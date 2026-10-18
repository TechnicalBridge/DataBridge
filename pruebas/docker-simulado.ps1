param([string]$Preparador)

$global:EstadoDocker = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'docker.json') -Raw | ConvertFrom-Json
$global:CarpetaPrueba = $PSScriptRoot

function global:docker {
    $argumentos = @($args | ForEach-Object { [string]$_ })
    $consulta = @($input) -join "`n"
    $registro = @{ argumentos = $argumentos; consulta = $consulta }
    [System.IO.File]::AppendAllText((Join-Path $global:CarpetaPrueba 'llamadas.jsonl'), (($registro | ConvertTo-Json -Compress) + "`n"))
    $global:LASTEXITCODE = 0
    switch ($argumentos[0]) {
        'info' {
            if ($global:EstadoDocker.sinMotor) { $global:LASTEXITCODE = 1 }
            else { 'simulado' }
        }
        'volume' { if ($global:EstadoDocker.volumen) { 'tb_web_tbridge_db_data' } }
        'ps' {
            if ($global:EstadoDocker.contenedor) { 'tbridge-db' }
            if ($global:EstadoDocker.servicio) { 'tb_web-ms-debt-1' }
            if ($global:EstadoDocker.ia) { 'tb_web-ms-ai-1' }
            if ($global:EstadoDocker.pagos) { 'tb_web-ms-payments-1' }
        }
        'inspect' {
            $nombre = $argumentos[-1]
            if ($nombre -eq 'tbridge-db' -and $global:EstadoDocker.contenedor) {
                if ($argumentos -contains '-f') { [string][bool]$global:EstadoDocker.iniciado; return }
                $r = @{
                    Config = @{ Labels = @{ 'com.docker.compose.project' = $global:EstadoDocker.proyecto; 'com.docker.compose.service' = 'mysql' }; Env = @(('MYSQL_ROOT_PASSWORD=' + $global:EstadoDocker.configRoot), ('MYSQL_PASSWORD=' + $global:EstadoDocker.configAplicacion)) }
                    State = @{ Running = [bool]$global:EstadoDocker.iniciado }
                }
                ConvertTo-Json -InputObject @($r) -Depth 8 -Compress
            } elseif ($nombre -eq 'tb_web-ms-debt-1' -and $global:EstadoDocker.servicio) {
                $r = @{
                    Config = @{
                        Labels = @{ 'com.docker.compose.project' = 'tb_web'; 'com.docker.compose.service' = 'ms-debt' }
                        Env = @(('DB_PASSWORD=' + $global:EstadoDocker.claveServicio), ('JWT_SECRET=' + $global:EstadoDocker.jwt), ('INTERNAL_KEY=' + $global:EstadoDocker.interna), ('CIFRADO_LLAVE=' + $global:EstadoDocker.cifrado))
                    }
                }
                ConvertTo-Json -InputObject @($r) -Depth 8 -Compress
            } elseif ($nombre -eq 'tb_web-ms-ai-1' -and $global:EstadoDocker.ia) {
                $r = @{
                    Config = @{
                        Labels = @{ 'com.docker.compose.project' = 'tb_web'; 'com.docker.compose.service' = 'ms-ai' }
                        Env = @($global:EstadoDocker.variablesIa.PSObject.Properties | ForEach-Object { $_.Name + '=' + [string]$_.Value })
                    }
                }
                ConvertTo-Json -InputObject @($r) -Depth 8 -Compress
            } elseif ($nombre -eq 'tb_web-ms-payments-1' -and $global:EstadoDocker.pagos) {
                $variables = @(('DB_PASSWORD=' + $global:EstadoDocker.configAplicacion), ('JWT_SECRET=' + $global:EstadoDocker.jwt), ('INTERNAL_KEY=' + $global:EstadoDocker.interna), ('WEBHOOK_SECRET=' + $global:EstadoDocker.webhook))
                $variables += @($global:EstadoDocker.variablesBanco.PSObject.Properties | ForEach-Object { $_.Name + '=' + [string]$_.Value })
                $r = @{
                    Config = @{
                        Labels = @{ 'com.docker.compose.project' = 'tb_web'; 'com.docker.compose.service' = 'ms-payments' }
                        Env = $variables
                    }
                }
                ConvertTo-Json -InputObject @($r) -Depth 8 -Compress
            } else { $global:LASTEXITCODE = 1 }
        }
        'exec' {
            $esRoot = $argumentos -contains '-uroot'
            $esperada = if ($esRoot) { $global:EstadoDocker.root } else { $global:EstadoDocker.aplicacion }
            if ($env:MYSQL_PWD -cne $esperada -or ($consulta -match '^ALTER' -and $global:EstadoDocker.fallarAlter)) {
                Write-Error 'Error simulado: no se debe imprimir este contenido.' -ErrorAction Continue
                $global:LASTEXITCODE = 1
                return
            }
            if ($consulta -match "IDENTIFIED BY '([^']+)'") {
                $nueva = $Matches[1]
                if ($consulta.Contains("'root'")) { $global:EstadoDocker.root = $nueva }
                else { $global:EstadoDocker.aplicacion = $nueva }
            }
            '1'
        }
        default { throw 'La prueba intento una operacion Docker no prevista.' }
    }
}

try { & $Preparador @args }
finally {
    $global:EstadoDocker | Add-Member -NotePropertyName pwdRestaurado -NotePropertyValue $env:MYSQL_PWD -Force
    [System.IO.File]::WriteAllText((Join-Path $PSScriptRoot 'docker-final.json'), ($global:EstadoDocker | ConvertTo-Json -Depth 8))
}
exit $LASTEXITCODE
