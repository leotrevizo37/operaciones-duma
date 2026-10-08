[CmdletBinding()]
param(
    [ValidateSet('Development', 'Artifacts')][string]$Mode = 'Development',
    [string]$PortsFile = 'config\ports.local.ps1',
    [string]$RuntimeFile = 'config\runtime.local.ps1',
    [PSCredential]$SystemLogCredential,
    [PSCredential]$WarehouseCredential,
    [switch]$SkipDatabaseInitialization
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'runtime-common.ps1')
Import-DumaConfiguration -PortsFile $PortsFile -RuntimeFile $RuntimeFile
$ports = @(Get-DumaPortDefinitions)
$requiredPorts = if ($Mode -eq 'Development') { $ports } else { @($ports | Where-Object Kind -eq 'backend') }
Assert-DumaPortsFree -Definitions $requiredPorts

$runtimeDirectory = Join-Path $script:DumaRepositoryRoot '.runtime'
$logsDirectory = Join-Path $runtimeDirectory 'logs'
$stateFile = Join-Path $runtimeDirectory 'environment.json'
New-Item -ItemType Directory -Path $logsDirectory -Force | Out-Null
if (Test-Path -LiteralPath $stateFile) {
    throw 'Ya existe .runtime\environment.json. Ejecute scripts\stop-environment.ps1 antes de iniciar otra instancia.'
}

$systemUsername = $env:DUMA_SYSTEMLOG_MSSQL_USERNAME
$warehouseUsername = $env:DUMA_WAREHOUSE_MSSQL_USERNAME
$systemCredential = Get-DumaCredential -Credential $SystemLogCredential -Username $systemUsername -Message 'Credencial SQL Server para system logs y autenticacion del shell'
if (-not $WarehouseCredential -and $env:DUMA_SYSTEMLOG_MSSQL_HOST -eq $env:DUMA_WAREHOUSE_MSSQL_HOST -and $systemUsername -eq $warehouseUsername) {
    $warehouseCredential = $systemCredential
} else {
    $warehouseCredential = Get-DumaCredential -Credential $WarehouseCredential -Username $warehouseUsername -Message 'Credencial SQL Server para los warehouses'
}

if (-not $SkipDatabaseInitialization) {
    & (Join-Path $PSScriptRoot 'initialize-databases.ps1') -PortsFile $PortsFile -RuntimeFile $RuntimeFile -SystemLogCredential $systemCredential
}

$backendDefinitions = @(
    [pscustomobject]@{ Name = 'experiencia-digital'; Container = 'duma-experiencia-digital'; PortVariable = 'DUMA_EXPERIENCE_BACKEND_PORT' }
    [pscustomobject]@{ Name = 'lecturas'; Container = 'duma-lecturas'; PortVariable = 'DUMA_READINGS_BACKEND_PORT' }
    [pscustomobject]@{ Name = 'smartaudits'; Container = 'duma-smartaudits'; PortVariable = 'DUMA_SMARTAUDITS_BACKEND_PORT' }
)

