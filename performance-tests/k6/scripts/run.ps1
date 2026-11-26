# =============================================================================
#  Corre un escenario de la suite modular dentro de Docker Compose.
# =============================================================================
#  Uso:
#      .\scripts\run.ps1 smoke
#      .\scripts\run.ps1 load -Levantar
#      .\scripts\run.ps1 stress -SinIa
#      .\scripts\run.ps1 concurrency -SoloLectura
#
#  -Levantar   crea el .env si falta, sube la pila con DEMO_DATOS=true y el
#              limitador del gateway alto, y espera a que este sana.
#  -SinIa      define INCLUIR_IA=false (no mide el chat del asistente).
#  -SoloLectura define PERMITIR_ESCRITURAS=false (no repacta).
#
#  El escenario corre en el servicio k6 de Compose, dentro de la red: las
#  rutas portal, gateway, ms-* y mailpit se resuelven ahi.
# =============================================================================
param(
    [Parameter(Position = 0)]
    [ValidateSet(
        'smoke', 'load', 'stress', 'spike', 'endurance', 'volume',
        'concurrency', 'scalability', 'response-time',
        'api-performance', 'database-performance', 'recovery', 'capacity'
    )]
    [string]$Escenario = 'smoke',

    [switch]$Levantar,
    [switch]$SinIa,
    [switch]$SoloLectura
)

$ErrorActionPreference = 'Stop'

#  performance-tests/k6/scripts -> raiz del repositorio.
$raiz = Resolve-Path (Join-Path $PSScriptRoot '..\..\..')
Set-Location $raiz

if (-not (Test-Path (Join-Path $raiz '.env'))) {
    Write-Host 'No hay .env: se crea con preparar-env.ps1'
    & (Join-Path $raiz 'preparar-env.ps1')
}

if ($Levantar) {
    #  Todo el trafico de k6 sale de una sola IP: con el cupo de fabrica se
    #  mediria el limitador del gateway, no el sistema. Cada escenario abre
    #  una sesion de empresa por correo, asi que los dos frenos de auth
    #  tambien se suben; si no, varios setup seguidos reciben 429.
    $env:RATE_GLOBAL_CAPACITY = '10000000'
    $env:RATE_AUTH_CAPACITY = '10000000'
    $env:AUTH_IDENTIFIER_LIMIT_CAPACITY = '1000000'
    $env:DEMO_DATOS = 'true'
    $env:INCLUIR_IA = if ($SinIa) { 'false' } else { $env:INCLUIR_IA }
    Write-Host 'Levantando la pila con la demo y el limitador alto...'
    docker compose --profile app up -d --wait
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

if ($SinIa) { $env:INCLUIR_IA = 'false' }
if ($SoloLectura) { $env:PERMITIR_ESCRITURAS = 'false' }

$archivo = "/performance-tests/k6/scenarios/$Escenario.js"
Write-Host "Corriendo $archivo ..."
docker compose --profile carga run --rm k6 run $archivo
exit $LASTEXITCODE
