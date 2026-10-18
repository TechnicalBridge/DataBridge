# El .env pertenece a esta instalacion. Los proveedores se importan solo a pedido.
param(
    [switch]$Verificar,
    [string]$ArchivoParaVerificar,
    [switch]$Ordenar,
    [string]$ArchivoParaOrdenar,
    [string]$Proveedores,
    [switch]$ReemplazarProveedores,
    [switch]$Renovar,
    [switch]$TambienCifrado,
    [string[]]$Cambiar = @(),
    [string]$Contenedor = 'tbridge-db'
)

$ErrorActionPreference = 'Stop'
$sinBom = New-Object System.Text.UTF8Encoding($false)
$archivo = if ($ArchivoParaVerificar) { $ArchivoParaVerificar } elseif ($ArchivoParaOrdenar) { $ArchivoParaOrdenar } else { Join-Path $PSScriptRoot '.env' }
$ejemplo = Join-Path $PSScriptRoot '.env.example'
$secretos = [ordered]@{
    MYSQL_ROOT_PASSWORD = '5012f5182061c46e57859cf617128c6f70eddfba4db27772bdede5a039fa7085'
    MYSQL_PASSWORD      = '068a635d2c022dfafb6c163b832327ab9bd8b944c3f44af23bf97cbc6591be1c'
    RABBIT_PASSWORD     = '84983c60f7daadc1cb8698621f802c0d9f9a3c3c295c810748fb048115c186ec'
    JWT_SECRET          = '686101b1c40874b07191bc92a9965f89fa570088218f88e30abbe768737f347f'
    INTERNAL_KEY        = 'cc9283bf0f8238f8dc57b9b97c24f62e132bc491e1b4a2b07c2840005f72b621'
    CIFRADO_LLAVE       = '774db0f5ff463139c885933a38068fec5a0648f9fc9f2605d6c786eeac406318'
    WEBHOOK_SECRET      = 'ec9ed8b11fa0ab12b53bcdca18a37def89e7c16c5d65248906469dc0972fb8f5'
}
$permitidas = @(
    'ADMIN_CORREO', 'BREVO_API_KEY', 'BREVO_URL',
    'SMTP_HOST', 'SMTP_PORT', 'SMTP_USER', 'SMTP_PASSWORD', 'SMTP_AUTH',
    'SMTP_STARTTLS', 'SMTP_SSL', 'SMTP_TIMEOUT_MS',
    'MAIL_FROM', 'MAIL_FROM_NAME', 'MAIL_REPLY_TO', 'CORREO_SOLO_A', 'CORREO_DESVIAR_A',
    'KHIPU_URL', 'KHIPU_LLAVE', 'KHIPU_URL_AVISOS', 'KHIPU_SECRETO', 'KHIPU_VENCE_EN',
    'TRANSBANK_ENVIRONMENT', 'TRANSBANK_API_URL', 'TRANSBANK_COMMERCE_CODE',
    'TRANSBANK_API_KEY', 'TRANSBANK_VENCE_EN',
    'MERCADOPAGO_URL', 'MERCADOPAGO_ACCESS_TOKEN', 'MERCADOPAGO_PUBLIC_KEY',
    'MERCADOPAGO_ENVIRONMENT', 'MERCADOPAGO_VENCE_EN',
    'BCENTRAL_USER', 'BCENTRAL_PASS',
    'XAI_API_KEY', 'XAI_BASE_URL', 'XAI_MODEL',
    'LLM_ORDEN', 'LLM_ENFRIAMIENTO_MINUTOS',
    'OLLAMA_BASE_URL', 'OLLAMA_MODEL', 'OLLAMA_TIMEOUT',
    'GROQ_API_KEY', 'GROQ_BASE_URL', 'GROQ_MODEL', 'GROQ_TIMEOUT',
    'GEMINI_API_KEY', 'GEMINI_BASE_URL', 'GEMINI_MODEL', 'GEMINI_TIMEOUT'
)

