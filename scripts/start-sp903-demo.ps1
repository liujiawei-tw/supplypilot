[CmdletBinding()]
param([switch]$WaitForVerifier)

$ErrorActionPreference = 'Stop'
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$envFile = Join-Path $repositoryRoot '.env.demo'
$composeBase = Join-Path $repositoryRoot 'compose.yaml'
$composeDemo = Join-Path $repositoryRoot 'compose.demo.yaml'
$verifierServerDirectory = Join-Path $repositoryRoot '.sp903-demo-verifier-server'
$verifierClientDirectory = Join-Path $repositoryRoot '.sp903-demo-verifier-client'
$verifierScript = Join-Path $PSScriptRoot 'sp903-demo-host-verifier.py'
$verifierSupervisor = Join-Path $PSScriptRoot 'sp903-demo-host-verifier-supervisor.py'
$startupModule = Join-Path $PSScriptRoot 'Sp903DemoStartup.psm1'
$project = 'supplypilot-sp903-local-demo'
$script:CreatedVerifierSupervisor = $null
$script:ManagedVerifierSupervisor = $null
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
    param([string]$ContainerId, [string]$ExpectedService, [switch]$AllowKnownLegacyDocumentMount,
        [switch]$AllowKnownLegacyCredentialMount)
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
        -VolumeMetadataByName $volumeMetadata -AllowKnownLegacyDocumentMount:$AllowKnownLegacyDocumentMount `
        -AllowKnownLegacyCredentialMount:$AllowKnownLegacyCredentialMount
    if (-not $contract.Valid) {
        throw "Demo service '$ExpectedService' failed mount ownership verification: $($contract.Error)"
    }
    if ($contract.Legacy) {
        $script:LegacyDocumentMountFound = $true
        Write-Warning 'Known legacy Demo document mount found. The controlled migration will recreate only the Backend and preserve the detached volume.'
    }
    if ($contract.LegacyCredential) {
        $script:LegacyCredentialMountFound = $true
        Write-Warning 'Known legacy Demo credential bind found. The controlled migration will recreate only the Backend; the old Host directory will be retained.'
    }
}

function Assert-DemoVolumeConsumers {
    param([switch]$AllowKnownLegacyDocumentMount, [switch]$AllowKnownLegacyCredentialMount)
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
                -AllowKnownLegacyDocumentMount:$AllowKnownLegacyDocumentMount `
                -AllowKnownLegacyCredentialMount:$AllowKnownLegacyCredentialMount
        }
    }
}

