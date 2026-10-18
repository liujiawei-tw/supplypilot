$ErrorActionPreference = 'Stop'
$modulePath = Join-Path $PSScriptRoot '..\Sp903DemoStartup.psm1'
Import-Module -Name $modulePath -Force

function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw "FAIL: $Message" }
}

$composeNames = Get-ComposeEnvironmentVariableNames -ComposeFiles @(
    (Join-Path $PSScriptRoot '..\..\compose.yaml'),
    (Join-Path $PSScriptRoot '..\..\compose.demo.yaml'))
foreach ($required in @('FRONTEND_ORIGIN', 'DEMO_ENV_SOURCE', 'MYSQL_PWD', 'PGPASSWORD',
        'DB_USERNAME', 'DB_PASSWORD', 'DEMO_FRONTEND_PORT')) {
    Assert-True ($required -in $composeNames) "Process environment protection omitted $required"
}

$fixtureName = 'SP903_ENV_RESTORE_FIXTURE'
$original = [Environment]::GetEnvironmentVariable($fixtureName, 'Process')
try {
    [Environment]::SetEnvironmentVariable($fixtureName, 'fixture-original', 'Process')
    Invoke-WithProcessEnvironmentCleared -Names @($fixtureName) -Action {
        Assert-True ($null -eq [Environment]::GetEnvironmentVariable($fixtureName, 'Process')) `
            'Environment value remained visible inside protected action'
        [Environment]::SetEnvironmentVariable($fixtureName, 'fixture-mutated', 'Process')
    }
    Assert-True ([Environment]::GetEnvironmentVariable($fixtureName, 'Process') -eq 'fixture-original') `
        'Original environment value was not restored after success'

    try {
        Invoke-WithProcessEnvironmentCleared -Names @($fixtureName) -Action {
            [Environment]::SetEnvironmentVariable($fixtureName, 'fixture-mutated', 'Process')
            throw 'fixture failure'
        }
        throw 'Expected protected action to throw'
    } catch {
        if ($_.Exception.Message -eq 'Expected protected action to throw') { throw }
    }
    Assert-True ([Environment]::GetEnvironmentVariable($fixtureName, 'Process') -eq 'fixture-original') `
        'Original environment value was not restored after exception'
} finally {
    [Environment]::SetEnvironmentVariable($fixtureName, $original, 'Process')
}

$expected = @{
    'supplypilot-sp903-local-demo-mysql' = @('sp903-mysql-data')
    'supplypilot-sp903-local-demo-pgvector' = @('sp903-pgvector-data')
    'supplypilot-sp903-local-demo-documents' = @('sp903-document-storage')
}
$allNames = @($expected.Keys)
$none = Get-DemoVolumeSetStatus -ExistingVolumeNames @() -VolumeLabelsByName @{} `
    -ExpectedProject 'supplypilot-sp903-local-demo' -ExpectedVolumes $expected
Assert-True ($none -eq 'FreshStart') 'No volumes should be a fresh start'

$labels = @{}
foreach ($name in $allNames) {
    $key = @($expected[$name])[0]
    $labels[$name] = @{ Project = 'supplypilot-sp903-local-demo'; VolumeKey = $key }
}
$existing = Get-DemoVolumeSetStatus -ExistingVolumeNames $allNames -VolumeLabelsByName $labels `
    -ExpectedProject 'supplypilot-sp903-local-demo' -ExpectedVolumes $expected
Assert-True ($existing -eq 'Existing') 'Compatible legacy volumes should be reusable'

foreach ($scenario in @(
    @{ Name = 'partial'; Names = @($allNames[0], $allNames[1]); Labels = $labels },
    @{ Name = 'wrong project'; Names = $allNames; Labels = (@{ $allNames[0] = @{ Project = 'other-project'; VolumeKey = 'sp903-mysql-data' }; $allNames[1] = $labels[$allNames[1]]; $allNames[2] = $labels[$allNames[2]] }) },
    @{ Name = 'unknown volume key'; Names = $allNames; Labels = (@{ $allNames[0] = @{ Project = 'supplypilot-sp903-local-demo'; VolumeKey = 'unknown-key' }; $allNames[1] = $labels[$allNames[1]]; $allNames[2] = $labels[$allNames[2]] }) },
    @{ Name = 'wrong-case project label'; Names = $allNames; Labels = (@{ $allNames[0] = @{ Project = 'SUPPLYPILOT-SP903-LOCAL-DEMO'; VolumeKey = 'sp903-mysql-data' }; $allNames[1] = $labels[$allNames[1]]; $allNames[2] = $labels[$allNames[2]] }) },
    @{ Name = 'wrong-case volume key'; Names = $allNames; Labels = (@{ $allNames[0] = @{ Project = 'supplypilot-sp903-local-demo'; VolumeKey = 'SP903-MYSQL-DATA' }; $allNames[1] = $labels[$allNames[1]]; $allNames[2] = $labels[$allNames[2]] }) }
)) {
    $thrown = $false
    try {
        Get-DemoVolumeSetStatus -ExistingVolumeNames $scenario.Names -VolumeLabelsByName $scenario.Labels `
            -ExpectedProject 'supplypilot-sp903-local-demo' -ExpectedVolumes $expected | Out-Null
    } catch { $thrown = $true }
    Assert-True $thrown "Volume safety check did not reject $($scenario.Name)"
}

