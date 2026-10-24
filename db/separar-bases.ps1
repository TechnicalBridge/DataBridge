# =============================================================================
#  db/separar-bases.ps1 - pasa los datos del MySQL unico a las tres bases (#238)
# =============================================================================
#  Hasta #238, tb_auth, tb_debt y tb_payments vivian en un solo MySQL (el
#  contenedor tbridge-db, con el volumen tbridge_db_data). Ahora cada servicio
#  tiene el suyo. Si en tu equipo ya habia datos, se pasan una sola vez:
#
#    .\preparar-env.ps1                  # las claves de las tres bases
#    .\db\separar-bases.ps1              # copia los datos
#    docker compose --profile app up -d --build --wait --remove-orphans
#
#  Que hace:
#    1. Apaga el MySQL de antes, sin borrarlo, y abre su volumen en un
#       contenedor de paso, sin puertos, solo para leerlo.
#    2. Levanta las tres bases nuevas. Si alguna ya tiene tablas, se detiene:
#       no pisa nada, salvo con -Forzar.
#    3. Vuelca cada base y la carga en la suya.
#    4. Cuenta, tabla por tabla, las filas de la vieja y de la nueva.
#    5. Borra el contenedor de paso. El volumen de antes queda intacto, con
#       sus datos, hasta que tu decidas borrarlo.
#
#  En un equipo nuevo, sin datos de antes, no hace falta: no hace nada.
#  Nunca muestra una clave. Los volcados quedan en una carpeta temporal.
# =============================================================================
param(
    #  El volumen de antes. Sin el, lo busca solo.
    [string]$Volumen = '',
    #  Cargar aunque las bases nuevas ya tengan tablas: las reemplaza.
    [switch]$Forzar
)

$ErrorActionPreference = 'Stop'
$raiz = Split-Path $PSScriptRoot -Parent
$archivoEnv = Join-Path $raiz '.env'
$sinBom = New-Object System.Text.UTF8Encoding($false)
$dePaso = 'tbridge-db-separar'
$bases = @(
    @{ Base = 'tb_auth';     Servicio = 'db-auth';     Contenedor = 'tbridge-db-auth';     Clave = 'DB_AUTH_ROOT_PASSWORD' },
    @{ Base = 'tb_debt';     Servicio = 'db-debt';     Contenedor = 'tbridge-db-debt';     Clave = 'DB_DEBT_ROOT_PASSWORD' },
    @{ Base = 'tb_payments'; Servicio = 'db-payments'; Contenedor = 'tbridge-db-payments'; Clave = 'DB_PAYMENTS_ROOT_PASSWORD' }
)

function Valor([string]$nombre) {
    foreach ($linea in [System.IO.File]::ReadAllLines($archivoEnv, $sinBom)) {
        if ($linea -match "^\s*$nombre\s*=(.*)$") { return $Matches[1].Trim() }
    }
    return $null
}

#  Corre un programa sin que PowerShell 5.1 corte el script por lo que escribe
#  en stderr (MySQL avisa ahi que MYSQL_PWD esta en desuso): se mira el codigo
#  de salida. La clave viaja por el entorno, nunca en la linea de comandos.
function Correr([string]$clave, [string[]]$argumentos) {
    $antes = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    if ($clave) { $env:MYSQL_PWD = $clave }
    try {
        $salida = & docker @argumentos 2>$null
        return @{ Ok = ($LASTEXITCODE -eq 0); Salida = $salida }
    } finally {
        $ErrorActionPreference = $antes
        Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
    }
}

#  Ojo al usarla: PowerShell entrega una sola fila como texto y no como lista,
#  asi que se llama dentro de @(...), y $filas[0] es la fila y no su primera letra.
function Consulta([string]$contenedor, [string]$clave, [string]$sql) {
    $r = Correr $clave @('exec', '-e', 'MYSQL_PWD', $contenedor, 'mysql', '-uroot', '-N', '-B', '-e', $sql)
    if (-not $r.Ok) { throw "La consulta en $contenedor no funciono." }
    return $r.Salida
}