function Assert-ProjectContainers {
    param([switch]$AllowKnownLegacyDocumentMount, [switch]$AllowKnownLegacyCredentialMount)
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
            -AllowKnownLegacyDocumentMount:$AllowKnownLegacyDocumentMount `
            -AllowKnownLegacyCredentialMount:$AllowKnownLegacyCredentialMount
    }
}

function Get-DemoVerifierNetworkPolicy {
    $networkName = "${project}_verifier"
    $networkJson = & docker network inspect $networkName 2>$null
    if ($LASTEXITCODE -ne 0) {
        $networkJson = & docker network create --driver bridge `
            --label "com.docker.compose.project=$project" --label 'com.docker.compose.network=verifier' $networkName
        if ($LASTEXITCODE -ne 0) { throw 'Could not create the isolated Demo Verifier Docker network.' }
        $networkJson = & docker network inspect $networkName
        if ($LASTEXITCODE -ne 0) { throw 'Could not inspect the isolated Demo Verifier Docker network.' }
    }
    $network = @($networkJson | ConvertFrom-Json)[0]
    if (-not [string]::Equals([string]$network.Name, $networkName, [StringComparison]::Ordinal) -or
            -not [string]::Equals([string]$network.Labels.'com.docker.compose.project', $project, [StringComparison]::Ordinal) -or
            -not [string]::Equals([string]$network.Labels.'com.docker.compose.network', 'verifier', [StringComparison]::Ordinal) -or
            $network.Internal -or $network.Driver -ne 'bridge') {
        throw 'Demo Verifier network identity or isolation properties are unexpected.'
    }
    foreach ($containerId in @($network.Containers.PSObject.Properties.Name)) {
        $containerJson = & docker inspect $containerId 2>$null
        if ($LASTEXITCODE -ne 0) { throw 'Could not verify a container attached to the Demo Verifier network.' }
        $container = @($containerJson | ConvertFrom-Json)[0]
        if (-not [string]::Equals([string]$container.Config.Labels.'com.docker.compose.project', $project, [StringComparison]::Ordinal) -or
                -not [string]::Equals([string]$container.Config.Labels.'com.docker.compose.service', 'backend', [StringComparison]::Ordinal)) {
            throw 'An unknown container is attached to the Demo Verifier network; refusing to trust its source range.'
        }
    }
    $ipv4 = @($network.IPAM.Config | Where-Object { $_.Subnet -notmatch ':' })
    if ($ipv4.Count -ne 1) { throw 'Demo Verifier network must have exactly one inspectable IPv4 subnet.' }
    $sourceSubnet = [string]$ipv4[0].Subnet
    $resolveArgs = @('run', '--rm', '--network', $networkName)
    if (-not $IsWindows) { $resolveArgs += @('--add-host', 'host.docker.internal:host-gateway') }
    $resolveArgs += @('--entrypoint', 'getent', 'mysql:8.4.11', 'ahostsv4', 'host.docker.internal')
    $resolution = & docker @resolveArgs 2>$null
    if ($LASTEXITCODE -ne 0) { throw 'Could not resolve host.docker.internal from the Demo Verifier Docker network.' }
    $hostAddress = $null
    foreach ($line in $resolution) {
        if ($line -match '^\s*(?<address>(?:\d{1,3}\.){3}\d{1,3})\s+') { $hostAddress = $Matches.address; break }
    }
    if (-not $hostAddress) { throw 'Docker did not return an IPv4 Host Verifier route.' }

    if ($IsWindows) {
        $hostInterface = Get-NetIPAddress -AddressFamily IPv4 -IPAddress $hostAddress -ErrorAction SilentlyContinue |
            Select-Object -First 1
        if (-not $hostInterface) { throw 'host.docker.internal does not resolve to an address assigned to this Windows host.' }
        $adapter = Get-NetAdapter -InterfaceIndex $hostInterface.InterfaceIndex -ErrorAction SilentlyContinue
        if (-not $adapter -or $adapter.InterfaceAlias -notmatch '(?i)docker|wsl|hyper-v|container|default switch') {
            throw 'host.docker.internal did not resolve to a recognized Docker/WSL host interface.'
        }
        $connectionProfile = Get-NetConnectionProfile -InterfaceIndex $hostInterface.InterfaceIndex -ErrorAction SilentlyContinue |
            Select-Object -First 1
        if (-not $connectionProfile) { throw 'Could not determine the Windows Firewall profile for the Docker Host interface.' }
        $firewallProfileName = switch ([string]$connectionProfile.NetworkCategory) {
            'DomainAuthenticated' { 'Domain' }
            'Private' { 'Private' }
            'Public' { 'Public' }
            default { $null }
        }
        if (-not $firewallProfileName) { throw 'Docker Host interface has an unknown Windows Firewall profile.' }
        $firewallProfile = Get-NetFirewallProfile -PolicyStore ActiveStore -Name $firewallProfileName -ErrorAction SilentlyContinue
        if (-not $firewallProfile -or -not $firewallProfile.Enabled -or $firewallProfile.DefaultInboundAction -ne 'Block') {
            throw "Windows Firewall profile '$firewallProfileName' must be enabled with default-deny inbound policy."
        }
        $allowRules = @(Get-NetFirewallRule -PolicyStore ActiveStore -Direction Inbound -Action Allow -Enabled True -ErrorAction Stop)
        foreach ($rule in $allowRules) {
            $portFilter = $rule | Get-NetFirewallPortFilter
            $ports = @($portFilter.LocalPort)
            if ($ports.Count -gt 0 -and ($ports -contains '18443' -or $ports -contains 'Any' -or $ports -contains '*')) {
                $addressFilter = $rule | Get-NetFirewallAddressFilter
                $remote = @($addressFilter.RemoteAddress)
                if ($remote.Count -eq 0 -or $remote -contains 'Any' -or $remote -contains '0.0.0.0/0' -or $remote -contains '::/0') {
                    throw 'An unrestricted Windows Firewall inbound Allow rule covers Host Verifier TCP/18443; remove or narrow it manually.'
                }
            }
        }
        return @{ Host = $hostAddress; AllowedClient = $sourceSubnet; Network = $networkName;
            Interface = $adapter.InterfaceAlias; FirewallProfile = $firewallProfileName }
    }

    $bridgeJson = & docker network inspect bridge 2>$null
    if ($LASTEXITCODE -ne 0) { throw 'Could not inspect Docker host-gateway configuration.' }
    $bridge = @($bridgeJson | ConvertFrom-Json)[0]
    $bridgeConfig = @($bridge.IPAM.Config | Where-Object { $_.Subnet -notmatch ':' })
    $gateway = if ($bridgeConfig.Count -eq 1) { [string]$bridgeConfig[0].Gateway } else { $null }
    if (-not $gateway -or $hostAddress -ne $gateway) {
        throw 'Linux host-gateway resolution does not match the Docker bridge gateway; refusing an unverified listener.'
    }
    $addresses = & ip -o -4 address show 2>$null
    if ($LASTEXITCODE -ne 0 -or -not ($addresses | Select-String -SimpleMatch $gateway -Quiet)) {
        throw 'The Docker host-gateway address is not assigned to a local Linux interface.'
    }
    return @{ Host = $gateway; AllowedClient = $sourceSubnet; Network = $networkName; Interface = 'Docker bridge' }
}

