[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$envFile = Join-Path $repositoryRoot '.env.demo'
$composeBase = Join-Path $repositoryRoot 'compose.yaml'
$composeDemo = Join-Path $repositoryRoot 'compose.demo.yaml'
$verifierServerDirectory = Join-Path $repositoryRoot '.sp903-demo-verifier-server'
$verifierClientDirectory = Join-Path $repositoryRoot '.sp903-demo-verifier-client'
$verifierScript = Join-Path $PSScriptRoot 'sp903-demo-host-verifier.py'
$startupModule = Join-Path $PSScriptRoot 'Sp903DemoStartup.psm1'
$project = 'supplypilot-sp903-local-demo'
Import-Module -Name $startupModule -Force

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
        } else {
            $value = [regex]::Split($value, '\s+#', 2)[0].TrimEnd()
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

function Get-DemoVolumeMetadata {
    $expected = @{
        'supplypilot-sp903-local-demo-mysql' = @('sp903-mysql-data')
        'supplypilot-sp903-local-demo-pgvector' = @('sp903-pgvector-data')
        'supplypilot-sp903-local-demo-documents' = @('sp903-document-storage')
    }
    $listed = @(& docker volume ls -q)
    if ($LASTEXITCODE -ne 0) { throw 'Could not list Docker volumes; refusing to start.' }
    $labelsByName = @{}
    foreach ($name in $expected.Keys) {
        if (-not (@($listed | Where-Object { [string]::Equals([string]$_, [string]$name, [StringComparison]::Ordinal) }).Count)) { continue }
        $json = & docker volume inspect $name
        if ($LASTEXITCODE -ne 0) { throw "Could not inspect expected Demo volume '$name'." }
        $volume = @($json | ConvertFrom-Json)[0]
        if (-not [string]::Equals([string]$volume.Name, [string]$name, [StringComparison]::Ordinal)) {
            throw 'Docker returned a case-mismatched volume identity; refusing to continue.'
        }
        $labels = $volume.Labels
        $labelsByName[$name] = @{
            Project = $labels.'com.docker.compose.project'
            VolumeKey = $labels.'com.docker.compose.volume'
        }
    }
    $state = Get-DemoVolumeSetStatus -ExistingVolumeNames $listed -VolumeLabelsByName $labelsByName `
        -ExpectedProject $project -ExpectedVolumes $expected
    return @{ State = $state; Names = $expected.Keys }
}

function Assert-ContainerMountOwnership {
    param([string]$ContainerId, [string]$ExpectedService, [switch]$AllowKnownLegacyDocumentMount)
    $json = & docker inspect $ContainerId
    if ($LASTEXITCODE -ne 0) { throw 'Could not inspect a container using a Demo volume.' }
    $container = @($json | ConvertFrom-Json)[0]
    $volumeMetadata = @{}
    foreach ($mount in @($container.Mounts | Where-Object { $_.Type -eq 'volume' })) {
        $volumeJson = & docker volume inspect $mount.Name
        if ($LASTEXITCODE -ne 0) { throw 'Could not verify a mounted named volume; refusing to continue.' }
        $volume = @($volumeJson | ConvertFrom-Json)[0]
        $volumeMetadata[[string]$mount.Name] = @{
            Project = $volume.Labels.'com.docker.compose.project'
            VolumeKey = $volume.Labels.'com.docker.compose.volume'
            Mountpoint = $volume.Mountpoint
        }
    }
    $contract = Test-DemoContainerMountContract -Container $container -ExpectedProject $project `
        -ExpectedService $ExpectedService -RepositoryRoot $repositoryRoot `
        -VolumeMetadataByName $volumeMetadata -AllowKnownLegacyDocumentMount:$AllowKnownLegacyDocumentMount
    if (-not $contract.Valid) {
        throw "Demo service '$ExpectedService' failed mount ownership verification: $($contract.Error)"
    }
    if ($contract.Legacy) {
        $script:LegacyDocumentMountFound = $true
        Write-Warning 'Known legacy Demo document mount found. The controlled migration will recreate only the Backend and preserve the detached volume.'
    }
}

function Assert-DemoVolumeConsumers {
    param([switch]$AllowKnownLegacyDocumentMount)
    $expectedConsumers = @{
        'supplypilot-sp903-local-demo-mysql' = 'mysql'
        'supplypilot-sp903-local-demo-pgvector' = 'pgvector'
        'supplypilot-sp903-local-demo-documents' = 'backend'
    }
    foreach ($entry in $expectedConsumers.GetEnumerator()) {
        $ids = @(& docker ps -aq --filter "volume=$($entry.Key)")
        if ($LASTEXITCODE -ne 0) { throw 'Could not inspect containers attached to Demo volumes.' }
        foreach ($id in $ids) {
            if ([string]::IsNullOrWhiteSpace([string]$id)) { continue }
            Assert-ContainerMountOwnership -ContainerId $id -ExpectedService $entry.Value `
                -AllowKnownLegacyDocumentMount:$AllowKnownLegacyDocumentMount
        }
    }
}

