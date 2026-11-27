param(
    [switch]$SoloVisualizar,
    [switch]$SinVisualizar,
    [switch]$SinAbrir,
    [ValidateRange(1, 65535)]
    [int]$Puerto = 8766,
    [string]$Proyecto,
    [string]$DirectorioEntorno,
    [ValidateSet(
        'smoke', 'response-time', 'load', 'concurrency', 'scalability',
        'api-performance', 'database-performance', 'volume', 'spike',
        'recovery', 'capacity', 'stress', 'endurance'
    )]
    [string[]]$Escenarios = @(
        'smoke', 'response-time', 'load', 'concurrency', 'scalability',
        'api-performance', 'database-performance', 'volume', 'spike',
        'recovery', 'capacity', 'stress', 'endurance'
    )
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
    throw 'Indica -Proyecto con el nombre del Compose local activo (consultalo con: docker compose ls).'
}
if (-not $DirectorioEntorno) {
    $gateway = docker inspect "$proyecto-gateway-1" | ConvertFrom-Json
    if ($LASTEXITCODE -ne 0) { throw "No se encontro el gateway del proyecto $proyecto" }
    $DirectorioEntorno = $gateway.Config.Labels.'com.docker.compose.project.working_dir'
}
if (-not $DirectorioEntorno -or -not (Test-Path (Join-Path $DirectorioEntorno 'docker-compose.yml'))) {
    throw 'Indica -DirectorioEntorno con la ruta del Compose activo.'
}
if (-not $Escenarios) { throw 'Indica al menos un escenario en -Escenarios.' }
$directorio = $DirectorioEntorno
$baseCompose = @('--project-directory', $directorio, '-p', $proyecto, '-f', (Join-Path $directorio 'docker-compose.yml'))
$servicios = @('gateway', 'ms-auth')
$limites = @(
    @{ Servicio = 'gateway'; Nombre = 'RATE_GLOBAL_CAPACITY'; Valor = '10000000' },
    @{ Servicio = 'gateway'; Nombre = 'RATE_AUTH_CAPACITY'; Valor = '10000000' },
    @{ Servicio = 'ms-auth'; Nombre = 'AUTH_IDENTIFIER_LIMIT_CAPACITY'; Valor = '1000000' }
)

# Lee solo la variable pedida; nunca vuelca el entorno completo del contenedor.
function Leer-VariableContenedor([string]$servicio, [string]$variable) {
    $lineas = docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$proyecto-$servicio-1"
    if ($LASTEXITCODE -ne 0) { throw "No se pudo inspeccionar $servicio" }
    $prefijo = "$variable="
    $linea = $lineas | Where-Object { $_.StartsWith($prefijo, [StringComparison]::Ordinal) } |
        Select-Object -First 1
    if ($null -eq $linea) { return $null }
    return $linea.Substring($prefijo.Length)
}

$env:INTERNAL_KEY = Leer-VariableContenedor 'ms-auth' 'INTERNAL_KEY'
if (-not $env:INTERNAL_KEY) { throw 'Falta la clave interna del entorno activo.' }

# Los valores originales viven solo en memoria: no se escriben en disco.
$limitesOriginales = @{}
$variablesPrevias = @{}
foreach ($limite in $limites) {
    $clave = "$($limite.Servicio)/$($limite.Nombre)"
    $limitesOriginales[$clave] = Leer-VariableContenedor $limite.Servicio $limite.Nombre
    $variablesPrevias[$limite.Nombre] = [Environment]::GetEnvironmentVariable($limite.Nombre, 'Process')
}
$aplicados = @{}
foreach ($limite in $limites) {
    $aplicados["$($limite.Servicio)/$($limite.Nombre)"] = $limite.Valor
}
$estado = @{
    inicio = (Get-Date).ToUniversalTime().ToString('o')
    limitesRestaurados = $false
    escenarios = $Escenarios
    limites = @{ antes = $limitesOriginales; aplicados = $aplicados; despues = @{} }
    ejecuciones = @()
}
$estadoPath = Join-Path $reportes 'suite-completa-ejecuciones.json'
try {
    foreach ($limite in $limites) {
        [Environment]::SetEnvironmentVariable($limite.Nombre, $limite.Valor, 'Process')
    }
    $resumenCupos = ($limites | ForEach-Object { "$($_.Nombre)=$($_.Valor)" }) -join ', '
    Write-Output "Cupos temporales aplicados: $resumenCupos"
    #  Compose lee los cupos del entorno: basta con recrear estos dos servicios.
    docker compose @baseCompose --profile app up -d --no-deps --no-build --pull never --wait @servicios
    if ($LASTEXITCODE -ne 0) { throw 'No se pudieron preparar los limites temporales.' }
    foreach ($escenario in $Escenarios) {
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
    #  Se restauran solo las tres variables de cupo; el resto del entorno no se toca.
    foreach ($limite in $limites) {
        $previo = $variablesPrevias[$limite.Nombre]
        if ($null -eq $previo) {
            [Environment]::SetEnvironmentVariable($limite.Nombre, $null, 'Process')
        } else {
            [Environment]::SetEnvironmentVariable($limite.Nombre, $previo, 'Process')
        }
    }
    docker compose @baseCompose --profile app up -d --no-deps --no-build --pull never --wait @servicios
    $codigoRestauracion = $LASTEXITCODE
    $restaurados = ($codigoRestauracion -eq 0)
    foreach ($limite in $limites) {
        $clave = "$($limite.Servicio)/$($limite.Nombre)"
        $actual = $null
        try {
            $actual = Leer-VariableContenedor $limite.Servicio $limite.Nombre
        } catch {
            $restaurados = $false
        }
        $estado.limites.despues[$clave] = $actual
        if ($actual -ne $limitesOriginales[$clave]) { $restaurados = $false }
    }
    $estado.limitesRestaurados = $restaurados
    $estado.fin = (Get-Date).ToUniversalTime().ToString('o')
    $estado | ConvertTo-Json -Depth 10 | Set-Content -Encoding UTF8 $estadoPath
    [Environment]::SetEnvironmentVariable('INTERNAL_KEY', $null, 'Process')
    if (-not $restaurados) {
        Write-Error 'No se pudieron restaurar los cupos. Revisa suite-completa-ejecuciones.json y vuelve a levantar gateway y ms-auth sin las variables de cupo.'
    }
}
if (-not $estado.limitesRestaurados) {
    throw 'La restauracion fallo. Revisa suite-completa-ejecuciones.json antes de continuar.'
}
# Se genera y publica despues del finally, nunca mientras se restauran servicios.
Mostrar-Evidencias