function Get-VerifierSupervisorStatus {
    param([string]$PythonPath, [string]$LockPath)
    $output = & $PythonPath $verifierSupervisor --status --lock-file $LockPath 2>$null
    if ($LASTEXITCODE -ne 0 -or $output.Count -ne 1) { throw 'Could not verify Host Verifier supervisor ownership.' }
    return [string]$output[0]
}

function Assert-TrustedVerifierSupervisor {
    param([int]$ProcessId, [string]$PythonPath, [string]$LockPath, [string]$HostAddress, [string]$AllowedClient)
    $pathComparison = if ($IsWindows) { [StringComparison]::OrdinalIgnoreCase } else { [StringComparison]::Ordinal }
    if ($ProcessId -le 0) { throw 'Host Verifier lock contains an invalid process identity.' }
    if ($IsWindows) {
        $process = Get-CimInstance Win32_Process -Filter "ProcessId = $ProcessId" -ErrorAction SilentlyContinue
        if (-not $process -or -not [string]::Equals([IO.Path]::GetFullPath([string]$process.ExecutablePath),
                [IO.Path]::GetFullPath($PythonPath), $pathComparison)) {
            throw 'Host Verifier lock does not belong to the expected Python process; leaving the listener untouched.'
        }
        $commandLine = [string]$process.CommandLine
    } else {
        $commandLine = (& ps -p $ProcessId -o args= 2>$null) -join ' '
        if (-not $commandLine) { throw 'Host Verifier supervisor process is no longer running.' }
    }
    foreach ($expectedText in @($verifierSupervisor, $envFile, $LockPath,
            (Join-Path $verifierServerDirectory 'server.crt'), (Join-Path $verifierServerDirectory 'server.key'),
            (Join-Path $verifierServerDirectory 'ca.crt'), $HostAddress, $AllowedClient, '18443')) {
        if ($commandLine.IndexOf($expectedText, $pathComparison) -lt 0) {
            throw 'Host Verifier process arguments do not match the managed Demo identity; leaving the listener untouched.'
        }
    }
}