$state = [ordered]@{
    startedAt = (Get-Date).ToString('o')
    mode = $Mode
    containers = @()
    processes = @()
}
$startedContainers = [Collections.Generic.List[string]]::new()
$startedProcesses = [Collections.Generic.List[int]]::new()
$originalValues = @{}
$secretNames = @('DUMA_SYSTEMLOG_MSSQL_PASSWORD', 'DUMA_WAREHOUSE_MSSQL_PASSWORD')
$overrideNames = @('DUMA_SYSTEMLOG_MSSQL_HOST', 'DUMA_WAREHOUSE_MSSQL_HOST')
foreach ($name in $secretNames + $overrideNames) {
    $originalValues[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}

try {
    $env:DUMA_SYSTEMLOG_MSSQL_USERNAME = $systemCredential.UserName
    $env:DUMA_WAREHOUSE_MSSQL_USERNAME = $warehouseCredential.UserName
    $env:DUMA_SYSTEMLOG_MSSQL_PASSWORD = $systemCredential.GetNetworkCredential().Password
    $env:DUMA_WAREHOUSE_MSSQL_PASSWORD = $warehouseCredential.GetNetworkCredential().Password
    if ($env:DUMA_SYSTEMLOG_MSSQL_HOST -in @('localhost', '127.0.0.1')) {
        $env:DUMA_SYSTEMLOG_MSSQL_HOST = 'host.docker.internal'
    }
    if ($env:DUMA_WAREHOUSE_MSSQL_HOST -in @('localhost', '127.0.0.1')) {
        $env:DUMA_WAREHOUSE_MSSQL_HOST = 'host.docker.internal'
    }
    # El emisor y el JWKS deben declararse explícitamente para no confiar en el shell equivocado.
    if ([string]::IsNullOrWhiteSpace($env:DUMA_AUTH_ISSUER) -or [string]::IsNullOrWhiteSpace($env:DUMA_AUTH_JWKS_URI)) {
        throw 'DUMA_AUTH_ISSUER y DUMA_AUTH_JWKS_URI son obligatorias. Declarelas en config\runtime.local.ps1.'
    }
    $environmentNames = @(Get-ChildItem Env: | Where-Object Name -Like 'DUMA_*' | Select-Object -ExpandProperty Name | Sort-Object -Unique)

    foreach ($definition in $backendDefinitions) {
        $existing = docker ps -a --filter "name=^/$($definition.Container)$" --format '{{.Names}}'
        if ($existing) {
            throw "Ya existe el contenedor $($definition.Container)."
        }
        $artifactDirectory = Join-Path $script:DumaRepositoryRoot "artifacts\$($definition.Name)"
        $targetDirectory = Join-Path $script:DumaRepositoryRoot "$($definition.Name)\backend\target"
        $jar = if (Test-Path -LiteralPath $artifactDirectory) {
            Get-ChildItem -LiteralPath $artifactDirectory -Filter '*.jar' -File | Select-Object -First 1
        } else {
            Get-ChildItem -LiteralPath $targetDirectory -Filter '*.jar' -File | Where-Object Name -NotLike '*.original' | Select-Object -First 1
        }
        if (-not $jar) {
            throw "No existe JAR para $($definition.Name). Ejecute scripts\build-artifacts.ps1."
        }
        $port = [Environment]::GetEnvironmentVariable($definition.PortVariable, 'Process')
        $dockerArguments = @('run', '--detach', '--rm', '--name', $definition.Container, '--add-host', 'host.docker.internal:host-gateway', '-p', "${port}:${port}")
        foreach ($environmentName in $environmentNames) {
            $dockerArguments += @('-e', $environmentName)
        }
        $dockerArguments += @('-v', "$($jar.FullName):/app/application.jar:ro", '-w', '/app', 'eclipse-temurin:17-jre', 'java', '-jar', '/app/application.jar')
        if ($Mode -eq 'Development') {
            $dockerArguments += '--spring.profiles.active=dev'
        }
        $containerId = docker @dockerArguments
        if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($containerId)) {
            throw "No fue posible iniciar $($definition.Container)."
        }
        $startedContainers.Add($definition.Container)
        $state.containers += [ordered]@{ name = $definition.Container; application = $definition.Name; url = "http://localhost:$port"; health = "http://localhost:$port/actuator/health" }
    }
} catch {
    foreach ($containerName in $startedContainers) {
        docker stop $containerName 2>$null | Out-Null
    }
    throw
} finally {
    foreach ($name in $secretNames + $overrideNames) {
        if ($null -eq $originalValues[$name]) {
            Remove-Item "Env:$name" -ErrorAction SilentlyContinue
        } else {
            [Environment]::SetEnvironmentVariable($name, $originalValues[$name], 'Process')
        }
    }
}

try {
    $state | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $stateFile -Encoding utf8
    foreach ($container in $state.containers) {
        Invoke-DumaWebWait -Url $container.health
    }
    foreach ($process in $state.processes) {
        Invoke-DumaWebWait -Url $process.url
    }
    Write-Output "Entorno iniciado en modo $Mode."
    $state.containers | ForEach-Object { Write-Output "$($_.application): $($_.url)" }
    $state.processes | ForEach-Object { Write-Output "$($_.name): $($_.url)" }
    Write-Output 'Estado: powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\status-environment.ps1'
} catch {
    foreach ($pidValue in $startedProcesses) {
        Stop-Process -Id $pidValue -ErrorAction SilentlyContinue
    }
    foreach ($containerName in $startedContainers) {
        docker stop $containerName 2>$null | Out-Null
    }
    Remove-Item -LiteralPath $stateFile -ErrorAction SilentlyContinue
    throw
}
