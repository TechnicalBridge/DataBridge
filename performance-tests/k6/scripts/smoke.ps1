# =============================================================================
#  El primer paso: comprobar que la pila y la suite se hablan.
# =============================================================================
#  Corre una sesion de deudor, una de empresa por el buzon, el recorrido de
#  lectura, la salud del asistente y una revision pequena de cartera en CSV y
#  XLSX. La revision siempre hace rollback: no deja deudas.
#
#      .\scripts\smoke.ps1
#      .\scripts\smoke.ps1 -Levantar
#      .\scripts\smoke.ps1 -SinIa
# =============================================================================
param(
    [switch]$Levantar,
    [switch]$SinIa,
    [switch]$SoloLectura
)

$ErrorActionPreference = 'Stop'
$argumentos = @('smoke')
if ($Levantar) { $argumentos += '-Levantar' }
if ($SinIa) { $argumentos += '-SinIa' }
if ($SoloLectura) { $argumentos += '-SoloLectura' }

& (Join-Path $PSScriptRoot 'run.ps1') @argumentos
exit $LASTEXITCODE