function Assert-ProjectContainers {
    param([switch]$AllowKnownLegacyDocumentMount)
    $ids = @(& docker ps -aq --filter "label=com.docker.compose.project=$project")
    if ($LASTEXITCODE -ne 0) { throw 'Could not inspect containers for the Demo Compose project.' }
    foreach ($id in $ids) {
        if ([string]::IsNullOrWhiteSpace([string]$id)) { continue }
        $json = & docker inspect $id
        if ($LASTEXITCODE -ne 0) { throw 'Could not inspect a container assigned to the Demo Compose project.' }
        $container = @($json | ConvertFrom-Json)[0]
        $service = $container.Config.Labels.'com.docker.compose.service'
        if ($service -notin @('mysql', 'pgvector', 'backend', 'frontend')) {
            throw 'An unknown container is assigned to the Demo Compose project; refusing to continue.'
        }
        Assert-ContainerMountOwnership -ContainerId $id -ExpectedService $service `
            -AllowKnownLegacyDocumentMount:$AllowKnownLegacyDocumentMount
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

$pythonCommand = Get-Command python -ErrorAction SilentlyContinue
if (-not $pythonCommand) { $pythonCommand = Get-Command python3 -ErrorAction SilentlyContinue }
if (-not $pythonCommand) { throw 'Python 3 is required for the Demo Host Verifier.' }
$volumeState = Get-DemoVolumeMetadata
$script:LegacyDocumentMountFound = $false
if ($volumeState.State -eq 'Existing') {
    Assert-DemoVolumeConsumers -AllowKnownLegacyDocumentMount
}
Assert-ProjectContainers -AllowKnownLegacyDocumentMount

$composeArgs = @('compose', '--project-name', $project, '--env-file', $envFile, '-f', $composeBase, '-f', $composeDemo)
$interpolationNames = Get-ComposeEnvironmentVariableNames -ComposeFiles @($composeBase, $composeDemo)

Invoke-WithProcessEnvironmentCleared -Names $interpolationNames -Action {
    Push-Location $repositoryRoot
    try {
    $opensslCommand = Get-Command openssl -ErrorAction SilentlyContinue
    if (-not $opensslCommand) { throw 'OpenSSL is required to provision mutual TLS for the Demo Host Verifier.' }
    New-Item -ItemType Directory -Path $verifierServerDirectory -Force | Out-Null
    New-Item -ItemType Directory -Path $verifierClientDirectory -Force | Out-Null
    $caKey = Join-Path $verifierServerDirectory 'ca.key'
    $caCert = Join-Path $verifierServerDirectory 'ca.crt'
    $serverKey = Join-Path $verifierServerDirectory 'server.key'
    $serverCsr = Join-Path $verifierServerDirectory 'server.csr'
    $serverCert = Join-Path $verifierServerDirectory 'server.crt'
    $clientKey = Join-Path $verifierClientDirectory 'client.key'
    $clientCsr = Join-Path $verifierClientDirectory 'client.csr'
    $clientCert = Join-Path $verifierClientDirectory 'client.crt'
    $serverExt = Join-Path $verifierServerDirectory 'server.ext'
    $clientExt = Join-Path $verifierServerDirectory 'client.ext'
    $opensslConfigCandidates = @(
        (Join-Path (Split-Path (Split-Path $opensslCommand.Source -Parent) -Parent) 'ssl/openssl.cnf'),
        '/etc/ssl/openssl.cnf', '/etc/pki/tls/openssl.cnf')
    $opensslConfig = $opensslConfigCandidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
    $opensslConfigArgs = if ($opensslConfig) { @('-config', $opensslConfig) } else { @() }
    if (-not (Test-Path -LiteralPath $caCert)) {
        & $opensslCommand.Source req @opensslConfigArgs -x509 -newkey rsa:3072 -nodes -keyout $caKey -out $caCert -days 1825 -subj '/CN=SP903 Demo Host Verifier CA' -addext 'basicConstraints=critical,CA:TRUE' -addext 'keyUsage=critical,keyCertSign,cRLSign' 2>$null
        if ($LASTEXITCODE -ne 0) { throw 'Could not create local verifier CA.' }
    }
    if (-not (Test-Path -LiteralPath $serverCert)) {
        & $opensslCommand.Source req @opensslConfigArgs -new -newkey rsa:3072 -nodes -keyout $serverKey -out $serverCsr -subj '/CN=host.docker.internal' 2>$null
        if ($LASTEXITCODE -ne 0) { throw 'Could not create verifier server key.' }
        @('subjectAltName=DNS:host.docker.internal', 'extendedKeyUsage=serverAuth') | Set-Content -LiteralPath $serverExt -Encoding ascii
        & $opensslCommand.Source x509 -req -in $serverCsr -CA $caCert -CAkey $caKey -CAcreateserial -out $serverCert -days 825 -sha256 -extfile $serverExt 2>$null
        if ($LASTEXITCODE -ne 0) { throw 'Could not sign verifier server certificate.' }
    }
    if (-not (Test-Path -LiteralPath $clientCert)) {
        & $opensslCommand.Source req @opensslConfigArgs -new -newkey rsa:3072 -nodes -keyout $clientKey -out $clientCsr -subj '/CN=SP903 Demo Backend' 2>$null
        if ($LASTEXITCODE -ne 0) { throw 'Could not create verifier client key.' }
        @('extendedKeyUsage=clientAuth') | Set-Content -LiteralPath $clientExt -Encoding ascii
        & $opensslCommand.Source x509 -req -in $clientCsr -CA $caCert -CAkey $caKey -CAcreateserial -out $clientCert -days 825 -sha256 -extfile $clientExt 2>$null
        if ($LASTEXITCODE -ne 0) { throw 'Could not sign verifier client certificate.' }
    }
    Copy-Item -LiteralPath $caCert -Destination (Join-Path $verifierClientDirectory 'ca.crt') -Force
    if (-not $IsWindows) {
        $setfacl = Get-Command setfacl -ErrorAction SilentlyContinue
        if (-not $setfacl) { throw 'Linux Docker Engine requires setfacl (from the acl package) so only the Host user and Backend UID 10001 can read the Client key.' }
        & chmod 700 $verifierServerDirectory
        if ($LASTEXITCODE -ne 0) { throw 'Could not restrict Host Verifier private-key directory permissions.' }
        & chmod 700 $verifierClientDirectory
        if ($LASTEXITCODE -ne 0) { throw 'Could not restrict Backend Client key directory permissions.' }
        & $setfacl.Source -m 'u:10001:rx' $verifierClientDirectory
        if ($LASTEXITCODE -ne 0) { throw 'Could not grant Backend UID 10001 access to the Client certificate directory.' }
        foreach ($path in @((Join-Path $verifierClientDirectory 'client.crt'),
                (Join-Path $verifierClientDirectory 'client.key'), (Join-Path $verifierClientDirectory 'ca.crt'))) {
            & chmod 600 $path
            if ($LASTEXITCODE -ne 0) { throw 'Could not restrict Backend Client certificate permissions.' }
            & $setfacl.Source -m 'u:10001:r' $path
            if ($LASTEXITCODE -ne 0) { throw 'Could not grant Backend UID 10001 access to the Client certificate file.' }
        }
        foreach ($path in @($caKey, $serverKey)) {
            & chmod 600 $path
            if ($LASTEXITCODE -ne 0) { throw 'Could not restrict Host Verifier private key permissions.' }
        }
    }
    $serverListening = $false
    $probe = [Net.Sockets.TcpClient]::new()
    try { $probe.Connect('127.0.0.1', 18443); $serverListening = $true } catch { } finally { $probe.Dispose() }
    if (-not $serverListening) {
        $arguments = @($verifierScript, '--environment-file', $envFile, '--server-cert', $serverCert,
            '--server-key', $serverKey, '--client-ca', $caCert, '--host', '0.0.0.0', '--port', '18443')
        $startInfo = [Diagnostics.ProcessStartInfo]::new($pythonCommand.Source)
        foreach ($argument in $arguments) { [void]$startInfo.ArgumentList.Add($argument) }
        $startInfo.UseShellExecute = $false
        $startInfo.CreateNoWindow = $true
        $null = [Diagnostics.Process]::Start($startInfo)
        $ready = $false
        for ($attempt = 0; $attempt -lt 40; $attempt++) {
            try { $probe = [Net.Sockets.TcpClient]::new(); $probe.Connect('127.0.0.1', 18443); $probe.Dispose(); $ready = $true; break }
            catch { Start-Sleep -Milliseconds 100 }
        }
        if (-not $ready) { throw 'Demo Host Verifier did not start listening.' }
    }
    & $pythonCommand.Source $verifierScript --probe --client-cert $clientCert --client-key $clientKey `
        --server-ca $caCert --port 18443
    if ($LASTEXITCODE -ne 0) { throw 'Port 18443 is occupied by a service that failed mutual-TLS Host Verifier identity validation.' }
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

    if ($script:LegacyDocumentMountFound) {
        Invoke-DemoBackendMountUpgrade -GetDatabaseContainerIds {
            $mysqlId = (& docker @composeArgs ps -q mysql).Trim()
            if ($LASTEXITCODE -ne 0) { throw 'Could not read MySQL container identity.' }
            $pgvectorId = (& docker @composeArgs ps -q pgvector).Trim()
            if ($LASTEXITCODE -ne 0) { throw 'Could not read pgvector container identity.' }
            @($mysqlId, $pgvectorId)
        } -RecreateBackend {
            Invoke-Compose -Arguments @('up', '--detach', '--build', '--force-recreate', '--no-deps', '--wait',
                '--wait-timeout', '300', 'backend')
        } -AssertStrictMounts {
            Assert-ProjectContainers
        }
    }
    Invoke-Compose -Arguments @('up', '--detach', '--build', '--no-deps', '--wait', '--wait-timeout', '300', 'backend', 'frontend')
    Assert-ProjectContainers
    $serviceStatus = & docker @composeArgs ps --format json
    if ($LASTEXITCODE -ne 0) { throw 'Could not verify SP-903 service health.' }
    $statuses = @($serviceStatus | ForEach-Object { $_ | ConvertFrom-Json })
    foreach ($service in @('mysql', 'pgvector', 'backend', 'frontend')) {
        $item = $statuses | Where-Object { [string]::Equals([string]$_.Service, [string]$service, [StringComparison]::Ordinal) } | Select-Object -First 1
        if (-not $item -or $item.State -ne 'running' -or $item.Health -ne 'healthy') {
            throw "SP-903 service health verification failed for service '$service'. No cleanup was attempted."
        }
    }

    Write-Output 'SP-903 Demo is running; the mTLS Host Verifier and application services are ready.'
    Write-Output 'Frontend: http://127.0.0.1:8081'
    } finally {
        Pop-Location
    }
}
