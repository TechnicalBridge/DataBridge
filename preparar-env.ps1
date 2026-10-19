# =============================================================================
#  preparar-env.ps1 - el .env de DataBridge, con secretos al azar
# =============================================================================
#  Los secretos no tienen valor por omision en el repositorio: un valor escrito
#  ahi seria publico. Este script los genera en tu .env, que no se sube.
#
#    .\preparar-env.ps1
#        Crea el .env desde .env.example si no existe, y completa cada secreto
#        que falte o este vacio. Lo que ya tiene valor no se toca.
#
#    .\preparar-env.ps1 -Renovar
#        Ademas cambia los secretos que todavia tengan uno de los valores de
#        desarrollo que antes venian escritos en el repositorio (los reconoce
#        por su huella SHA-256, sin guardarlos aqui). Las claves de MySQL se
#        cambian tambien dentro de la base, si esta arriba, sin perder datos.
#
#    CIFRADO_LLAVE se rota con scripts\rotar-cifrado.cjs y respaldo, nunca
#    reemplazando la llave sin migrar las suscripciones existentes.
#
#    .\preparar-env.ps1 -Cambiar JWT_SECRET[,OTRO]
#        Cambia esos secretos aunque ya tengan un valor propio: para cuando uno
#        se filtro (quedo en una captura, en un registro, en un chat).
#
#  Nunca muestra un valor. Despues: docker compose --profile app up -d.
# =============================================================================
param(
    [switch]$Renovar,
    [switch]$TambienCifrado,
    [string[]]$Cambiar = @(),
    #  El contenedor de MySQL de DataBridge. Solo cambia en una prueba.
    [string]$Contenedor = 'tbridge-db'
)

$ErrorActionPreference = 'Stop'
if ($TambienCifrado -or $Cambiar -contains 'CIFRADO_LLAVE') {
    Write-Host 'CIFRADO_LLAVE requiere la migracion con respaldo: scripts\rotar-cifrado.cjs. No se modifico el entorno.' -ForegroundColor Red
    exit 1
}
$archivo = Join-Path $PSScriptRoot '.env'
$ejemplo = Join-Path $PSScriptRoot '.env.example'
$sinBom = New-Object System.Text.UTF8Encoding($false)

#  Los secretos, y la huella SHA-256 del valor publico que tenia cada uno.
$secretos = [ordered]@{
    MYSQL_ROOT_PASSWORD = '5012f5182061c46e57859cf617128c6f70eddfba4db27772bdede5a039fa7085'
    MYSQL_PASSWORD      = '068a635d2c022dfafb6c163b832327ab9bd8b944c3f44af23bf97cbc6591be1c'
    RABBIT_PASSWORD     = '84983c60f7daadc1cb8698621f802c0d9f9a3c3c295c810748fb048115c186ec'
    JWT_SECRET          = '686101b1c40874b07191bc92a9965f89fa570088218f88e30abbe768737f347f'
    INTERNAL_KEY        = 'cc9283bf0f8238f8dc57b9b97c24f62e132bc491e1b4a2b07c2840005f72b621'
    CIFRADO_LLAVE       = '774db0f5ff463139c885933a38068fec5a0648f9fc9f2605d6c786eeac406318'
    WEBHOOK_SECRET      = 'ec9ed8b11fa0ab12b53bcdca18a37def89e7c16c5d65248906469dc0972fb8f5'
}

