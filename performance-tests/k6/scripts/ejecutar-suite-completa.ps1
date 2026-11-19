param(
    [switch]$SoloVisualizar,
    [switch]$SinVisualizar,
    [switch]$SinAbrir,
    [ValidateRange(1, 65535)]
    [int]$Puerto = 8766,
    [string]$Proyecto,
    [string]$DirectorioEntorno
)
$ErrorActionPreference = 'Stop'
$raiz = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$reportes = Join-Path $raiz 'performance-tests\k6\reports'
$generador = Join-Path $PSScriptRoot 'generar-evidencias.py'
# Verificar Python antes de modificar los servicios.
Get-Command python -ErrorAction Stop | Out-Null
function Mostrar-Evidencias {
    $argumentos = @($generador, '--report-dir', $reportes)
    if (-not $SinVisualizar) {
        $argumentos += @('--servir', '--puerto', "$Puerto")
        if ($SinAbrir) { $argumentos += '--sin-abrir' }
    }
    & python @argumentos
    if ($LASTEXITCODE -ne 0) { throw 'No se pudieron generar o servir las evidencias.' }
}
if ($SoloVisualizar) {
    Mostrar-Evidencias
    return
}
$proyecto = $Proyecto
if (-not $proyecto) {
    throw 'Indica -Proyecto con el nombre del Compose local activo (por ejemplo: databridge).'
}
if (-not $DirectorioEntorno) {
    $gateway = docker inspect "$proyecto-gateway-1" | ConvertFrom-Json
    if ($LASTEXITCODE -ne 0) { throw "No se encontro el gateway del proyecto $proyecto" }
    $DirectorioEntorno = $gateway.Config.Labels.'com.docker.compose.project.working_dir'
}
if (-not $DirectorioEntorno -or -not (Test-Path (Join-Path $DirectorioEntorno 'docker-compose.yml'))) {
    throw 'Indica -DirectorioEntorno con la ruta del Compose activo.'
}
$directorio = $DirectorioEntorno
$baseTemporal = Join-Path $env:LOCALAPPDATA 'Temp\opencode'
if (-not (Test-Path $baseTemporal)) { New-Item -ItemType Directory -Path $baseTemporal -Force | Out-Null }
$temporal = Join-Path $baseTemporal ("databridge-rendimiento-" + [guid]::NewGuid())
New-Item -ItemType Directory -Path $temporal | Out-Null
$original = @{ services = @{} }
foreach ($servicio in @('gateway', 'ms-auth')) {
    $contenedor = docker inspect "$proyecto-$servicio-1" | ConvertFrom-Json
    if ($LASTEXITCODE -ne 0) { throw "No se pudo inspeccionar $servicio" }
    $variables = @{}
    foreach ($linea in $contenedor.Config.Env) {
        $partes = $linea -split '=', 2
        $variables[$partes[0]] = $partes[1]
    }
    $original.services[$servicio] = @{ image = $contenedor.Image; environment = $variables }
}
$env:INTERNAL_KEY = $original.services['ms-auth'].environment['INTERNAL_KEY']
if (-not $env:INTERNAL_KEY) { throw 'Falta la clave interna del entorno activo.' }
$restaurar = Join-Path $temporal 'restaurar.json'
$carga = Join-Path $temporal 'carga.json'
$original | ConvertTo-Json -Depth 10 | Set-Content -Encoding UTF8 $restaurar
$pruebas = $original | ConvertTo-Json -Depth 10 | ConvertFrom-Json
$pruebas.services.gateway.environment.RATE_GLOBAL_CAPACITY = '10000000'
$pruebas.services.gateway.environment.RATE_AUTH_CAPACITY = '10000000'
$pruebas.services.'ms-auth'.environment.AUTH_IDENTIFIER_LIMIT_CAPACITY = '1000000'
$pruebas | ConvertTo-Json -Depth 10 | Set-Content -Encoding UTF8 $carga
$baseCompose = @('--project-directory', $directorio, '-p', $proyecto, '-f', (Join-Path $directorio 'docker-compose.yml'))
$estado = @{ inicio = (Get-Date).ToUniversalTime().ToString('o'); limitesRestaurados = $false; ejecuciones = @() }
$estadoPath = Join-Path $reportes 'suite-completa-ejecuciones.json'
try {
    docker compose @baseCompose -f $carga --profile app up -d --no-deps --no-build --pull never --wait gateway ms-auth
    if ($LASTEXITCODE -ne 0) { throw 'No se pudieron preparar los limites temporales.' }
    foreach ($escenario in @('smoke', 'response-time', 'load', 'concurrency', 'scalability', 'api-performance', 'database-performance', 'volume', 'spike', 'recovery', 'capacity', 'stress', 'endurance')) {
        $inicio = (Get-Date).ToUniversalTime().ToString('o')
        Write-Output "Iniciando $escenario ($inicio)"
        # Cada escenario original conserva sus opciones, cargas y handleSummary.
        # Un fallo de k6 no impide ejecutar los demas ni restaurar los servicios.
        $ErrorActionPreference = 'Continue'
        docker run --rm --network "${proyecto}_default" -e INTERNAL_KEY `
            -e INCLUIR_IA=false -e PERMITIR_ESCRITURAS=false -e VERIFICAR_PASARELAS=false `
            -e DATA_DIR=/performance-tests/k6/data -e REPORT_DIR=/performance-tests/k6/reports `
            -v "${raiz}/performance-tests/k6:/performance-tests/k6" `
            grafana/k6:latest run --quiet "/performance-tests/k6/scenarios/$escenario.js" `
            *> (Join-Path $reportes "suite-$escenario.log")
        $codigo = $LASTEXITCODE
        $ErrorActionPreference = 'Stop'
        $reporte = Get-ChildItem $reportes -Filter "$escenario-2*.json" |
            Where-Object { $_.LastWriteTimeUtc -ge [datetime]::Parse($inicio).ToUniversalTime().AddSeconds(-2) } |
            Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
        $estado.ejecuciones += @{
            prueba = $escenario; inicio = $inicio; fin = (Get-Date).ToUniversalTime().ToString('o')
            codigo = $codigo; archivo = $(if ($reporte) { $reporte.Name } else { $null })
            entorno = $proyecto
        }
        $estado | ConvertTo-Json -Depth 10 | Set-Content -Encoding UTF8 $estadoPath
        Write-Output "$escenario : codigo de salida $codigo"
    }
} finally {
    Write-Output 'Restaurando la configuracion original de gateway y ms-auth...'
    $ErrorActionPreference = 'Continue'
    docker compose @baseCompose -f $restaurar --profile app up -d --no-deps --no-build --pull never --wait gateway ms-auth
    $estado.limitesRestaurados = ($LASTEXITCODE -eq 0)
    $estado.fin = (Get-Date).ToUniversalTime().ToString('o')
    $estado | ConvertTo-Json -Depth 10 | Set-Content -Encoding UTF8 $estadoPath
    Remove-Item Env:\INTERNAL_KEY
    if ($estado.limitesRestaurados) {
        Remove-Item -LiteralPath $carga, $restaurar
        Remove-Item -LiteralPath $temporal
    } else {
        Write-Error "No se pudo restaurar. Configuracion original conservada en $restaurar"
    }
}
if (-not $estado.limitesRestaurados) {
    throw 'La restauracion fallo. Revisa suite-completa-ejecuciones.json antes de continuar.'
}
# Se genera y publica despues del finally, nunca mientras se restauran servicios.
Mostrar-Evidencias