function New-TestContainer([string]$Service, [object[]]$Mounts) {
    return [pscustomobject]@{
        Config = [pscustomobject]@{ Labels = @{
                'com.docker.compose.project' = 'supplypilot-sp903-local-demo'
                'com.docker.compose.service' = $Service
            } }
        Mounts = $Mounts
    }
}

function New-TestVolumeMount([string]$Name, [string]$Source, [string]$Target, [bool]$ReadWrite = $true) {
    return [pscustomobject]@{ Type = 'volume'; Name = $Name; Source = $Source; Destination = $Target; RW = $ReadWrite }
}

function New-TestBindMount([string]$Source, [string]$Target, [bool]$ReadWrite = $false) {
    return [pscustomobject]@{ Type = 'bind'; Name = $null; Source = $Source; Destination = $Target; RW = $ReadWrite }
}

$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$documentsVolume = 'supplypilot-sp903-local-demo-documents'
$documentsMountpoint = '/docker/volumes/supplypilot-sp903-local-demo-documents/_data'
$sampleSource = Join-Path $repositoryRoot 'samples\documents'
$credentialSource = Join-Path $repositoryRoot '.sp903-demo-verifier-client'
$volumeMetadata = @{
    $documentsVolume = @{ Project = 'supplypilot-sp903-local-demo'; VolumeKey = 'sp903-document-storage'; Mountpoint = $documentsMountpoint }
}
$validBackendMounts = @(
    (New-TestVolumeMount $documentsVolume $documentsMountpoint '/app/data/demo-documents'),
    (New-TestBindMount $sampleSource '/app/samples/demo-documents'),
    (New-TestBindMount $credentialSource '/run/supplypilot-demo-verifier')
)
$validBackend = New-TestContainer 'backend' $validBackendMounts
$validContract = Test-DemoContainerMountContract -Container $validBackend `
    -ExpectedProject 'supplypilot-sp903-local-demo' -ExpectedService backend `
    -RepositoryRoot $repositoryRoot -VolumeMetadataByName $volumeMetadata
Assert-True ($validContract.Valid -and -not $validContract.Legacy) 'Complete current Backend mount contract should pass strict validation'
$repeatStartContract = Test-DemoContainerMountContract -Container $validBackend `
    -ExpectedProject 'supplypilot-sp903-local-demo' -ExpectedService backend `
    -RepositoryRoot $repositoryRoot -VolumeMetadataByName $volumeMetadata -AllowKnownLegacyDocumentMount
Assert-True ($repeatStartContract.Valid -and -not $repeatStartContract.Legacy) `
    'Repeated start with current mounts must not request legacy migration'
$windowsPathA = Normalize-DemoHostPath 'C:\SupplyPilot\samples\documents\'
$windowsPathB = Normalize-DemoHostPath 'c:/supplypilot/samples/documents'
Assert-True ([string]::Equals($windowsPathA, $windowsPathB, [StringComparison]::OrdinalIgnoreCase)) `
    'Windows Docker Host paths should normalize case and separator differences'
