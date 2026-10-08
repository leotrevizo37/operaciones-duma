[CmdletBinding()]
param(
    [string]$PortsFile = 'config\ports.local.ps1',
    [string]$RuntimeFile = 'config\runtime.local.ps1',
    [PSCredential]$SystemLogCredential
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'runtime-common.ps1')
Import-DumaConfiguration -PortsFile $PortsFile -RuntimeFile $RuntimeFile

$username = $env:DUMA_SYSTEMLOG_MSSQL_USERNAME
if ([string]::IsNullOrWhiteSpace($username)) {
    throw 'DUMA_SYSTEMLOG_MSSQL_USERNAME es obligatorio.'
}
$credential = Get-DumaCredential -Credential $SystemLogCredential -Username $username -Message 'Credencial SQL Server para inicializar DumaSystemLogs'
$hostName = $env:DUMA_SYSTEMLOG_MSSQL_HOST
$port = $env:DUMA_SYSTEMLOG_MSSQL_PORT
$database = $env:DUMA_SYSTEMLOG_MSSQL_DATABASE
if ([string]::IsNullOrWhiteSpace($hostName) -or [string]::IsNullOrWhiteSpace($port) -or [string]::IsNullOrWhiteSpace($database)) {
    throw 'Host, puerto y base de system logs son obligatorios.'
}
if ($database -notmatch '^[A-Za-z0-9_-]+$') {
    throw 'DUMA_SYSTEMLOG_MSSQL_DATABASE contiene caracteres no permitidos.'
}
$sqlcmd = (Get-Command sqlcmd -ErrorAction Stop).Source
$runtimeDirectory = Join-Path $script:DumaRepositoryRoot '.runtime\logs'
New-Item -ItemType Directory -Path $runtimeDirectory -Force | Out-Null
$logFile = Join-Path $runtimeDirectory "$(Get-Date -Format 'yyyyMMdd-HHmmss')-database-init.log"
$server = "$hostName,$port"
$previousPassword = $env:SQLCMDPASSWORD
$env:SQLCMDPASSWORD = $credential.GetNetworkCredential().Password

function Invoke-SqlcmdChecked {
    param([Parameter(Mandatory)][string[]]$Arguments)

    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $sqlcmd @Arguments 2>&1 | ForEach-Object {
            $_ | Write-Output
            Add-Content -LiteralPath $logFile -Value $_.ToString() -Encoding utf8
        }
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousPreference
    }
    if ($exitCode -ne 0) {
        throw "sqlcmd termino con codigo $exitCode. Revise $logFile."
    }
}

try {
    Invoke-SqlcmdChecked -Arguments @('-S', $server, '-U', $credential.UserName, '-d', 'master', '-b', '-C', '-Q', "IF DB_ID(N'$database') IS NULL CREATE DATABASE [$database]")
    $scripts = @(
        'experiencia-digital\db\init\01-system-log.sql'
        'experiencia-digital\db\init\02-system-log-procedure.sql'
        'lecturas\db\init\01-system-log.sql'
        'lecturas\db\init\02-system-log-procedure.sql'
        'smartaudits\db\init\01-system-log.sql'
        'smartaudits\db\init\02-system-log-procedure.sql'
    )
    foreach ($relativePath in $scripts) {
        $path = Join-Path $script:DumaRepositoryRoot $relativePath
        Write-Output "Aplicando $relativePath"
        Invoke-SqlcmdChecked -Arguments @('-S', $server, '-U', $credential.UserName, '-d', $database, '-b', '-C', '-i', $path)
    }
    Write-Output "Inicializacion completada. Evidencia: $logFile"
} finally {
    if ($null -eq $previousPassword) {
        Remove-Item Env:SQLCMDPASSWORD -ErrorAction SilentlyContinue
    } else {
        $env:SQLCMDPASSWORD = $previousPassword
    }
}