function Test-VerifierTlsIdentity {
    param([string]$PythonPath, [string]$HostAddress, [string]$CaCert, [string]$ClientCert, [string]$ClientKey)
    & $PythonPath $verifierScript --probe --client-cert $ClientCert --client-key $ClientKey `
        --server-ca $CaCert --server-name host.docker.internal --connect-host $HostAddress --port 18443
    return $LASTEXITCODE -eq 0
}

function Assert-DemoVerifierContainerPath {
    param([hashtable]$NetworkPolicy)
    $clientDirectory = $verifierClientDirectory.Replace('\', '/')
    $arguments = @('run', '--rm', '--network', $NetworkPolicy.Network)
    if (-not $IsWindows) { $arguments += @('--add-host', 'host.docker.internal:host-gateway') }
    $arguments += @('-v', "${clientDirectory}:/run/supplypilot-demo-verifier:ro", '--entrypoint', 'openssl',
        'mysql:8.4.11', 's_client', '-connect', 'host.docker.internal:18443',
        '-servername', 'host.docker.internal', '-verify_hostname', 'host.docker.internal',
        '-verify_return_error', '-CAfile', '/run/supplypilot-demo-verifier/ca.crt',
        '-cert', '/run/supplypilot-demo-verifier/client.crt', '-key', '/run/supplypilot-demo-verifier/client.key', '-quiet')
    $request = "GET /v1/health HTTP/1.1`r`nHost: host.docker.internal`r`nConnection: close`r`n`r`n"
    $response = $request | & docker @arguments 2>$null
    if ($LASTEXITCODE -ne 0 -or ($response -join "`n") -notmatch '\{"status":"ok"\}') {
        throw 'Backend-only Docker network could not verify Host Verifier TLS hostname, CA, client certificate, and route.'
    }
}

function Assert-DemoVerifierCertificates {
    param([string]$OpenSslPath, [string]$CaCert, [string]$ServerCert, [string]$ServerKey,
        [string]$ClientCert, [string]$ClientKey)
    foreach ($path in @($CaCert, $ServerCert, $ServerKey, $ClientCert, $ClientKey)) {
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw 'A required Host Verifier certificate or key is missing.' }
    }
    foreach ($certificate in @($CaCert, $ServerCert, $ClientCert)) {
        & $OpenSslPath x509 -in $certificate -noout -checkend 86400 *> $null
        if ($LASTEXITCODE -ne 0) { throw 'A Host Verifier certificate is invalid or expires within 24 hours.' }
    }
    & $OpenSslPath verify -CAfile $CaCert $CaCert *> $null
    if ($LASTEXITCODE -ne 0) { throw 'Host Verifier CA certificate is not self-consistent.' }
    & $OpenSslPath verify -CAfile $CaCert -purpose sslserver $ServerCert *> $null
    if ($LASTEXITCODE -ne 0) { throw 'Host Verifier Server certificate does not chain to the configured private CA.' }
    & $OpenSslPath verify -CAfile $CaCert -purpose sslclient $ClientCert *> $null
    if ($LASTEXITCODE -ne 0) { throw 'Host Verifier Client certificate does not chain to the configured private CA.' }
    & $OpenSslPath x509 -in $ServerCert -noout -checkhost host.docker.internal *> $null
    if ($LASTEXITCODE -ne 0) { throw 'Host Verifier Server certificate does not match host.docker.internal.' }
    $clientSubject = (& $OpenSslPath x509 -in $ClientCert -noout -subject -nameopt RFC2253 2>$null) -join ''
    if ($LASTEXITCODE -ne 0 -or $clientSubject -notmatch 'CN=SP903 Demo Backend(?:,|$)') {
        throw 'Host Verifier Client certificate has an unexpected identity.'
    }
    $pairs = @(
        @{ Certificate = $ServerCert; Key = $ServerKey },
        @{ Certificate = $ClientCert; Key = $ClientKey }
    )
    foreach ($pair in $pairs) {
        $certPublic = (& $OpenSslPath x509 -in $pair.Certificate -pubkey -noout 2>$null | & $OpenSslPath pkey -pubin -outform DER 2>$null | & $OpenSslPath dgst -sha256 2>$null) -join ''
        $keyPublic = (& $OpenSslPath pkey -in $pair.Key -pubout -outform DER 2>$null | & $OpenSslPath dgst -sha256 2>$null) -join ''
        if ($LASTEXITCODE -ne 0 -or -not $certPublic -or -not $keyPublic -or $certPublic -ne $keyPublic) {
            throw 'Host Verifier certificate and private key do not match.'
        }
    }
    if ($IsWindows) {
        foreach ($directory in @($verifierServerDirectory, $verifierClientDirectory)) {
            $acl = Get-Acl -LiteralPath $directory
            foreach ($rule in $acl.Access) {
                if ($rule.AccessControlType -ne [Security.AccessControl.AccessControlType]::Allow) { continue }
                $sid = $null
                try { $sid = $rule.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value } catch { }
                if ($sid -in @('S-1-1-0', 'S-1-5-11', 'S-1-5-32-545') -and
                        ($rule.FileSystemRights.ToString() -match 'Read|Write|Modify|FullControl')) {
                    throw 'Host Verifier key directories are readable by a broad Windows principal; restrict the NTFS ACL manually.'
                }
            }
        }
    }
}