function Leer-Entorno([string]$ruta) {
    if (-not (Test-Path -LiteralPath $ruta -PathType Leaf)) { throw 'No existe uno de los archivos de configuracion indicados.' }
    $lineas = New-Object 'System.Collections.Generic.List[string]'
    $valores = [ordered]@{}
    $literales = @{}
    $numero = 0
    foreach ($linea in [System.IO.File]::ReadAllLines($ruta, $sinBom)) {
        $lineas.Add($linea)
        $numero++
        if ($linea -match '^\s*(#.*)?$') { continue }
        if ($linea -notmatch '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=(.*)$') {
            throw "Formato no admitido en la linea $numero. Use NOMBRE=valor; no se evalua codigo."
        }
        $nombre = $Matches[1]
        $literal = $Matches[2].Trim()
        if ($valores.Contains($nombre)) { throw "Variable duplicada: $nombre. No se modifico el archivo." }
        $valor = $literal
        if ($literal.StartsWith("'") -or $literal.StartsWith('"')) {
            $comilla = $literal.Substring(0, 1)
            if ($literal.Length -lt 2 -or -not $literal.EndsWith($comilla)) {
                throw "Comillas incompletas en $nombre. No se muestra su valor."
            }
            $valor = $literal.Substring(1, $literal.Length - 2)
            if ($valor.Contains($comilla) -or ($comilla -eq '"' -and $valor -match '[\\$]')) {
                throw "Escape o interpolacion no admitidos en $nombre. Use un valor literal entre comillas simples."
            }
        } elseif ($valor -match '\s+#|[\x00-\x1f$]') {
            throw "Comentario en linea o interpolacion no admitidos en $nombre. Separe el comentario o use comillas simples."
        }
        $valores[$nombre] = $valor
        $literales[$nombre] = $literal
    }
    return [pscustomobject]@{ Lineas = $lineas; Valores = $valores; Literales = $literales }
}

function Poner($entorno, [string]$nombre, [string]$literal, [string]$valor) {
    for ($i = 0; $i -lt $entorno.Lineas.Count; $i++) {
        if ($entorno.Lineas[$i] -match "^\s*$nombre\s*=") {
            $entorno.Lineas[$i] = "$nombre=$literal"
            $entorno.Valores[$nombre] = $valor
            $entorno.Literales[$nombre] = $literal
            return
        }
    }
    $entorno.Lineas.Add("$nombre=$literal")
    $entorno.Valores[$nombre] = $valor
    $entorno.Literales[$nombre] = $literal
}

function Nueva-Clave {
    $bytes = New-Object byte[] 48
    $generador = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $generador.GetBytes($bytes) } finally { $generador.Dispose() }
    return [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

function Huella([string]$valor) {
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try { return -join ($sha.ComputeHash($sinBom.GetBytes($valor)) | ForEach-Object { $_.ToString('x2') }) }
    finally { $sha.Dispose() }
}

function Ejecutar-Docker([string[]]$Argumentos, [string]$Entrada) {
    $antes = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        if ($PSBoundParameters.ContainsKey('Entrada')) { $salida = $Entrada | & docker @Argumentos 2>$null }
        else { $salida = & docker @Argumentos 2>$null }
        $codigo = $LASTEXITCODE
        return [pscustomobject]@{ Codigo = $codigo; Salida = ($salida -join "`n") }
    } finally { $ErrorActionPreference = $antes }
}

function Inspeccionar([string]$nombre) {
    $r = Ejecutar-Docker -Argumentos @('inspect', $nombre)
    if ($r.Codigo -ne 0) { return $null }
    try { return @($r.Salida | ConvertFrom-Json)[0] }
    catch { throw 'Docker devolvio una inspeccion no valida.' }
}

function Consultar-MySQL([string]$clave, [string]$usuario, [string]$sql, [switch]$Aplicacion) {
    $previa = [Environment]::GetEnvironmentVariable('MYSQL_PWD', 'Process')
    try {
        $env:MYSQL_PWD = $clave
        $argumentos = @('exec', '-i', '-e', 'MYSQL_PWD', $Contenedor, 'mysql', '--batch', '--skip-column-names', "-u$usuario")
        if ($Aplicacion) { $argumentos += @('--protocol=TCP', '-h', 'mysql') }
        return Ejecutar-Docker -Argumentos $argumentos -Entrada $sql
    } finally { [Environment]::SetEnvironmentVariable('MYSQL_PWD', $previa, 'Process') }
}

