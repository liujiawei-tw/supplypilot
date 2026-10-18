[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$envFile = Join-Path $repositoryRoot '.env.demo'
$composeBase = Join-Path $repositoryRoot 'compose.yaml'
$composeDemo = Join-Path $repositoryRoot 'compose.demo.yaml'
$project = 'supplypilot-sp903-local-demo'

function Read-DemoEnvFile {
    param([Parameter(Mandatory = $true)][string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw 'Repository root .env.demo is missing.' }
    $values = @{}
    foreach ($line in Get-Content -LiteralPath $Path) {
        if ($line -match '^\s*(?:#|$)') { continue }
        if ($line -notmatch '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=') { throw 'Malformed assignment in .env.demo; no value was displayed.' }
        $name = $Matches[1]
        if ($values.ContainsKey($name)) { throw "Duplicate variable name in .env.demo: $name" }
        $value = $line.Substring($line.IndexOf('=') + 1).Trim()
        if ($value.StartsWith('"') -or $value.StartsWith("'")) {
            if ($value.Length -lt 2 -or $value[$value.Length - 1] -ne $value[0]) {
                throw 'Malformed quoted value in .env.demo; no value was displayed.'
            }
            $value = $value.Substring(1, $value.Length - 2)
        } elseif ($value.EndsWith('"') -or $value.EndsWith("'")) {
            throw 'Malformed quoted value in .env.demo; no value was displayed.'
        }
        $values[$name] = $value
    }
    return $values
}

function Assert-Required {
    param([hashtable]$Values, [string]$Name)
    if (-not $Values.ContainsKey($Name) -or [string]::IsNullOrWhiteSpace([string]$Values[$Name])) {
        throw "Required .env.demo variable is missing or empty: $Name"
    }
}

function Invoke-Compose {
    param([string[]]$Arguments)
    & docker @composeArgs @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Docker Compose failed during: $($Arguments[0])" }
}

function Assert-VolumeOwnership {
    param([string]$VolumeName, [string[]]$AllowedVolumeKeys)
    $json = & docker volume inspect $VolumeName 2>$null
    if ($LASTEXITCODE -ne 0) { return } # A missing volume is created by Compose after these checks.
    $volume = @($json | ConvertFrom-Json)[0]
    $labels = $volume.Labels
    $owner = $labels.'com.docker.compose.project'
    $volumeKey = $labels.'com.docker.compose.volume'
    if ($owner -ne $project -or $volumeKey -notin $AllowedVolumeKeys) {
        throw "Existing volume '$VolumeName' has unknown ownership; refusing to attach it. No data was changed."
    }
}

function Assert-ProjectContainers {
    $ids = @(& docker ps -aq --filter "label=com.docker.compose.project=$project")
    if ($LASTEXITCODE -ne 0) { throw 'Could not inspect containers for the Demo Compose project.' }
    foreach ($id in $ids) {
        if ([string]::IsNullOrWhiteSpace([string]$id)) { continue }
        $json = & docker inspect $id
        if ($LASTEXITCODE -ne 0) { throw 'Could not inspect a container assigned to the Demo Compose project.' }
        $container = @($json | ConvertFrom-Json)[0]
        if ($container.Config.Labels.'com.docker.compose.service' -notin @('mysql', 'pgvector', 'backend', 'frontend')) {
            throw 'An unknown container is assigned to the Demo Compose project; refusing to continue.'
        }
    }
}

$values = Read-DemoEnvFile -Path $envFile
foreach ($name in @('DB_USERNAME', 'DB_PASSWORD', 'MYSQL_ROOT_PASSWORD', 'SEMANTIC_DB_USERNAME',
        'SEMANTIC_DB_PASSWORD', 'JWT_SECRET', 'DEMO_ACCOUNT_PASSWORD')) { Assert-Required -Values $values -Name $name }
if ($values.DEMO_ACCOUNT_PASSWORD.Length -lt 6 -or $values.DEMO_ACCOUNT_PASSWORD.Length -gt 100) {
    throw 'DEMO_ACCOUNT_PASSWORD in .env.demo must contain 6 to 100 characters.'
}
try { $jwtBytes = [Convert]::FromBase64String([string]$values.JWT_SECRET) }
catch { throw 'JWT_SECRET in .env.demo must be Base64 encoded. Secret values were not displayed.' }
if ($jwtBytes.Length -lt 32) {
    [Array]::Clear($jwtBytes, 0, $jwtBytes.Length)
    throw 'JWT_SECRET in .env.demo must decode to at least 32 bytes. Secret values were not displayed.'
}
[Array]::Clear($jwtBytes, 0, $jwtBytes.Length)

foreach ($path in @('.gitignore', '.dockerignore')) {
    if (-not (Select-String -LiteralPath (Join-Path $repositoryRoot $path) -Pattern '^\.env\.\*$' -Quiet)) {
        throw "$path does not exclude environment files; refusing to start or build."
    }
}
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw 'Docker CLI is not available.' }
& docker info --format '{{.ServerVersion}}' *> $null
if ($LASTEXITCODE -ne 0) { throw 'Docker Engine is unavailable; no SP-903 resources were changed.' }

Assert-VolumeOwnership -VolumeName 'supplypilot-sp903-local-demo-mysql' -AllowedVolumeKeys @('sp903-mysql-data', 'demo-mysql-data')
Assert-VolumeOwnership -VolumeName 'supplypilot-sp903-local-demo-pgvector' -AllowedVolumeKeys @('sp903-pgvector-data', 'demo-pgvector-data')
Assert-VolumeOwnership -VolumeName 'supplypilot-sp903-local-demo-documents' -AllowedVolumeKeys @('sp903-document-storage', 'demo-document-storage')
Assert-ProjectContainers

$composeArgs = @('compose', '--project-name', $project, '--env-file', $envFile, '-f', $composeBase, '-f', $composeDemo)
$interpolationNames = @('DB_USERNAME', 'DB_PASSWORD', 'MYSQL_ROOT_PASSWORD', 'SEMANTIC_DB_USERNAME',
    'SEMANTIC_DB_PASSWORD', 'JWT_SECRET', 'DEMO_ACCOUNT_PASSWORD', 'DEMO_FRONTEND_PORT',
    'GEMINI_API_KEY', 'AI_API_KEY', 'AI_CHAT_PROVIDER', 'AI_EMBEDDING_PROVIDER', 'AI_MODEL',
    'GEMINI_MODEL', 'GEMINI_EMBEDDING_MODEL', 'SEMANTIC_DIMENSIONS', 'SEMANTIC_GENERATION_KEY',
    'SEMANTIC_READ_ENABLED', 'JWT_EXPIRATION_SECONDS', 'AI_PROVIDER_TIMEOUT', 'DEMO_DATASET_VERSION')
$originalEnvironment = @{}
foreach ($name in $interpolationNames) { $originalEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }

Push-Location $repositoryRoot
try {
    # Compose gives process variables priority over --env-file. Clear collisions so this script uses .env.demo.
    foreach ($name in $interpolationNames) { Remove-Item -LiteralPath ("Env:" + $name) -ErrorAction SilentlyContinue }
    Invoke-Compose -Arguments @('config', '--quiet')

    # Start only the databases. Application credentials are checked before a Backend can run Flyway or seed data.
    Invoke-Compose -Arguments @('up', '--detach', '--wait', '--wait-timeout', '180', 'mysql', 'pgvector')
    try {
        $env:MYSQL_PWD = [string]$values.DB_PASSWORD
        $mysqlResult = & docker @composeArgs exec -T -e MYSQL_PWD mysql mysql --host=127.0.0.1 `
            --user=$([string]$values.DB_USERNAME) --database=supplypilot_demo --batch --skip-column-names --execute 'SELECT 1' 2>$null
        if ($LASTEXITCODE -ne 0 -or ($mysqlResult -join '').Trim() -ne '1') {
            throw 'MySQL application credentials do not match the existing Demo database; Backend was not started.'
        }
        $env:PGPASSWORD = [string]$values.SEMANTIC_DB_PASSWORD
        $pgResult = & docker @composeArgs exec -T -e PGPASSWORD pgvector psql --host=127.0.0.1 `
            --username=$([string]$values.SEMANTIC_DB_USERNAME) --dbname=supplypilot_semantic_demo `
            --tuples-only --no-align --command 'SELECT 1' 2>$null
        if ($LASTEXITCODE -ne 0 -or ($pgResult -join '').Trim() -ne '1') {
            throw 'PostgreSQL application credentials do not match the existing Demo database; Backend was not started.'
        }
    } finally {
        Remove-Item Env:MYSQL_PWD, Env:PGPASSWORD -ErrorAction SilentlyContinue
    }

    Invoke-Compose -Arguments @('up', '--detach', '--build', '--wait', '--wait-timeout', '300', 'backend', 'frontend')
    $serviceStatus = & docker @composeArgs ps --format json
    if ($LASTEXITCODE -ne 0) { throw 'Could not verify SP-903 service health.' }
    $statuses = @($serviceStatus | ForEach-Object { $_ | ConvertFrom-Json })
    foreach ($service in @('mysql', 'pgvector', 'backend', 'frontend')) {
        $item = $statuses | Where-Object { $_.Service -eq $service } | Select-Object -First 1
        if (-not $item -or $item.State -ne 'running' -or $item.Health -ne 'healthy') {
            throw "SP-903 service health verification failed for service '$service'. No cleanup was attempted."
        }
    }
    Write-Output 'SP-903 Demo is running; all four services are healthy.'
    Write-Output 'Frontend: http://127.0.0.1:8081'
} finally {
    foreach ($name in $interpolationNames) {
        if ($null -eq $originalEnvironment[$name]) { Remove-Item -LiteralPath ("Env:" + $name) -ErrorAction SilentlyContinue }
        else { [Environment]::SetEnvironmentVariable($name, $originalEnvironment[$name], 'Process') }
    }
    Pop-Location
}
