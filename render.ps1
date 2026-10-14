# =============================================================================
#  render.ps1 — Gestión e integración local con Render (render.com)
# =============================================================================
#  Permite consultar y gestionar los servicios y despliegues en Render
#  utilizando la API oficial de Render.
#
#  Uso:
#    .\render.ps1 -Estado               # Consulta el workspace y lista los servicios
#    .\render.ps1 -Despliegues <id>     # Muestra los últimos despliegues de un servicio
#    .\render.ps1 -Desplegar <id>       # Dispara un despliegue en Render
#    .\render.ps1 -Validar              # Valida la existencia y sintaxis de render.yaml
# =============================================================================

[CmdletBinding(DefaultParameterSetName = 'Estado')]
param(
    [Parameter(ParameterSetName = 'Estado')]
    [switch]$Estado,

    [Parameter(ParameterSetName = 'Despliegues')]
    [string]$Despliegues,

    [Parameter(ParameterSetName = 'Desplegar')]
    [string]$Desplegar,

    [Parameter(ParameterSetName = 'Validar')]
    [switch]$Validar,

    [Parameter()]
    [string]$ApiKey = $env:RENDER_API_KEY
)

$ErrorActionPreference = 'Stop'

if (-not $ApiKey) {
    # Clave de API de Render por omisión para este entorno
    $ApiKey = 'rnd_XjD7eeY0omnHmK7oWIcJzlALNAet'
}

$headers = @{
    'Authorization' = "Bearer $ApiKey"
    'Accept'        = 'application/json'
    'Content-Type'  = 'application/json'
}

$baseUrl = 'https://api.render.com/v1'

function Invocar-RenderApi {
    param(
        [string]$Ruta,
        [string]$Metodo = 'GET',
        [string]$Cuerpo = $null
    )
    try {
        $params = @{
            Uri     = "$baseUrl/$Ruta"
            Method  = $Metodo
            Headers = $headers
        }
        if ($Cuerpo) { $params.Body = $Cuerpo }
        $respuesta = Invoke-RestMethod @params
        return $respuesta
    } catch {
        Write-Error "Error al llamar a la API de Render ($Ruta): $_"
        return $null
    }
}

if ($Validar) {
    Write-Host "`n=== Validando Blueprint render.yaml ===" -ForegroundColor Cyan
    $yamlPath = Join-Path $PSScriptRoot 'render.yaml'
    if (Test-Path $yamlPath) {
        $contenido = Get-Content $yamlPath -Raw
        $lineas = (Get-Content $yamlPath).Count
        Write-Host " [OK] render.yaml encontrado ($lineas líneas)." -ForegroundColor Green
        if ($contenido -match 'services:') {
            Write-Host " [OK] Sección 'services:' declarada correctamente." -ForegroundColor Green
        }
        if ($contenido -match 'tbridge-portal' -and $contenido -match 'tbridge-gateway') {
            Write-Host " [OK] Portal y Gateway declarados." -ForegroundColor Green
        }
    } else {
        Write-Host " [ERROR] No se encontró render.yaml en la raíz del proyecto." -ForegroundColor Red
    }
    return
}

if ($Despliegues) {
    Write-Host "`n=== Despliegues para el servicio: $Despliegues ===" -ForegroundColor Cyan
    $deploys = Invocar-RenderApi "services/$Despliegues/deploys"
    if ($deploys) {
        $deploys | ForEach-Object {
            [PSCustomObject]@{
                ID        = $_.deploy.id
                Estado    = $_.deploy.status
                Creado    = $_.deploy.createdAt
                Commit    = $_.deploy.commit.id
                Mensaje   = $_.deploy.commit.message
            }
        } | Format-Table -AutoSize
    }
    return
}

if ($Desplegar) {
    Write-Host "`n=== Disparando despliegue para: $Desplegar ===" -ForegroundColor Cyan
    $respuesta = Invocar-RenderApi "services/$Desplegar/deploys" -Metodo 'POST' -Cuerpo '{}'
    if ($respuesta) {
        Write-Host " [OK] Despliegue iniciado con ID: $($respuesta.id)" -ForegroundColor Green
        Write-Host " Estado inicial: $($respuesta.status)" -ForegroundColor Yellow
    }
    return
}

# Por omisión: -Estado
Write-Host "`n========================================================" -ForegroundColor Cyan
Write-Host "  Estado del Workspace en Render (render.com)" -ForegroundColor Cyan
Write-Host "========================================================" -ForegroundColor Cyan

$owners = Invocar-RenderApi 'owners'
if ($owners) {
    $owner = $owners[0].owner
    Write-Host " Workspace : $($owner.name) (ID: $($owner.id))" -ForegroundColor Green
    Write-Host " Email     : $($owner.email)" -ForegroundColor Gray
    Write-Host " Tipo      : $($owner.type)" -ForegroundColor Gray
}

$services = Invocar-RenderApi 'services'
Write-Host "`n--- Servicios configurados ---" -ForegroundColor Cyan
if ($services -and $services.Count -gt 0) {
    $services | ForEach-Object {
        $s = $_.service
        [PSCustomObject]@{
            Nombre      = $s.name
            Tipo        = $s.type
            Entorno     = $s.env
            Estado      = $s.status
            URL         = $s.serviceDetails.url
            Actualizado = $s.updatedAt
        }
    } | Format-Table -AutoSize
} else {
    Write-Host " No hay servicios creados aún en este workspace." -ForegroundColor Yellow
    Write-Host " Conecta tu repositorio de GitHub en el dashboard de Render" -ForegroundColor Gray
    Write-Host " o sincroniza mediante el Blueprint 'render.yaml'." -ForegroundColor Gray
}
Write-Host ""
