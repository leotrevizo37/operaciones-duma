[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$stateFile = Join-Path $repositoryRoot '.runtime\environment.json'
if (-not (Test-Path -LiteralPath $stateFile)) {
    Write-Output 'No hay estado registrado; no se detuvo ningun proceso.'
    exit 0
}
$state = Get-Content -LiteralPath $stateFile -Raw | ConvertFrom-Json
foreach ($process in $state.processes) {
    $current = Get-Process -Id $process.pid -ErrorAction SilentlyContinue
    if ($current) {
        Stop-Process -Id $process.pid
        Write-Output "Detenido $($process.name) PID=$($process.pid)"
    }
}
foreach ($container in $state.containers) {
    $exists = docker ps -a --filter "name=^/$($container.name)$" --format '{{.Names}}'
    if ($exists -eq $container.name) {
        docker stop $container.name | Out-Null
        Write-Output "Detenido $($container.name)"
    }
}
Remove-Item -LiteralPath $stateFile
Write-Output 'Entorno Duma detenido.'