function Start-OrReuseDemoVerifier {
    param([string]$PythonPath, [hashtable]$NetworkPolicy)
    $caCert = Join-Path $verifierServerDirectory 'ca.crt'
    $serverCert = Join-Path $verifierServerDirectory 'server.crt'
    $serverKey = Join-Path $verifierServerDirectory 'server.key'
    $clientCert = Join-Path $verifierClientDirectory 'client.crt'
    $clientKey = Join-Path $verifierClientDirectory 'client.key'
    $lockPath = Join-Path $verifierServerDirectory 'supervisor.lock'
    $logPath = Join-Path $verifierServerDirectory 'supervisor.log'
    $status = Get-VerifierSupervisorStatus -PythonPath $PythonPath -LockPath $lockPath
    if ($status -match '^running:(?<pid>\d+)$') {
        $existingPid = [int]$Matches.pid
        Assert-TrustedVerifierSupervisor -ProcessId $existingPid -PythonPath $PythonPath `
            -LockPath $lockPath -HostAddress $NetworkPolicy.Host -AllowedClient $NetworkPolicy.AllowedClient
        if (-not (Test-VerifierTlsIdentity -PythonPath $PythonPath -HostAddress $NetworkPolicy.Host `
                    -CaCert $caCert -ClientCert $clientCert -ClientKey $clientKey)) {
            throw 'Existing managed Host Verifier failed server and mutual-TLS identity verification.'
        }
        $script:ManagedVerifierSupervisor = Get-Process -Id $existingPid -ErrorAction Stop
        return
    }
    if ($status -ne 'not-running') { throw 'Host Verifier supervisor status is ambiguous; refusing to start or terminate processes.' }
    $listener = [Net.Sockets.TcpClient]::new()
    $portOpen = $false
    try { $listener.Connect($NetworkPolicy.Host, 18443); $portOpen = $true } catch { } finally { $listener.Dispose() }
    if ($portOpen) { throw 'TCP/18443 is occupied by an unmanaged process; it was not stopped.' }

    $arguments = @($verifierSupervisor, '--lock-file', $lockPath, '--environment-file', $envFile,
        '--server-cert', $serverCert, '--server-key', $serverKey, '--client-ca', $caCert,
        '--host', $NetworkPolicy.Host, '--allowed-client', $NetworkPolicy.AllowedClient,
        '--port', '18443', '--log-file', $logPath)
    $startInfo = [Diagnostics.ProcessStartInfo]::new($PythonPath)
    foreach ($argument in $arguments) { [void]$startInfo.ArgumentList.Add($argument) }
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $script:CreatedVerifierSupervisor = [Diagnostics.Process]::Start($startInfo)
    $script:ManagedVerifierSupervisor = $script:CreatedVerifierSupervisor
    $ready = $false
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        if ($script:CreatedVerifierSupervisor.HasExited) { throw 'Demo Host Verifier supervisor exited during startup.' }
        $status = Get-VerifierSupervisorStatus -PythonPath $PythonPath -LockPath $lockPath
        if ($status -match '^running:(?<pid>\d+)$') {
            Assert-TrustedVerifierSupervisor -ProcessId ([int]$Matches.pid) -PythonPath $PythonPath `
                -LockPath $lockPath -HostAddress $NetworkPolicy.Host -AllowedClient $NetworkPolicy.AllowedClient
            if (Test-VerifierTlsIdentity -PythonPath $PythonPath -HostAddress $NetworkPolicy.Host `
                    -CaCert $caCert -ClientCert $clientCert -ClientKey $clientKey) { $ready = $true; break }
        }
        Start-Sleep -Milliseconds 250
    }
    if (-not $ready) { throw 'Demo Host Verifier did not pass trusted mTLS readiness verification.' }
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
$script:LegacyCredentialMountFound = $false
if ($volumeState.State -eq 'Existing') {
    Assert-DemoVolumeConsumers -AllowKnownLegacyDocumentMount -AllowKnownLegacyCredentialMount
}
Assert-ProjectContainers -AllowKnownLegacyDocumentMount -AllowKnownLegacyCredentialMount

