# Aprovisionamiento explicito de una pareja; nunca rota o sobrescribe archivos.
param(
    [string]$Directory = (Join-Path (Split-Path $PSScriptRoot -Parent) '.secrets/jwt'),
    [string]$OpenSsl = ''
)
$ErrorActionPreference = 'Stop'
if (Test-Path -LiteralPath $Directory) { throw 'El directorio destino ya existe; usa uno nuevo. No se sobrescribe ni rota.' }
if (-not $OpenSsl) {
    $comando = Get-Command openssl -ErrorAction SilentlyContinue
    if ($comando) { $OpenSsl = $comando.Source }
    elseif ($env:ProgramFiles -and (Test-Path -LiteralPath (Join-Path $env:ProgramFiles 'Git/usr/bin/openssl.exe'))) {
        $OpenSsl = Join-Path $env:ProgramFiles 'Git/usr/bin/openssl.exe'
    } else { throw 'Se requiere OpenSSL instalado. No se descarga automaticamente.' }
}
New-Item -ItemType Directory -Path $Directory | Out-Null
$destino = (Resolve-Path -LiteralPath $Directory).Path
if ($env:OS -eq 'Windows_NT') {
    $acl = New-Object System.Security.AccessControl.DirectorySecurity
    $acl.SetAccessRuleProtection($true, $false)
    $usuario = [System.Security.Principal.WindowsIdentity]::GetCurrent().User
    foreach ($sid in @($usuario, (New-Object System.Security.Principal.SecurityIdentifier('S-1-5-18')))) {
        $regla = New-Object System.Security.AccessControl.FileSystemAccessRule($sid, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')
        $acl.AddAccessRule($regla)
    }
    Set-Acl -LiteralPath $destino -AclObject $acl
} else {
    & chmod 700 $destino
    if ($LASTEXITCODE -ne 0) { throw 'No se pudieron restringir permisos del directorio.' }
}
$privada = Join-Path $destino 'private.pem'
$publica = Join-Path $destino 'public.pem'
& $OpenSsl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out $privada 2>$null
if ($LASTEXITCODE -ne 0) { throw 'OpenSSL no pudo generar la pareja. Revisa el directorio parcial antes de reintentar.' }
& $OpenSsl pkey -in $privada -pubout -out $publica 2>$null
if ($LASTEXITCODE -ne 0) { throw 'OpenSSL no pudo exportar la publica.' }
if ($env:OS -ne 'Windows_NT') {
    & chmod 600 $privada $publica
    if ($LASTEXITCODE -ne 0) { throw 'No se pudieron restringir permisos de las claves.' }
}
Write-Host 'Pareja P-256 creada (PKCS#8 / SPKI). No se mostro material criptografico.'
Write-Host ('Configura JWT_KEY_DIRECTORY=' + $destino.Replace('\', '/'))
Write-Host 'Docker Linux: concede lectura controlada al UID 10001 de auth; ver docs/seguridad/jwt.md.'
