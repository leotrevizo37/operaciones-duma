Set-StrictMode -Version Latest

$script:DumaRepositoryRoot = Split-Path -Parent $PSScriptRoot

function Resolve-DumaPath {
    param([Parameter(Mandatory)][string]$Path)

    if ([IO.Path]::IsPathRooted($Path)) {
        return $Path
    }
    return Join-Path $script:DumaRepositoryRoot $Path
}

function Import-DumaConfiguration {
    param(
        [string]$PortsFile = 'config\ports.local.ps1',
        [string]$RuntimeFile = 'config\runtime.local.ps1'
    )

    $portsPath = Resolve-DumaPath $PortsFile
    $runtimePath = Resolve-DumaPath $RuntimeFile
    if (-not (Test-Path -LiteralPath $portsPath)) {
        throw "Falta $portsPath. Copie config\ports.example.ps1 a config\ports.local.ps1."
    }
    if (-not (Test-Path -LiteralPath $runtimePath)) {
        throw "Falta $runtimePath. Copie config\runtime.example.ps1 a config\runtime.local.ps1."
    }
    . $portsPath
    . $runtimePath
}

function Get-DumaPortDefinitions {
    $definitions = @(
        [pscustomobject]@{ Name = 'experiencia-digital-backend'; Variable = 'DUMA_EXPERIENCE_BACKEND_PORT'; Default = 8081; Kind = 'backend' }
        [pscustomobject]@{ Name = 'lecturas-backend'; Variable = 'DUMA_READINGS_BACKEND_PORT'; Default = 8082; Kind = 'backend' }
        [pscustomobject]@{ Name = 'smartaudits-backend'; Variable = 'DUMA_SMARTAUDITS_BACKEND_PORT'; Default = 8084; Kind = 'backend' }
    )
    foreach ($definition in $definitions) {
        $value = [Environment]::GetEnvironmentVariable($definition.Variable, 'Process')
        if ([string]::IsNullOrWhiteSpace($value)) {
            $value = [string]$definition.Default
            [Environment]::SetEnvironmentVariable($definition.Variable, $value, 'Process')
        }
        $port = 0
        if (-not [int]::TryParse($value, [ref]$port) -or $port -lt 1 -or $port -gt 65535) {
            throw "$($definition.Variable) debe estar entre 1 y 65535."
        }
        $definition | Add-Member -NotePropertyName Port -NotePropertyValue $port
    }
    $duplicates = $definitions | Group-Object Port | Where-Object Count -gt 1
    if ($duplicates) {
        throw "Los tres puertos deben ser unicos: $($duplicates.Name -join ', ')."
    }
    return $definitions
}

function Assert-DumaPortsFree {
    param([Parameter(Mandatory)][object[]]$Definitions)

    $ports = $Definitions.Port
    $occupied = @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object LocalPort -in $ports)
    if ($occupied) {
        $description = $occupied | Sort-Object LocalPort | ForEach-Object { "$($_.LocalPort) PID=$($_.OwningProcess)" }
        throw "Puertos ocupados: $($description -join '; '). Edite config\ports.local.ps1."
    }
}

function Get-DumaCredential {
    param(
        [PSCredential]$Credential,
        [Parameter(Mandatory)][string]$Username,
        [Parameter(Mandatory)][string]$Message
    )

    if ($Credential) {
        return $Credential
    }
    return Get-Credential -UserName $Username -Message $Message
}

function Invoke-DumaWebWait {
    param(
        [Parameter(Mandatory)][string]$Url,
        [int]$TimeoutSeconds = 120
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        try {
            $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 3
            if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 400) {
                return
            }
        } catch {
        }
        Start-Sleep -Milliseconds 750
    } while ((Get-Date) -lt $deadline)
    throw "Timeout esperando $Url."
}