$composeArgs = @('compose', '--project-name', $project, '--env-file', $envFile, '-f', $composeBase, '-f', $composeDemo)
$interpolationNames = Get-ComposeEnvironmentVariableNames -ComposeFiles @($composeBase, $composeDemo)

try {
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
    Assert-DemoVerifierCertificates -OpenSslPath $opensslCommand.Source -CaCert $caCert -ServerCert $serverCert `
        -ServerKey $serverKey -ClientCert $clientCert -ClientKey $clientKey
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

    # Choose a Docker-only listener after confirming database credentials. A failure here leaves databases
    # running but never starts Backend or weakens the listener to a wildcard address.
    $networkPolicy = Get-DemoVerifierNetworkPolicy
    Start-OrReuseDemoVerifier -PythonPath $pythonCommand.Source -NetworkPolicy $networkPolicy
    Assert-DemoVerifierContainerPath -NetworkPolicy $networkPolicy

    if ($script:LegacyDocumentMountFound -or $script:LegacyCredentialMountFound) {
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
    Write-Output "Verifier listener: $($networkPolicy.Host):18443 on $($networkPolicy.Interface), allowed source $($networkPolicy.AllowedClient)"
    if ($WaitForVerifier) {
        if (-not $script:ManagedVerifierSupervisor) { throw 'Managed Host Verifier process is not available to wait on.' }
        $script:ManagedVerifierSupervisor.WaitForExit()
        if ($script:ManagedVerifierSupervisor.ExitCode -ne 0) {
            throw 'Managed Host Verifier supervisor stopped after its bounded restart policy.'
        }
    }
    } finally {
        Pop-Location
    }
}
} catch {
    if ($script:CreatedVerifierSupervisor -and -not $script:CreatedVerifierSupervisor.HasExited) {
        $script:CreatedVerifierSupervisor.Kill($true)
        $script:CreatedVerifierSupervisor.WaitForExit(10000) | Out-Null
    }
    throw
}
