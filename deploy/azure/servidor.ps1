# =============================================================================
#  Interruptor del servidor de DataBridge en Azure (#468): si esta apagado lo
#  prende, si esta prendido lo apaga. Apagar es desasignar: Azure deja de cobrar
#  la maquina y solo cobra el disco y la IP fija (unos US$7 al mes).
#
#  Necesita Azure CLI (winget install -e --id Microsoft.AzureCLI) y una cuenta
#  con permiso para prender y apagar la maquina. Uso, desde la raiz del repo:
#
#    powershell -ExecutionPolicy Bypass -File deploy\azure\servidor.ps1
#    powershell -ExecutionPolicy Bypass -File deploy\azure\servidor.ps1 -Grupo otro-grupo -Maquina otra-vm
#
#  Al prenderla, el servidor se pone al dia solo con main
#  (deploy/databridge-actualizar.service).
# =============================================================================
param(
    [string]$Grupo = 'rg-databridge',
    [string]$Maquina = 'vm-databridge'
)
$ErrorActionPreference = 'Stop'
$az = 'C:\Program Files\Microsoft SDKs\Azure\CLI2\wbin\az.cmd'
if (-not (Test-Path $az)) { $az = 'az' }

function Esperar-Tecla { Write-Host ''; Read-Host 'Presiona Enter para cerrar' | Out-Null }

& $az account show --only-show-errors -o none 2>$null
if ($LASTEXITCODE -ne 0) {
    Write-Host 'No hay sesion de Azure: se abre el navegador para entrar.' -ForegroundColor Yellow
    & $az login --only-show-errors -o none
    if ($LASTEXITCODE -ne 0) { Write-Host 'No se pudo entrar a Azure.' -ForegroundColor Red; Esperar-Tecla; exit 1 }
}

#  Quien entra con acceso del equipo suele tener por omision su propia
#  suscripcion de estudiante: se busca la que tiene el grupo.
$sub = & $az account list --query "[].id" -o tsv --only-show-errors |
    Where-Object { (& $az group exists -n $Grupo --subscription $_ --only-show-errors) -eq 'true' } |
    Select-Object -First 1
if (-not $sub) {
    Write-Host "Ninguna de tus suscripciones tiene el grupo $Grupo. Pide acceso a quien es dueno de la maquina." -ForegroundColor Red
    Esperar-Tecla; exit 1
}

$nombre = & $az vm show -d -g $Grupo -n $Maquina --subscription $sub --query fqdns -o tsv --only-show-errors
$sitio = if ($nombre) { "https://$nombre" } else { '' }
$estado = & $az vm get-instance-view -g $Grupo -n $Maquina --subscription $sub --query "instanceView.statuses[?starts_with(code,'PowerState/')].displayStatus | [0]" -o tsv --only-show-errors
Write-Host ''
Write-Host '  DataBridge en Azure' -ForegroundColor Cyan
if ($sitio) { Write-Host "  $sitio" }
Write-Host "  Estado actual: $estado"
Write-Host ''

if ($estado -eq 'VM running') {
    $r = Read-Host 'El servidor esta PRENDIDO. Apagarlo para que no gaste? (s/n)'
    if ($r -notmatch '^[sS]') { Write-Host 'No se hizo nada.'; Esperar-Tecla; exit 0 }
    Write-Host 'Apagando (desasignando)... toma 1 a 2 minutos.'
    & $az vm deallocate -g $Grupo -n $Maquina --subscription $sub --only-show-errors -o none
    if ($LASTEXITCODE -eq 0) { Write-Host 'Listo: servidor APAGADO.' -ForegroundColor Green }
    else { Write-Host 'Azure respondio con un error al apagar.' -ForegroundColor Red }
    Esperar-Tecla; exit 0
}

if ($estado -match 'deallocat|stopped') {
    $r = Read-Host 'El servidor esta APAGADO. Prenderlo? (s/n)'
    if ($r -notmatch '^[sS]') { Write-Host 'No se hizo nada.'; Esperar-Tecla; exit 0 }
    Write-Host 'Prendiendo... toma 1 a 2 minutos; DataBridge tarda otros 2 a 3 en levantar,'
    Write-Host 'y si hubo cambios en main mientras estaba apagado, se actualiza solo.'
    & $az vm start -g $Grupo -n $Maquina --subscription $sub --only-show-errors -o none
    if ($LASTEXITCODE -ne 0) { Write-Host 'Azure respondio con un error al prender.' -ForegroundColor Red; Esperar-Tecla; exit 1 }
    $ok = $false
    if ($sitio) {
        Write-Host 'Maquina prendida. Esperando a que DataBridge responda...'
        for ($i = 0; $i -lt 60 -and -not $ok; $i++) {
            try { $resp = Invoke-WebRequest -Uri $sitio -UseBasicParsing -TimeoutSec 10; if ($resp.StatusCode -eq 200) { $ok = $true } } catch { Start-Sleep -Seconds 10 }
        }
    }
    if ($ok) { Write-Host "Listo: DataBridge esta en linea en $sitio" -ForegroundColor Green }
    else { Write-Host 'La maquina esta prendida, pero DataBridge aun no responde. Espera un par de minutos.' -ForegroundColor Yellow }
    Esperar-Tecla; exit 0
}

Write-Host "El servidor esta en un estado intermedio ($estado). Espera un minuto y vuelve a abrir el interruptor." -ForegroundColor Yellow
Esperar-Tecla
