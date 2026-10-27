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
#        por su huella SHA-256, sin guardarlos aqui).
#
#    .\preparar-env.ps1 -Renovar -TambienCifrado
#        Lo mismo, y tambien CIFRADO_LLAVE. Cuidado: con ella se cifran los
#        secretos de las suscripciones guardadas; despues de cambiarla, cada
#        sistema conectado (APOFYX, Patrimonio) tiene que volver a conectarse.
#
#    .\preparar-env.ps1 -Cambiar INTERNAL_KEY[,OTRO]
#        Cambia esos secretos aunque ya tengan un valor propio: para cuando uno
#        se filtro (quedo en una captura, en un registro, en un chat). Las
#        claves de una base (DB_DEBT_PASSWORD, ...) se cambian tambien dentro de
#        esa base, que tiene que estar arriba, sin perder datos.
#
#  Nunca muestra un valor. Despues: docker compose --profile app up -d.
# =============================================================================
param(
    [switch]$Renovar,
    [switch]$TambienCifrado,
    [string[]]$Cambiar = @(),
    #  Como empiezan los contenedores de las tres bases (tbridge-db-auth, ...).
    #  Solo cambia en una prueba.
    [string]$Contenedores = 'tbridge-db'
)

$ErrorActionPreference = 'Stop'
$archivo = Join-Path $PSScriptRoot '.env'
$ejemplo = Join-Path $PSScriptRoot '.env.example'
$sinBom = New-Object System.Text.UTF8Encoding($false)

#  Los secretos, y la huella SHA-256 del valor publico que tenia cada uno. Las
#  claves de las tres bases (#238) nunca tuvieron uno: van sin huella.
$secretos = [ordered]@{
    DB_AUTH_ROOT_PASSWORD     = ''
    DB_AUTH_PASSWORD          = ''
    DB_DEBT_ROOT_PASSWORD     = ''
    DB_DEBT_PASSWORD          = ''
    DB_PAYMENTS_ROOT_PASSWORD = ''
    DB_PAYMENTS_PASSWORD      = ''
    RABBIT_PASSWORD     = '84983c60f7daadc1cb8698621f802c0d9f9a3c3c295c810748fb048115c186ec'
    INTERNAL_KEY        = 'cc9283bf0f8238f8dc57b9b97c24f62e132bc491e1b4a2b07c2840005f72b621'
    CIFRADO_LLAVE       = '774db0f5ff463139c885933a38068fec5a0648f9fc9f2605d6c786eeac406318'
    WEBHOOK_SECRET      = 'ec9ed8b11fa0ab12b53bcdca18a37def89e7c16c5d65248906469dc0972fb8f5'
}

#  De que base es cada clave, y en que contenedor vive: DB_DEBT_PASSWORD es de
#  DB_DEBT, que vive en tbridge-db-debt.
function Base-De([string]$nombre) { return ($nombre -replace '_(ROOT_)?PASSWORD$', '') }
function Contenedor-De([string]$base) {
    return "$Contenedores-" + ($base -replace '^DB_', '').ToLower()
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
function Cambiar-En-MySQL([string]$Contenedor, [string]$rootActual, [string]$sql) {
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

if (-not (Test-Path $archivo)) {
    Copy-Item $ejemplo $archivo
    Write-Host "Cree el .env desde .env.example."
}
$lineas = New-Object System.Collections.Generic.List[string]
foreach ($linea in [System.IO.File]::ReadAllLines($archivo, $sinBom)) { $lineas.Add($linea) }

function Esta-Arriba([string]$Contenedor) {
    $ErrorActionPreference = 'Continue'
    $estado = docker inspect -f '{{.State.Running}}' $Contenedor 2>$null
    $arriba = ($LASTEXITCODE -eq 0 -and $estado -eq 'true')
    $ErrorActionPreference = 'Stop'
    return $arriba
}

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
    if ($nombre -like 'DB_*') {
        $base = Base-De $nombre
        $contenedor = Contenedor-De $base
        if (-not (Esta-Arriba $contenedor)) {
            Write-Host "$nombre`: $contenedor no esta arriba; levantalo (docker compose up -d) y vuelve a correrlo." -ForegroundColor Yellow
            continue
        }
        if ($nombre -like '*_ROOT_PASSWORD') {
            $sql = "ALTER USER IF EXISTS 'root'@'%' IDENTIFIED BY '$nueva'; ALTER USER IF EXISTS 'root'@'localhost' IDENTIFIED BY '$nueva';"
        } else {
            $sql = "ALTER USER IF EXISTS '$usuarioBase'@'%' IDENTIFIED BY '$nueva';"
        }
        #  La de root se lee recien aqui: si se cambio en esta misma vuelta, ya es la nueva.
        if (-not (Cambiar-En-MySQL $contenedor (Valor $lineas "${base}_ROOT_PASSWORD") $sql)) {
            Write-Host "$nombre`: MySQL no acepto el cambio; el .env queda como estaba." -ForegroundColor Red
            continue
        }
    }
    Poner $lineas $nombre $nueva
    Write-Host "$nombre`: renovado." -ForegroundColor Green
}

if (-not (Valor $lineas 'AUTH_JWKS_URI')) {
    Poner $lineas 'AUTH_JWKS_URI' 'http://127.0.0.1:8081/.well-known/jwks.json'
}
# La firma usa un directorio, no un secreto compartido. Nunca pisa sus archivos.
$directorioJwt = Valor $lineas 'JWT_KEY_DIRECTORY'
if (-not $directorioJwt) {
    $candidatoJwt = Join-Path $PSScriptRoot '.secrets/jwt'
    if ((Test-Path -LiteralPath (Join-Path $candidatoJwt 'private.pem')) -and
        (Test-Path -LiteralPath (Join-Path $candidatoJwt 'public.pem'))) {
        $directorioJwt = (Resolve-Path -LiteralPath $candidatoJwt).Path
        Poner $lineas 'JWT_KEY_DIRECTORY' $directorioJwt.Replace('\', '/')
    }
}
if (-not $directorioJwt -or
    -not (Test-Path -LiteralPath (Join-Path $directorioJwt 'private.pem')) -or
    -not (Test-Path -LiteralPath (Join-Path $directorioJwt 'public.pem'))) {
    Write-Warning 'Falta la pareja JWT. Ejecuta scripts/generar-claves-jwt.ps1 y configura JWT_KEY_DIRECTORY; ver docs/seguridad/jwt.md.'
}
[System.IO.File]::WriteAllLines($archivo, $lineas, $sinBom)
Write-Host ""
Write-Host "Listo. Para que los servicios tomen los valores: docker compose --profile app up -d"

exit 0