Assert-True (Test-DemoHostPathMatch -Expected 'C:\SupplyPilot\samples\documents' `
    -Actual '/run/desktop/mnt/host/c/SupplyPilot/samples/documents') `
    'Windows Docker Desktop VM source paths should map to the exact Windows Host path'
Assert-True (-not (Test-DemoHostPathMatch -Expected 'C:\SupplyPilot\samples\documents' `
    -Actual '/run/desktop/mnt/host/c/OtherProject/samples/documents')) `
    'Windows Docker Desktop path normalization must not accept a different Host directory'
$linuxPathA = Normalize-DemoHostPath '/srv/supplypilot/samples/documents/'
$linuxPathB = Normalize-DemoHostPath '/srv/supplypilot/samples/documents'
Assert-True ([string]::Equals($linuxPathA, $linuxPathB, [StringComparison]::Ordinal)) `
    'Linux Docker Host paths should normalize trailing separators without changing case'
Assert-True (Test-DemoHostPathMatch -Expected '/srv/supplypilot/samples/documents/' `
    -Actual '/srv/supplypilot/samples/documents') 'Linux Host paths should compare by exact normalized path'
Assert-True (-not (Test-DemoHostPathMatch -Expected '/srv/supplypilot/samples/documents' `
    -Actual '/srv/SupplyPilot/samples/documents')) 'Linux Host path comparison must remain case-sensitive'

$legacyName = 'unimportant-legacy-volume-name'
$legacyMountpoint = '/docker/volumes/legacy-document-storage/_data'
$legacyVolumeMetadata = @{
    $documentsVolume = $volumeMetadata[$documentsVolume]
    $legacyName = @{ Project = 'supplypilot-sp903-local-demo'; VolumeKey = 'document-storage'; Mountpoint = $legacyMountpoint }
}
$legacyMounts = @($validBackendMounts) + @((New-TestVolumeMount $legacyName $legacyMountpoint '/app/data/documents'))
$legacyBackend = New-TestContainer 'backend' $legacyMounts
$legacyBackendFixture = $legacyBackend
$legacyContract = Test-DemoContainerMountContract -Container $legacyBackend `
    -ExpectedProject 'supplypilot-sp903-local-demo' -ExpectedService backend `
    -RepositoryRoot $repositoryRoot -VolumeMetadataByName $legacyVolumeMetadata -AllowKnownLegacyDocumentMount
Assert-True ($legacyContract.Valid -and $legacyContract.Legacy) 'Only the exact labelled old dual-volume Backend should be eligible for migration'

$negativeCases = @(
    @{ Name = 'wrong project'; Container = (New-TestContainer 'backend' $legacyMounts); Project = 'different-project'; Volumes = $legacyVolumeMetadata; AllowLegacy = $true },
    @{ Name = 'wrong service'; Container = (New-TestContainer 'frontend' $legacyMounts); Project = 'supplypilot-sp903-local-demo'; Volumes = $legacyVolumeMetadata; AllowLegacy = $true },
    @{ Name = 'wrong legacy key'; Container = $legacyBackend; Project = 'supplypilot-sp903-local-demo'; Volumes = (@{ $documentsVolume = $volumeMetadata[$documentsVolume]; $legacyName = @{ Project = 'supplypilot-sp903-local-demo'; VolumeKey = 'wrong-key'; Mountpoint = $legacyMountpoint } }); AllowLegacy = $true },
    @{ Name = 'wrong legacy project'; Container = $legacyBackend; Project = 'supplypilot-sp903-local-demo'; Volumes = (@{ $documentsVolume = $volumeMetadata[$documentsVolume]; $legacyName = @{ Project = 'foreign'; VolumeKey = 'document-storage'; Mountpoint = $legacyMountpoint } }); AllowLegacy = $true },
    @{ Name = 'wrong legacy target'; Container = (New-TestContainer 'backend' (@($validBackendMounts) + @((New-TestVolumeMount $legacyName $legacyMountpoint '/wrong/target')))); Project = 'supplypilot-sp903-local-demo'; Volumes = $legacyVolumeMetadata; AllowLegacy = $true },
    @{ Name = 'missing current document volume'; Container = (New-TestContainer 'backend' @($validBackendMounts | Where-Object { $_.Type -ne 'volume' })); Project = 'supplypilot-sp903-local-demo'; Volumes = $volumeMetadata; AllowLegacy = $true },
    @{ Name = 'unknown extra volumes'; Container = (New-TestContainer 'backend' (@($legacyMounts) + @((New-TestVolumeMount 'unknown-two' '/volume/unknown-two' '/unknown/two')))); Project = 'supplypilot-sp903-local-demo'; Volumes = $legacyVolumeMetadata; AllowLegacy = $true }
)
foreach ($scenario in $negativeCases) {
    $result = Test-DemoContainerMountContract -Container $scenario.Container `
        -ExpectedProject $scenario.Project -ExpectedService backend -RepositoryRoot $repositoryRoot `
        -VolumeMetadataByName $scenario.Volumes -AllowKnownLegacyDocumentMount:$scenario.AllowLegacy
    Assert-True (-not $result.Valid) "Mount ownership should fail closed for $($scenario.Name)"
}

foreach ($scenario in @(
    @{ Name = 'unknown bind'; Mounts = (@($validBackendMounts) + @((New-TestBindMount (Join-Path $repositoryRoot 'other') '/other'))) },
    @{ Name = 'wrong sample source'; Mounts = @((New-TestVolumeMount $documentsVolume $documentsMountpoint '/app/data/demo-documents'), (New-TestBindMount (Join-Path $repositoryRoot 'other') '/app/samples/demo-documents'), $validBackendMounts[2]) },
    @{ Name = 'wrong credential destination'; Mounts = @($validBackendMounts[0], $validBackendMounts[1], (New-TestBindMount $credentialSource '/tmp/credentials')) },
    @{ Name = 'sample writable'; Mounts = @($validBackendMounts[0], (New-TestBindMount $sampleSource '/app/samples/demo-documents' $true), $validBackendMounts[2]) },
    @{ Name = 'credentials writable'; Mounts = @($validBackendMounts[0], $validBackendMounts[1], (New-TestBindMount $credentialSource '/run/supplypilot-demo-verifier' $true)) },
    @{ Name = 'wrong document volume'; Mounts = @((New-TestVolumeMount 'lookalike-volume' $documentsMountpoint '/app/data/demo-documents'), $validBackendMounts[1], $validBackendMounts[2]) },
    @{ Name = 'document volume read only'; Mounts = @((New-TestVolumeMount $documentsVolume $documentsMountpoint '/app/data/demo-documents' $false), $validBackendMounts[1], $validBackendMounts[2]) },
    @{ Name = 'extra named volume'; Mounts = (@($validBackendMounts) + @((New-TestVolumeMount 'extra' '/volume/extra' '/extra'))) },
    @{ Name = 'missing verifier mount'; Mounts = @($validBackendMounts[0], $validBackendMounts[1]) },
    @{ Name = 'wrong-case document target'; Mounts = @((New-TestVolumeMount $documentsVolume $documentsMountpoint '/App/data/demo-documents'), $validBackendMounts[1], $validBackendMounts[2]) },
    @{ Name = 'wrong-case document volume name'; Mounts = @((New-TestVolumeMount 'Supplypilot-sp903-local-demo-documents' $documentsMountpoint '/app/data/demo-documents'), $validBackendMounts[1], $validBackendMounts[2]) },
    @{ Name = 'wrong-case sample target'; Mounts = @($validBackendMounts[0], (New-TestBindMount $sampleSource '/App/samples/demo-documents'), $validBackendMounts[2]) },
    @{ Name = 'wrong-case verifier target'; Mounts = @($validBackendMounts[0], $validBackendMounts[1], (New-TestBindMount $credentialSource '/Run/supplypilot-demo-verifier')) }
)) {
    $container = New-TestContainer 'backend' $scenario.Mounts
    $result = Test-DemoContainerMountContract -Container $container `
        -ExpectedProject 'supplypilot-sp903-local-demo' -ExpectedService backend `
        -RepositoryRoot $repositoryRoot -VolumeMetadataByName $volumeMetadata
    Assert-True (-not $result.Valid) "Complete Mount contract should reject $($scenario.Name)"
}

$wrongNamedVolumeMetadata = @{
    $documentsVolume = @{ Project = 'other-project'; VolumeKey = 'sp903-document-storage'; Mountpoint = $documentsMountpoint }
}
$wrongNamedVolumeContainer = New-TestContainer 'backend' $validBackendMounts
$wrongNamedVolumeContract = Test-DemoContainerMountContract -Container $wrongNamedVolumeContainer `
    -ExpectedProject 'supplypilot-sp903-local-demo' -ExpectedService backend `
    -RepositoryRoot $repositoryRoot -VolumeMetadataByName $wrongNamedVolumeMetadata
Assert-True (-not $wrongNamedVolumeContract.Valid) 'A matching volume name must not substitute for required Compose labels'
$wrongPhysicalMounts = @(
    (New-TestVolumeMount $documentsVolume '/other/volume/mountpoint' '/app/data/demo-documents'),
    $validBackendMounts[1], $validBackendMounts[2]
)
$wrongPhysicalContainer = New-TestContainer 'backend' $wrongPhysicalMounts
$wrongPhysicalContract = Test-DemoContainerMountContract -Container $wrongPhysicalContainer `
    -ExpectedProject 'supplypilot-sp903-local-demo' -ExpectedService backend `
    -RepositoryRoot $repositoryRoot -VolumeMetadataByName $volumeMetadata
Assert-True (-not $wrongPhysicalContract.Valid) 'Named volume source must match its inspected Docker volume mountpoint'
Assert-True (-not (Test-DemoHostPathMatch -Expected '/srv/supplypilot/samples/documents' `
    -Actual '/srv/SupplyPilot/samples/documents')) 'Linux bind source comparison must be case sensitive'

foreach ($databaseVolume in @(
    @{ Service = 'mysql'; Name = 'supplypilot-sp903-local-demo-mysql'; Key = 'sp903-mysql-data'; Target = '/var/lib/mysql' },
    @{ Service = 'pgvector'; Name = 'supplypilot-sp903-local-demo-pgvector'; Key = 'sp903-pgvector-data'; Target = '/var/lib/postgresql/data' }
)) {
    $mountpoint = "/docker/volumes/$($databaseVolume.Name)/_data"
    $databaseMetadata = @{ $databaseVolume.Name = @{
            Project = 'supplypilot-sp903-local-demo'; VolumeKey = $databaseVolume.Key; Mountpoint = $mountpoint
        } }
    $databaseContainer = New-TestContainer $databaseVolume.Service @(
        (New-TestVolumeMount $databaseVolume.Name $mountpoint $databaseVolume.Target)
    )
    $databaseContract = Test-DemoContainerMountContract -Container $databaseContainer `
        -ExpectedProject 'supplypilot-sp903-local-demo' -ExpectedService $databaseVolume.Service `
        -RepositoryRoot $repositoryRoot -VolumeMetadataByName $databaseMetadata
    Assert-True $databaseContract.Valid "$($databaseVolume.Service) persistence volume contract should pass: $($databaseContract.Error)"
}

$frontend = New-TestContainer 'frontend' @()
$frontendContract = Test-DemoContainerMountContract -Container $frontend `
    -ExpectedProject 'supplypilot-sp903-local-demo' -ExpectedService frontend `
    -RepositoryRoot $repositoryRoot -VolumeMetadataByName @{}
Assert-True $frontendContract.Valid 'Frontend with no mounts should pass'
$mountedFrontend = New-TestContainer 'frontend' @((New-TestBindMount $sampleSource '/unexpected'))
$frontendContract = Test-DemoContainerMountContract -Container $mountedFrontend `
    -ExpectedProject 'supplypilot-sp903-local-demo' -ExpectedService frontend `
    -RepositoryRoot $repositoryRoot -VolumeMetadataByName @{}
Assert-True (-not $frontendContract.Valid) 'Frontend extra mounts must be rejected'

$dockerFixtureCalls = [System.Collections.Generic.List[string]]::new()
function docker {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
    $script:dockerFixtureCalls.Add($Arguments -join ' ')
    if ($script:dockerFixtureFailure) { $global:LASTEXITCODE = 1 } else { $global:LASTEXITCODE = 0 }
}
$script:dockerFixtureFailure = $false
$script:strictVerificationCalled = $false
$script:databaseIdentityChanged = $false
$fixtureDatabaseIds = {
    if ($script:databaseIdentityChanged) { @('mysql-changed', 'pgvector-1') }
    else { @('mysql-1', 'pgvector-1') }
}
$fixtureRecreate = {
    docker compose up --detach --build --force-recreate --no-deps --wait backend
    if ($LASTEXITCODE -ne 0) { throw 'fixture recreate failure' }
    $script:legacyBackend = New-TestContainer 'backend' $validBackendMounts
}
$fixtureStrictCheck = {
    $script:strictVerificationCalled = $true
    $result = Test-DemoContainerMountContract -Container $script:legacyBackend `
        -ExpectedProject 'supplypilot-sp903-local-demo' -ExpectedService backend `
        -RepositoryRoot $repositoryRoot -VolumeMetadataByName $volumeMetadata
    if (-not $result.Valid) { throw 'post-recreate mounts invalid' }
}
Invoke-DemoBackendMountUpgrade -GetDatabaseContainerIds $fixtureDatabaseIds `
    -RecreateBackend $fixtureRecreate -AssertStrictMounts $fixtureStrictCheck
Assert-True $script:strictVerificationCalled 'Successful recreate must be followed by strict mount verification'
Assert-True (($dockerFixtureCalls -join "`n") -match 'compose up --detach --build --force-recreate --no-deps --wait backend') 'Legacy upgrade must recreate only Backend through the Docker CLI fixture'
Assert-True (-not ($dockerFixtureCalls -join "`n" | Select-String 'volume rm|volume prune|down -v')) 'Legacy upgrade fixture must not delete or mutate volumes'

$script:strictVerificationCalled = $false
$script:dockerFixtureFailure = $true
$failedRecreate = $false
try {
    Invoke-DemoBackendMountUpgrade -GetDatabaseContainerIds $fixtureDatabaseIds `
        -RecreateBackend $fixtureRecreate -AssertStrictMounts $fixtureStrictCheck
} catch { $failedRecreate = $true }
Assert-True $failedRecreate 'Recreate failure must be returned to the caller'
Assert-True (-not $script:strictVerificationCalled) 'Strict post-check must not run after failed recreation'
$script:dockerFixtureFailure = $false

$script:strictVerificationCalled = $false
$script:databaseIdentityChanged = $false
$changedDatabaseIdentity = $false
try {
    Invoke-DemoBackendMountUpgrade -GetDatabaseContainerIds {
        if ($script:databaseIdentityChanged) { @('mysql-changed', 'pgvector-1') }
        else { @('mysql-1', 'pgvector-1') }
    } -RecreateBackend {
        docker compose up --detach --build --force-recreate --no-deps --wait backend
        $script:databaseIdentityChanged = $true
        $global:LASTEXITCODE = 0
    } -AssertStrictMounts { $script:strictVerificationCalled = $true }
} catch { $changedDatabaseIdentity = $true }
Assert-True $changedDatabaseIdentity 'Legacy upgrade must fail if either database container ID changes'
Assert-True (-not $script:strictVerificationCalled) 'Strict Backend mount check must not proceed after a database identity change'
$script:databaseIdentityChanged = $false

$script:legacyBackend = $legacyBackendFixture
$staleMountRejected = $false
try {
    Invoke-DemoBackendMountUpgrade -GetDatabaseContainerIds $fixtureDatabaseIds `
        -RecreateBackend { docker compose up --detach --build --force-recreate --no-deps --wait backend; $global:LASTEXITCODE = 0 } `
        -AssertStrictMounts {
            $result = Test-DemoContainerMountContract -Container $script:legacyBackend `
                -ExpectedProject 'supplypilot-sp903-local-demo' -ExpectedService backend `
                -RepositoryRoot $repositoryRoot -VolumeMetadataByName $legacyVolumeMetadata
            if (-not $result.Valid) { throw 'post-recreate mounts invalid' }
        }
} catch { $staleMountRejected = $true }
Assert-True $staleMountRejected 'Successful recreate must fail closed if the legacy mount remains'

Write-Output 'SP-903 mount ownership and legacy upgrade fixture tests: PASS'

Write-Output 'SP-903 PowerShell startup regression tests: PASS'