function Guardar($entorno) {
    $carpetaArchivo = Split-Path -Parent ([System.IO.Path]::GetFullPath($archivo))
    $temporal = Join-Path $carpetaArchivo ('.env.' + [guid]::NewGuid().ToString('N') + '.tmp')
    try {
        [System.IO.File]::WriteAllLines($temporal, $entorno.Lineas, $sinBom)
        if (Test-Path -LiteralPath $archivo) { [System.IO.File]::Replace($temporal, $archivo, [NullString]::Value) }
        else { [System.IO.File]::Move($temporal, $archivo) }
    } finally {
        if ([System.IO.File]::Exists($temporal)) { [System.IO.File]::Delete($temporal) }
    }
}

function Respaldar-Entorno {
    $padre = Split-Path -Parent (Split-Path -Parent ([System.IO.Path]::GetFullPath($archivo)))
    $carpeta = Join-Path (Join-Path $padre 'respaldos') ('entorno-' + (Get-Date -Format 'yyyyMMdd-HHmmssfff') + '-' + [guid]::NewGuid().ToString('N'))
    [System.IO.Directory]::CreateDirectory($carpeta) | Out-Null
    if (Test-Path -LiteralPath $archivo) { [System.IO.File]::Copy($archivo, (Join-Path $carpeta '.env')) }
    return $carpeta
}

function Ordenar-Entorno($entorno) {
    $grupos = @(
        @{ Id = 'internos'; Titulo = 'Secretos internos'; Patron = '^(JWT_SECRET|INTERNAL_KEY|WEBHOOK_SECRET|CIFRADO_LLAVE)$' },
        @{ Id = 'mysql'; Titulo = 'MySQL'; Patron = '^(MYSQL_|DB_)' },
        @{ Id = 'rabbit'; Titulo = 'RabbitMQ'; Patron = '^(RABBIT_|EVENTS_RABBIT$)' },
        @{ Id = 'direcciones'; Titulo = 'Direcciones, puertos y gateway'; Patron = '^(PUBLIC_URL$|PORTAL_|CORS_|TRUSTED_|RATE_|SWAGGER_|DOMINIO$|DEBT_URI$|AUTH_URI$|PAYMENTS_URI$|AI_URI$)' },
        @{ Id = 'acceso'; Titulo = 'Acceso y sesiones'; Patron = '^(ADMIN_|JWT_|REFRESH_|COOKIE_|CODE_|MAGIC_|INVITACION_)' },
        @{ Id = 'correo'; Titulo = 'Correo y proteccion de destinatarios'; Patron = '^(BREVO_|SMTP_|MAIL_|CORREO_)' },
        @{ Id = 'pasarelas'; Titulo = 'Pasarelas y UF'; Patron = '^(KHIPU_|MERCADOPAGO_|TRANSBANK_|BCENTRAL_)' },
        @{ Id = 'cobranza'; Titulo = 'Cartera, campanas y demo'; Patron = '^(MIN_|RECORDATORIO_|INTERES_|CONTACTO_|DEMO_|INTEGRACION_)' },
        @{ Id = 'ia'; Titulo = 'Asistente e IA'; Patron = '^(LLM_|OLLAMA_|GROQ_|GEMINI_|XAI_)' },
        @{ Id = 'otras'; Titulo = 'Otras variables conservadas'; Patron = '.' }
    )
    $bloques = New-Object 'System.Collections.Generic.List[object]'
    $pendientes = New-Object 'System.Collections.Generic.List[string]'
    $preambulo = @()
    foreach ($linea in $entorno.Lineas) {
        # Solo se retiran las cabeceras de este formateador; los comentarios propios se conservan.
        if ($linea -match '^# \[entorno:(internos|mysql|rabbit|direcciones|acceso|correo|pasarelas|cobranza|ia|otras)\] ') { continue }
        if ($linea -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=') {
            $nombre = $Matches[1]
            if ($bloques.Count -eq 0) { $preambulo = @($pendientes); $pendientes.Clear() }
            $bloques.Add([pscustomobject]@{ Nombre = $nombre; Comentarios = @($pendientes | Where-Object { $_.Trim() }); Linea = $linea })
            $pendientes.Clear()
        } else { $pendientes.Add($linea) }
    }
    $nuevas = New-Object 'System.Collections.Generic.List[string]'
    foreach ($linea in $preambulo) { $nuevas.Add($linea) }
    while ($nuevas.Count -and -not $nuevas[$nuevas.Count - 1].Trim()) { $nuevas.RemoveAt($nuevas.Count - 1) }
    $asignadas = @{}
    foreach ($grupo in $grupos) {
        $seleccion = @($bloques | Where-Object { -not $asignadas.ContainsKey($_.Nombre) -and $_.Nombre -match $grupo.Patron })
        if (-not $seleccion.Count) { continue }
        if ($nuevas.Count) { $nuevas.Add('') }
        $nuevas.Add('# [entorno:' + $grupo.Id + '] ' + $grupo.Titulo)
        foreach ($bloque in $seleccion) {
            foreach ($linea in $bloque.Comentarios) { $nuevas.Add($linea) }
            $nuevas.Add($bloque.Linea)
            $asignadas[$bloque.Nombre] = $true
        }
    }
    $notas = @($pendientes | Where-Object { $_.Trim() })
    if ($notas.Count) {
        if ($nuevas.Count) { $nuevas.Add('') }
        foreach ($linea in $notas) { $nuevas.Add($linea) }
    }
    $entorno.Lineas = $nuevas
}