#  Las filas de cada tabla de una base, como texto comparable: "tabla=filas".
function Filas([string]$contenedor, [string]$clave, [string]$base) {
    $tablas = @(Consulta $contenedor $clave "SELECT table_name FROM information_schema.tables WHERE table_schema = '$base' AND table_type = 'BASE TABLE' ORDER BY table_name")
    if ($tablas.Count -eq 0 -or -not $tablas[0]) { return @() }
    $partes = $tablas | ForEach-Object { "SELECT '$_', COUNT(*) FROM ``$base``.``$_``" }
    return Consulta $contenedor $clave ($partes -join ' UNION ALL ') | ForEach-Object { $_ -replace "`t", '=' }
}

function Esperar-Base([string]$contenedor, [string]$clave) {
    for ($i = 0; $i -lt 60; $i++) {
        $r = Correr $clave @('exec', '-e', 'MYSQL_PWD', $contenedor, 'mysqladmin', 'ping', '-h', '127.0.0.1', '-uroot', '--silent')
        if ($r.Ok) { return }
        Start-Sleep -Seconds 2
    }
    throw "$contenedor no respondio en dos minutos."
}

# ---- Lo que hace falta -------------------------------------------------------
if (-not (Test-Path $archivoEnv)) { throw "No hay .env en $raiz. Crealo con .\preparar-env.ps1." }
$claveVieja = Valor 'MYSQL_ROOT_PASSWORD'
foreach ($b in $bases) {
    $b.ClaveNueva = Valor $b.Clave
    if (-not $b.ClaveNueva) { throw "Falta $($b.Clave) en el .env. Corre primero .\preparar-env.ps1." }
}

if (-not $Volumen) {
    #  Sin comillas en el formato: PowerShell 5.1 las rompe al pasarlas a docker.
    $r = Correr '' @('inspect', '-f', '{{range .Mounts}}{{.Destination}}={{.Name}};{{end}}', 'tbridge-db')
    $montado = if ($r.Ok) { "$($r.Salida)".Split(';') | Where-Object { $_ -like '/var/lib/mysql=*' } } else { $null }
    if ($montado) {
        $Volumen = ($montado -split '=', 2)[1]
    } else {
        $candidatos = @((Correr '' @('volume', 'ls', '-q')).Salida | Where-Object { $_ -match '(^|_)tbridge_db_data$' })
        if ($candidatos.Count -gt 1) {
            throw "Hay mas de un volumen de antes ($($candidatos -join ', ')). Di cual con -Volumen."
        }
        if ($candidatos.Count -eq 1) { $Volumen = $candidatos[0] }
    }
}
if (-not $Volumen) {
    Write-Host 'No hay datos de antes en este equipo: no hay nada que pasar.'
    Write-Host 'Levanta todo con: docker compose --profile app up -d --build --wait'
    exit 0
}
if (-not $claveVieja) { throw 'Falta MYSQL_ROOT_PASSWORD en el .env: es la clave del MySQL de antes, y sin ella no se leen sus datos.' }

