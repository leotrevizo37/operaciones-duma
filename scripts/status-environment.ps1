[CmdletBinding()]
param([switch]$IncludeLogs)

$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$stateFile = Join-Path $repositoryRoot '.runtime\environment.json'
if (-not (Test-Path -LiteralPath $stateFile)) {
    throw 'No existe .runtime\environment.json. El entorno no fue iniciado con start-environment.ps1.'
}
$state = Get-Content -LiteralPath $stateFile -Raw | ConvertFrom-Json
foreach ($container in $state.containers) {
    $running = docker inspect -f '{{.State.Running}}' $container.name 2>$null
    [pscustomobject]@{ Component = $container.name; Type = 'backend'; Running = $running; Url = $container.url }
    if ($IncludeLogs) {
        docker logs --tail 30 $container.name 2>&1
    }
}
foreach ($process in $state.processes) {
    $running = $null -ne (Get-Process -Id $process.pid -ErrorAction SilentlyContinue)
    [pscustomobject]@{ Component = $process.name; Type = 'frontend'; Running = $running; Url = $process.url }
    if ($IncludeLogs) {
        Get-Content -LiteralPath $process.stdout -Tail 20 -ErrorAction SilentlyContinue
        Get-Content -LiteralPath $process.stderr -Tail 20 -ErrorAction SilentlyContinue
    }
}