function Nueva-Clave {
    #  48 bytes al azar del generador criptografico, en base64 sin + / ni =:
    #  nada que rompa un .env, una URL, una linea de SQL o la de comandos.
    $bytes = New-Object byte[] 48
    $generador = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    $generador.GetBytes($bytes)
    $generador.Dispose()
    return [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

function Huella([string]$texto) {
    $sha = [System.Security.Cryptography.SHA256]::Create()
    $hash = $sha.ComputeHash($sinBom.GetBytes($texto))
    $sha.Dispose()
    return -join ($hash | ForEach-Object { $_.ToString('x2') })
}

function Valor([System.Collections.Generic.List[string]]$lineas, [string]$nombre) {
    foreach ($linea in $lineas) {
        if ($linea -match "^\s*$nombre\s*=(.*)$") { return $Matches[1].Trim() }
    }
    return $null
}

function Poner([System.Collections.Generic.List[string]]$lineas, [string]$nombre, [string]$valor) {
    for ($i = 0; $i -lt $lineas.Count; $i++) {
        if ($lineas[$i] -match "^\s*$nombre\s*=") { $lineas[$i] = "$nombre=$valor"; return }
    }
    $lineas.Add("$nombre=$valor")
}

#  Cambia la clave de un usuario dentro del MySQL que esta corriendo. La clave
#  vieja y la nueva viajan por el entorno y por la entrada estandar, nunca en
#  la linea de comandos, donde cualquier proceso del equipo las podria leer.
function Cambiar-En-MySQL([string]$rootActual, [string]$sql) {
    $env:MYSQL_PWD = $rootActual
    $antes = $ErrorActionPreference
    #  En PowerShell 5.1 lo que un programa escribe en stderr corta el script
    #  si la preferencia es Stop: aqui se mira el codigo de salida.
    $ErrorActionPreference = 'Continue'
    try {
        $sql | docker exec -i -e MYSQL_PWD $Contenedor mysql -uroot 2>$null | Out-Null
        return ($LASTEXITCODE -eq 0)
    } finally {
        $ErrorActionPreference = $antes
        Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
    }
}

if (Test-Path -LiteralPath $archivo) {
    $existentes = New-Object System.Collections.Generic.List[string]
    foreach ($linea in [System.IO.File]::ReadAllLines($archivo, $sinBom)) { $existentes.Add($linea) }
    if (-not (Valor $existentes 'CIFRADO_LLAVE')) {
        Write-Host 'Falta CIFRADO_LLAVE en una instalacion existente. Recupere el respaldo; no se genera otra llave.' -ForegroundColor Red
        exit 1
    }
}

if (-not (Test-Path $archivo)) {
    Copy-Item $ejemplo $archivo
    Write-Host "Cree el .env desde .env.example."
}
$lineas = New-Object System.Collections.Generic.List[string]
foreach ($linea in [System.IO.File]::ReadAllLines($archivo, $sinBom)) { $lineas.Add($linea) }

$baseArriba = $false
if ($Renovar -or $Cambiar.Count -gt 0) {
    $ErrorActionPreference = 'Continue'
    $estado = docker inspect -f '{{.State.Running}}' $Contenedor 2>$null
    $baseArriba = ($LASTEXITCODE -eq 0 -and $estado -eq 'true')
    $ErrorActionPreference = 'Stop'
}

$rootActual = Valor $lineas 'MYSQL_ROOT_PASSWORD'
$usuarioBase = Valor $lineas 'MYSQL_USER'
if (-not $usuarioBase) { $usuarioBase = 'tbridge' }

foreach ($nombre in $secretos.Keys) {
    $actual = Valor $lineas $nombre
    if (-not $actual) {
        Poner $lineas $nombre (Nueva-Clave)
        Write-Host "$nombre`: generado."
        continue
    }
    $forzado = $Cambiar -contains $nombre
    if ((Huella $actual) -ne $secretos[$nombre] -and -not $forzado) {
        Write-Host "$nombre`: ya tiene un valor propio, no se toca."
        continue
    }
    if (-not $Renovar -and -not $forzado) {
        Write-Host "$nombre`: tiene el valor publico de antes. Cambialo con -Renovar." -ForegroundColor Yellow
        continue
    }
    if ($nombre -eq 'CIFRADO_LLAVE' -and -not $TambienCifrado -and -not $forzado) {
        Write-Host "CIFRADO_LLAVE: tiene el valor publico de antes. No la cambio sin -TambienCifrado (los sistemas conectados tendrian que volver a conectarse)." -ForegroundColor Yellow
        continue
    }
    $nueva = Nueva-Clave
    if ($nombre -like 'MYSQL_*') {
        if (-not $baseArriba) {
            Write-Host "$nombre`: la base no esta arriba; levantala (docker compose up -d) y vuelve a correr con -Renovar." -ForegroundColor Yellow
            continue
        }
        if ($nombre -eq 'MYSQL_ROOT_PASSWORD') {
            $sql = "ALTER USER IF EXISTS 'root'@'%' IDENTIFIED BY '$nueva'; ALTER USER IF EXISTS 'root'@'localhost' IDENTIFIED BY '$nueva';"
        } else {
            $sql = "ALTER USER IF EXISTS '$usuarioBase'@'%' IDENTIFIED BY '$nueva';"
        }
        if (-not (Cambiar-En-MySQL $rootActual $sql)) {
            Write-Host "$nombre`: MySQL no acepto el cambio; el .env queda como estaba." -ForegroundColor Red
            continue
        }
        if ($nombre -eq 'MYSQL_ROOT_PASSWORD') { $rootActual = $nueva }
    }
    Poner $lineas $nombre $nueva
    Write-Host "$nombre`: renovado." -ForegroundColor Green
}

[System.IO.File]::WriteAllLines($archivo, $lineas, $sinBom)
Write-Host ""
Write-Host "Listo. Para que los servicios tomen los valores: docker compose --profile app up -d"

exit 0