$carpeta = Join-Path $env:TEMP ('databridge-separar-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $carpeta | Out-Null
Write-Host "Volumen de antes: $Volumen"

try {
    # ---- 1. El MySQL de antes, de solo lectura ------------------------------
    #  Dos MySQL sobre el mismo volumen lo corromperian: el de antes se apaga.
    $r = Correr '' @('inspect', '-f', '{{.State.Running}}', 'tbridge-db')
    if ($r.Ok -and "$($r.Salida)".Trim() -eq 'true') {
        Write-Host 'Apago el MySQL de antes (tbridge-db), sin borrarlo.'
        if (-not (Correr '' @('stop', 'tbridge-db')).Ok) { throw 'No se pudo apagar tbridge-db.' }
    }
    Correr '' @('rm', '-f', $dePaso) | Out-Null
    $r = Correr '' @('run', '-d', '--name', $dePaso, '-v', "${Volumen}:/var/lib/mysql", 'mysql:8.4', '--performance-schema=OFF')
    if (-not $r.Ok) { throw 'No se pudo abrir el volumen de antes.' }
    Esperar-Base $dePaso $claveVieja
    Write-Host 'El volumen de antes esta abierto.'

    # ---- 2. Las tres bases nuevas, vacias -----------------------------------
    #  Sin servicios encima: Flyway no debe crear tablas mientras se cargan.
    Push-Location $raiz
    try {
        Correr '' @('compose', '--profile', 'app', 'stop', 'ms-auth', 'ms-debt', 'ms-payments') | Out-Null
        $r = Correr '' (@('compose', 'up', '-d', '--wait') + ($bases | ForEach-Object { $_.Servicio }))
        if (-not $r.Ok) { throw 'No se pudieron levantar las tres bases nuevas (docker compose up -d db-auth db-debt db-payments).' }
    } finally { Pop-Location }
    foreach ($b in $bases) {
        Esperar-Base $b.Contenedor $b.ClaveNueva
        $tablas = @(Consulta $b.Contenedor $b.ClaveNueva "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = '$($b.Base)'")
        if ([int]$tablas[0] -gt 0 -and -not $Forzar) {
            throw "$($b.Contenedor) ya tiene $($tablas[0]) tablas en $($b.Base). No piso nada: si quieres reemplazarlas por las de antes, corre de nuevo con -Forzar."
        }
    }

    # ---- 3. Volcar y cargar -------------------------------------------------
    foreach ($b in $bases) {
        $base = $b.Base
        $dentro = "/tmp/$base.sql"
        $r = Correr $claveVieja @('exec', '-e', 'MYSQL_PWD', $dePaso, 'sh', '-c',
            "mysqldump -uroot --single-transaction --routines --triggers --events --hex-blob --no-tablespaces --databases $base > $dentro")
        if (-not $r.Ok) { throw "No se pudo volcar $base. Revisa que MYSQL_ROOT_PASSWORD sea la clave con que se creo el volumen de antes." }
        $local = Join-Path $carpeta "$base.sql"
        if (-not (Correr '' @('cp', "${dePaso}:$dentro", $local)).Ok) { throw "No se pudo copiar el volcado de $base." }
        if (-not (Correr '' @('cp', $local, "$($b.Contenedor):$dentro")).Ok) { throw "No se pudo llevar el volcado de $base a $($b.Contenedor)." }
        #  Con -Forzar, la base se vacia antes: el volcado no borra las tablas que no trae.
        $vaciar = if ($Forzar) { "mysql -uroot -e 'DROP DATABASE IF EXISTS $base' && " } else { '' }
        $r = Correr $b.ClaveNueva @('exec', '-e', 'MYSQL_PWD', $b.Contenedor, 'sh', '-c', "$vaciar mysql -uroot < $dentro; codigo=`$?; rm -f $dentro; exit `$codigo")
        if (-not $r.Ok) { throw "No se pudo cargar $base en $($b.Contenedor)." }
        Write-Host "$base`: copiada a $($b.Contenedor)."
    }

    # ---- 4. Comparar ----------------------------------------------------------
    $iguales = $true
    foreach ($b in $bases) {
        $antes = @(Filas $dePaso $claveVieja $b.Base | Sort-Object)
        $ahora = @(Filas $b.Contenedor $b.ClaveNueva $b.Base | Sort-Object)
        $filas = 0
        foreach ($tabla in $ahora) { $filas += [int]($tabla -split '=')[1] }
        if (($antes -join ';') -ne ($ahora -join ';')) {
            $iguales = $false
            Write-Host "$($b.Base): NO calza con la de antes." -ForegroundColor Red
            Write-Host "   antes: $($antes -join ', ')"
            Write-Host "   ahora: $($ahora -join ', ')"
        } else {
            Write-Host "$($b.Base): $($ahora.Count) tablas y $filas filas, igual que antes." -ForegroundColor Green
        }
    }
    if (-not $iguales) { throw 'Alguna base no quedo igual. El volumen de antes sigue intacto; revisa antes de levantar los servicios.' }
} finally {
    # ---- 5. Sin el contenedor de paso ---------------------------------------
    Correr '' @('rm', '-f', $dePaso) | Out-Null
}

Write-Host ''
Write-Host 'Listo. Ahora levanta todo:'
Write-Host '   docker compose --profile app up -d --build --wait --remove-orphans'
Write-Host ''
Write-Host "El volumen de antes ($Volumen) quedo intacto. --remove-orphans borra el contenedor tbridge-db, no sus datos."
Write-Host 'Cuando compruebes que todo esta bien, el volumen se borra con:'
Write-Host "   docker volume rm $Volumen"
Write-Host "Los volcados quedaron en $carpeta. Traen datos de personas: borralos cuando ya no los necesites."
exit 0
