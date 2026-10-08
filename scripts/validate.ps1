[CmdletBinding()]
param(
    [string]$PortsFile
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$auditDirectory = Join-Path $repositoryRoot 'docs\auditoria-temporal\runs'
$runId = Get-Date -Format 'yyyyMMdd-HHmmss'
$auditFile = Join-Path $auditDirectory "$runId-validacion.md"
$applications = @('experiencia-digital', 'lecturas', 'smartaudits')
$portsDescription = 'defaults versionados'
$portDefaults = [ordered]@{
    DUMA_EXPERIENCE_BACKEND_PORT = 8081
    DUMA_READINGS_BACKEND_PORT = 8082
    DUMA_SMARTAUDITS_BACKEND_PORT = 8084
}

if (-not $PortsFile) {
    $defaultPortsFile = Join-Path $repositoryRoot 'config\ports.local.ps1'
    if (Test-Path -LiteralPath $defaultPortsFile) {
        $PortsFile = $defaultPortsFile
    }
}
if ($PortsFile) {
    $resolvedPortsFile = (Resolve-Path -LiteralPath $PortsFile).Path
    . $resolvedPortsFile
    $portsDescription = $resolvedPortsFile
}

$resolvedPorts = [ordered]@{}
foreach ($portName in $portDefaults.Keys) {
    $configuredValue = [Environment]::GetEnvironmentVariable($portName, 'Process')
    if ([string]::IsNullOrWhiteSpace($configuredValue)) {
        $configuredValue = [string]$portDefaults[$portName]
    }
    $configuredPort = 0
    if (-not [int]::TryParse($configuredValue, [ref]$configuredPort) -or $configuredPort -lt 1 -or $configuredPort -gt 65535) {
        throw "$portName debe ser un entero entre 1 y 65535."
    }
    $resolvedPorts[$portName] = $configuredPort
    [Environment]::SetEnvironmentVariable($portName, [string]$configuredPort, 'Process')
}

$duplicatePorts = $resolvedPorts.GetEnumerator() | Group-Object Value | Where-Object Count -gt 1
if ($duplicatePorts) {
    throw "Los tres puertos deben ser unicos: $($duplicatePorts.Name -join ', ')."
}

New-Item -ItemType Directory -Path $auditDirectory -Force | Out-Null
@(
    "# Validacion $runId"
    ''
    "- Inicio: $((Get-Date).ToString('o'))"
    '- Alcance: reactor Maven completo.'
    '- Secretos: no se imprimen ni se cargan archivos `.env`.'
    "- Puertos: $portsDescription."
    $resolvedPorts.GetEnumerator() | ForEach-Object { "- $($_.Key): $($_.Value)" }
    ''
    '```text'
) | Set-Content -LiteralPath $auditFile -Encoding utf8

function Write-AuditLine {
    param([string]$Line)

    Write-Output $Line
    Add-Content -LiteralPath $auditFile -Value $Line -Encoding utf8
}

function Invoke-ValidationStep {
    param(
        [string]$Name,
        [string]$Command,
        [scriptblock]$Action
    )

    Write-AuditLine "[$((Get-Date).ToString('o'))] START $Name"
    Write-AuditLine "COMMAND $Command"
    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $Action 2>&1 | ForEach-Object {
            Write-Output $_
            Add-Content -LiteralPath $auditFile -Value $_.ToString() -Encoding utf8
        }
        $stepExitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($stepExitCode -ne 0) {
        throw "$Name termino con codigo $stepExitCode."
    }
    Write-AuditLine "[$((Get-Date).ToString('o'))] PASS $Name"
}

try {
    Invoke-ValidationStep 'docker version' 'docker --version' { docker --version }

    $workspaceMount = "${repositoryRoot}:/workspace"
    Invoke-ValidationStep 'backend mvn verify' 'docker run --rm -v /var/run/docker.sock:/var/run/docker.sock -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal -v <repo>:/workspace -v duma-maven-cache:/root/.m2 -w /workspace maven:3.9.14-eclipse-temurin-17 mvn verify' {
        docker run --rm `
            -v /var/run/docker.sock:/var/run/docker.sock `
            -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal `
            -v $workspaceMount `
            -v duma-maven-cache:/root/.m2 `
            -w /workspace `
            maven:3.9.14-eclipse-temurin-17 `
            mvn verify
    }

    Write-AuditLine "[$((Get-Date).ToString('o'))] START backend integration evidence"
    Write-AuditLine 'COMMAND validate Failsafe XML reports'
    foreach ($application in $applications) {
        $reportsDirectory = Join-Path $repositoryRoot "$application\backend\target\failsafe-reports"
        $reports = @(Get-ChildItem -LiteralPath $reportsDirectory -Filter 'TEST-*.xml' -ErrorAction Stop)
        $tests = 0
        $skipped = 0
        foreach ($report in $reports) {
            [xml]$result = Get-Content -LiteralPath $report.FullName
            $tests += [int]$result.testsuite.tests
            $skipped += [int]$result.testsuite.skipped
        }
        if ($tests -lt 1 -or $skipped -ne 0) {
            throw "$application requiere al menos una integracion ejecutada y cero omisiones."
        }
        Write-AuditLine "$application integration tests=$tests skipped=$skipped"
    }
    Write-AuditLine "[$((Get-Date).ToString('o'))] PASS backend integration evidence"

    Write-AuditLine "[$((Get-Date).ToString('o'))] RESULT SUCCESS"
}
catch {
    Write-AuditLine "[$((Get-Date).ToString('o'))] RESULT FAILURE $($_.Exception.Message)"
    throw
}
finally {
    @(
        '```'
        ''
        "- Fin: $((Get-Date).ToString('o'))"
    ) | Add-Content -LiteralPath $auditFile -Encoding utf8
}

Write-Output "Evidencia: $auditFile"