function Validar-Correo($entorno) {
    $correoReal = $entorno.Valores['BREVO_API_KEY'] -or $entorno.Valores['SMTP_HOST']
    if ($correoReal -and $entorno.Valores['DEMO_DATOS'] -ne 'false' -and -not ($entorno.Valores['CORREO_SOLO_A'] -or $entorno.Valores['CORREO_DESVIAR_A'])) {
        throw 'Con datos de demo y correo real, configure CORREO_SOLO_A o CORREO_DESVIAR_A antes de continuar.'
    }
}

function Validar-BancoCentral($entorno) {
    $usuario = -not [string]::IsNullOrWhiteSpace($entorno.Valores['BCENTRAL_USER'])
    $clave = -not [string]::IsNullOrWhiteSpace($entorno.Valores['BCENTRAL_PASS'])
    if ($usuario -xor $clave) { throw 'Banco Central requiere BCENTRAL_USER y BCENTRAL_PASS juntos. No se generan credenciales del proveedor.' }
}

try {
    if ($ArchivoParaVerificar -and -not $Verificar) { throw '-ArchivoParaVerificar solo permite leer un .env existente junto con -Verificar.' }
    if ($ArchivoParaOrdenar -and -not $Ordenar) { throw '-ArchivoParaOrdenar requiere -Ordenar.' }
    if ($Ordenar -and ($Verificar -or $Proveedores -or $Renovar -or $Cambiar.Count -or $ReemplazarProveedores -or $TambienCifrado)) {
        throw '-Ordenar solo formatea un archivo existente; no genera, importa, verifica ni rota claves.'
    }
    if ($Verificar -and ($Proveedores -or $Renovar -or $Cambiar.Count -or $ReemplazarProveedores -or $TambienCifrado)) {
        throw '-Verificar es de solo lectura y no se combina con opciones que cambian el entorno.'
    }
    if ($ReemplazarProveedores -and -not $Proveedores) { throw '-ReemplazarProveedores requiere -Proveedores.' }
    foreach ($nombre in $Cambiar) {
        if (-not $secretos.Contains($nombre)) { throw 'Se pidio cambiar un secreto interno desconocido.' }
    }
    $plantilla = Leer-Entorno $ejemplo
    $existia = Test-Path -LiteralPath $archivo
    if (($Verificar -or $Ordenar) -and -not $existia) { throw 'Falta .env. Prepare primero la instalacion; verificar u ordenar no crea archivos.' }
    $entorno = if ($existia) { Leer-Entorno $archivo } else { Leer-Entorno $ejemplo }
    $original = $entorno.Lineas -join "`n"
    if ($Ordenar) {
        Ordenar-Entorno $entorno
        if (($entorno.Lineas -join "`n") -cne $original) {
            $carpeta = Respaldar-Entorno
            Guardar $entorno
            Write-Host "Archivo ordenado sin cambiar variables ni valores. Respaldo privado: $carpeta"
        } else { Write-Host 'El archivo ya esta ordenado; no se modifico.' }
        exit 0
    }
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw 'Se necesita Docker Desktop iniciado para comprobar si hay datos existentes.' }
    $motor = Ejecutar-Docker -Argumentos @('info', '--format', '{{.ServerVersion}}')
    if ($motor.Codigo -ne 0) { throw 'Docker no responde. No se generan ni cambian secretos sin comprobar la infraestructura.' }
    $base = Inspeccionar $Contenedor
    if ($null -ne $base -and $base.Config.Labels.'com.docker.compose.project' -ne 'tb_web') {
        throw 'El contenedor indicado no pertenece al proyecto tb_web. No se modifica.'
    }
    $volumenes = Ejecutar-Docker -Argumentos @('volume', 'ls', '--filter', 'label=com.docker.compose.project=tb_web', '--filter', 'label=com.docker.compose.volume=tbridge_db_data', '--format', '{{.Name}}')
    $conocidos = Ejecutar-Docker -Argumentos @('ps', '-a', '--filter', 'label=com.docker.compose.project=tb_web', '--format', '{{.Names}}')
    if ($volumenes.Codigo -ne 0 -or $conocidos.Codigo -ne 0) { throw 'No se pudo comprobar la infraestructura existente. No se modifico el entorno.' }
    $hayDatos = $null -ne $base -or -not [string]::IsNullOrWhiteSpace($volumenes.Salida) -or -not [string]::IsNullOrWhiteSpace($conocidos.Salida)
    $rotaciones = [ordered]@{}
    foreach ($nombre in $secretos.Keys) {
        $actual = [string]$entorno.Valores[$nombre]
        $forzado = $Cambiar -contains $nombre
        if ([string]::IsNullOrWhiteSpace($actual)) {
            if ($Verificar -or $hayDatos) { throw "Falta $nombre. Con infraestructura existente, recupere el .env del respaldo; no se inventa otra clave." }
            $rotaciones[$nombre] = Nueva-Clave
        } elseif (-not $Verificar -and ($forzado -or ($Renovar -and (Huella $actual) -eq $secretos[$nombre]))) {
            if ($nombre -eq 'CIFRADO_LLAVE' -and -not $TambienCifrado) { throw 'Cambiar CIFRADO_LLAVE requiere -TambienCifrado y planificar la recuperacion de los datos cifrados.' }
            if ($nombre -eq 'RABBIT_PASSWORD' -and $hayDatos) { throw 'La clave de RabbitMQ se cambia tambien en el broker. No se rota solo en .env con infraestructura existente.' }
            $rotaciones[$nombre] = Nueva-Clave
        }
    }
    if (-not $rotaciones.Contains('JWT_SECRET') -and $sinBom.GetByteCount([string]$entorno.Valores['JWT_SECRET']) -lt 32) {
        throw 'JWT_SECRET necesita al menos 32 bytes. No se cambia una clave existente automaticamente.'
    }
    if (([string]$entorno.Valores['MYSQL_USER']) -and $entorno.Valores['MYSQL_USER'] -cne 'tbridge') {
        throw 'La inicializacion de este proyecto requiere MYSQL_USER=tbridge. No se cambia un usuario existente automaticamente.'
    }
    $usuario = 'tbridge'
    foreach ($nombre in $secretos.Keys) {
        $externa = [Environment]::GetEnvironmentVariable($nombre, 'Process')
        if ($externa -and $externa -cne $entorno.Valores[$nombre]) {
            throw "La terminal sobrescribe $nombre con otro valor. Abra una terminal limpia o quite esa variable antes de continuar."
        }
    }
    $usuarioExterno = [Environment]::GetEnvironmentVariable('MYSQL_USER', 'Process')
    if ($usuarioExterno -and $usuarioExterno -cne $usuario) { throw 'La terminal sobrescribe MYSQL_USER. Quite esa variable o abra una terminal limpia.' }
    $cambiosMySQL = @($rotaciones.Keys | Where-Object { $_ -like 'MYSQL_*' })
    if ($hayDatos) {
        if ($null -eq $base -or -not $base.State.Running) { throw 'Hay datos existentes pero MySQL no esta iniciado. Inicie solo mysql; no se regenera el .env.' }
        $accesoRoot = Consultar-MySQL $entorno.Valores['MYSQL_ROOT_PASSWORD'] 'root' 'SELECT 1;'
        if ($accesoRoot.Codigo -ne 0) { throw 'MySQL rechaza MYSQL_ROOT_PASSWORD. Recupere el .env correcto; no se modifico ninguna clave.' }
        if ($cambiosMySQL -notcontains 'MYSQL_PASSWORD') {
            $acceso = Consultar-MySQL $entorno.Valores['MYSQL_PASSWORD'] $usuario 'USE tb_auth; USE tb_debt; USE tb_payments; SELECT 1;' -Aplicacion
            if ($acceso.Codigo -ne 0) { throw 'MySQL rechaza MYSQL_PASSWORD o faltan permisos/bases. No se modifico el entorno. Una rotacion requiere autorizacion y acceso root valido.' }
        }
    } elseif ($Verificar) { throw 'No hay MySQL de esta instalacion para verificar el acceso. Inicie la infraestructura despues de preparar el entorno.' }
    elseif ($cambiosMySQL.Count -and $existia -and ($Renovar -or $Cambiar.Count)) {
        throw 'No se rota una clave de MySQL sin comprobar el servidor. La preparacion inicial sin .env no necesita opciones de rotacion.'
    }

    if ($Verificar) {
        Validar-Correo $entorno
        Validar-BancoCentral $entorno
        foreach ($nombreContenedor in ($conocidos.Salida -split "`n" | Where-Object { $_ })) {
            $servicio = Inspeccionar $nombreContenedor
            if ($null -eq $servicio) { throw 'Un contenedor cambio durante la verificacion. Repita la comprobacion.' }
            $variables = @{}
            foreach ($variable in $servicio.Config.Env) {
                $par = $variable -split '=', 2
                $variables[$par[0]] = $par[1]
            }
            $rol = $servicio.Config.Labels.'com.docker.compose.service'
            if ($rol -eq 'ms-ai') {
                foreach ($nombre in $entorno.Valores.Keys) {
                    if ($nombre -match '^(LLM_|OLLAMA_|GROQ_|GEMINI_)' -and -not [string]::IsNullOrWhiteSpace($entorno.Valores[$nombre])) {
                        if (-not $variables.ContainsKey($nombre)) { throw "ms-ai no recibe $nombre. Conservarla en .env no la activa: revise el soporte de IA de la rama y Compose antes de recrear el servicio." }
                        if ($variables[$nombre] -cne $entorno.Valores[$nombre]) { throw "ms-ai conserva otro $nombre. Revise la configuracion antes de recrear el servicio." }
                    }
                }
            }
            if ($rol -eq 'mysql') {
                foreach ($nombre in @('MYSQL_ROOT_PASSWORD', 'MYSQL_PASSWORD')) {
                    if ($variables[$nombre] -cne $entorno.Valores[$nombre]) { throw "mysql conserva otro $nombre en su entorno. Recree mysql con la configuracion correcta, sin borrar volumenes." }
                }
            }
            if ($rol -in @('ms-auth', 'ms-debt', 'ms-payments')) {
                foreach ($nombre in @('JWT_SECRET', 'INTERNAL_KEY')) {
                    if ($variables[$nombre] -cne $entorno.Valores[$nombre]) { throw "$rol conserva otro $nombre. Recree los servicios despues de preparar .env." }
                }
                if ($variables['DB_PASSWORD'] -cne $entorno.Valores['MYSQL_PASSWORD']) { throw "$rol conserva otra DB_PASSWORD. Recree los servicios; no cambie la base." }
                $propia = if ($rol -eq 'ms-debt') { 'CIFRADO_LLAVE' } elseif ($rol -eq 'ms-payments') { 'WEBHOOK_SECRET' } else { $null }
                if ($propia -and $variables[$propia] -cne $entorno.Valores[$propia]) { throw "$rol conserva otra $propia. Recupere la configuracion correcta antes de recrearlo." }
                if ($rol -eq 'ms-payments') {
                    foreach ($nombre in @('BCENTRAL_USER', 'BCENTRAL_PASS')) {
                        if ($entorno.Valores[$nombre] -and $variables[$nombre] -cne $entorno.Valores[$nombre]) { throw "ms-payments no tiene el valor actual de $nombre. El archivo esta preparado, pero falta aplicar su configuracion con autorizacion." }
                    }
                }
            }
        }
        Write-Host 'Verificacion correcta: secretos presentes, acceso real a MySQL y credenciales de los servicios existentes alineados. Sin cambios.'
        exit 0
    }

    foreach ($nombre in $plantilla.Valores.Keys) {
        if (-not $entorno.Valores.Contains($nombre)) { Poner $entorno $nombre $plantilla.Literales[$nombre] $plantilla.Valores[$nombre] }
    }
    if ($Proveedores) {
        $perfil = Leer-Entorno $Proveedores
        Validar-BancoCentral $perfil
        foreach ($nombre in $perfil.Valores.Keys) {
            if ($permitidas -notcontains $nombre) { throw "El perfil de proveedores no admite $nombre. No se importan secretos internos ni parametros de infraestructura." }
            $actual = [string]$entorno.Valores[$nombre]
            $nuevo = [string]$perfil.Valores[$nombre]
            if ($actual -and $actual -cne $nuevo -and $actual -cne $plantilla.Valores[$nombre] -and -not $ReemplazarProveedores) {
                throw "$nombre ya tiene un valor propio. Revise el perfil; reemplazarlo requiere -ReemplazarProveedores."
            }
            Poner $entorno $nombre $perfil.Literales[$nombre] $nuevo
        }
    }
    Validar-Correo $entorno
    Validar-BancoCentral $entorno
    if ($rotaciones.Count -or ($entorno.Lineas -join "`n") -cne $original -or -not $existia) {
        $carpeta = Respaldar-Entorno
        # Cada rotacion SQL se persiste por separado: un fallo posterior no pierde un cambio ya aplicado.
        foreach ($nombre in $rotaciones.Keys) {
            $nueva = $rotaciones[$nombre]
            $rootActual = $entorno.Valores['MYSQL_ROOT_PASSWORD']
            Poner $entorno $nombre $nueva $nueva
            [System.IO.File]::WriteAllLines((Join-Path $carpeta '.env.propuesto'), $entorno.Lineas, $sinBom)
            if ($hayDatos -and $nombre -like 'MYSQL_*') {
                $sql = if ($nombre -eq 'MYSQL_ROOT_PASSWORD') {
                    "ALTER USER IF EXISTS 'root'@'localhost' IDENTIFIED BY '$nueva', 'root'@'%' IDENTIFIED BY '$nueva';"
                } else { "ALTER USER 'tbridge'@'%' IDENTIFIED BY '$nueva';" }
                $r = Consultar-MySQL $rootActual 'root' $sql
                if ($r.Codigo -ne 0) { throw "MySQL no acepto el cambio de $nombre. La operacion termino con error; revise el respaldo antes de reintentar." }
            }
            Guardar $entorno
            Write-Host "$nombre`: preparado sin mostrar su valor."
        }
        Guardar $entorno
        Write-Host "Archivo preparado. Respaldo privado: $carpeta"
    } else { Write-Host 'El archivo ya esta preparado; no se modifico.' }
    Write-Host 'Despues de iniciar o recrear los servicios, ejecute preparar-env.ps1 -Verificar. No se envio correo ni se iniciaron contenedores.'
    exit 0
} catch {
    Write-Host ('No se pudo preparar/verificar el entorno: ' + $_.Exception.Message) -ForegroundColor Red
    exit 1
}
