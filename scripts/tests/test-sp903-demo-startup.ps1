$ErrorActionPreference = 'Stop'
$modulePath = Join-Path $PSScriptRoot '..\Sp903DemoStartup.psm1'
$startupPath = Join-Path $PSScriptRoot '..\start-sp903-demo.ps1'
Import-Module -Name $modulePath -Force

function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw "FAIL: $Message" }
}

$composeNames = Get-ComposeEnvironmentVariableNames -ComposeFiles @(
    (Join-Path $PSScriptRoot '..\..\compose.yaml'),
    (Join-Path $PSScriptRoot '..\..\compose.demo.yaml'))
foreach ($required in @('FRONTEND_ORIGIN', 'MYSQL_PWD', 'PGPASSWORD', 'DB_USERNAME', 'DB_PASSWORD',
        'DEMO_ACCOUNT_PASSWORD', 'DEMO_FRONTEND_PORT')) {
    Assert-True ($required -in $composeNames) "Process environment protection omitted $required"
}
foreach ($removed in @('DEMO_ENV_SOURCE', 'DEMO_VERIFIER_URL', 'DEMO_VERIFIER_CLIENT_CERT')) {
    Assert-True ($removed -notin $composeNames) "Removed verifier interpolation remained: $removed"
}

$startupText = Get-Content -LiteralPath $startupPath -Raw
foreach ($removed in @('Get-NetIPAddress', 'Get-NetFirewall', 'OpenSSL', 'WaitForVerifier', 'Start-OrReuseDemoVerifier', 'host.docker.internal')) {
    Assert-True ($startupText -notmatch [regex]::Escape($removed)) "Host Verifier startup responsibility remained: $removed"
}

$fixtureName = 'SP903_ENV_RESTORE_FIXTURE'
$original = [Environment]::GetEnvironmentVariable($fixtureName, 'Process')
try {
    [Environment]::SetEnvironmentVariable($fixtureName, 'fixture-original', 'Process')
    Invoke-WithProcessEnvironmentCleared -Names @($fixtureName) -Action {
        Assert-True ($null -eq [Environment]::GetEnvironmentVariable($fixtureName, 'Process')) 'Environment value remained visible inside protected action'
        [Environment]::SetEnvironmentVariable($fixtureName, 'fixture-mutated', 'Process')
    }
    Assert-True ([Environment]::GetEnvironmentVariable($fixtureName, 'Process') -eq 'fixture-original') 'Environment value was not restored after success'
} finally {
    [Environment]::SetEnvironmentVariable($fixtureName, $original, 'Process')
}

$expectedVolumes = @{
    'supplypilot-sp903-local-demo-mysql' = @('sp903-mysql-data')
    'supplypilot-sp903-local-demo-pgvector' = @('sp903-pgvector-data')
    'supplypilot-sp903-local-demo-documents' = @('sp903-document-storage')
}
$names = @($expectedVolumes.Keys)
Assert-True ((Get-DemoVolumeSetStatus -ExistingVolumeNames @() -VolumeLabelsByName @{} `
    -ExpectedProject 'supplypilot-sp903-local-demo' -ExpectedVolumes $expectedVolumes) -eq 'FreshStart') 'Empty volume set should be fresh'
$volumeLabels = @{}
foreach ($name in $names) { $volumeLabels[$name] = @{ Project = 'supplypilot-sp903-local-demo'; VolumeKey = $expectedVolumes[$name][0] } }
Assert-True ((Get-DemoVolumeSetStatus -ExistingVolumeNames $names -VolumeLabelsByName $volumeLabels `
    -ExpectedProject 'supplypilot-sp903-local-demo' -ExpectedVolumes $expectedVolumes) -eq 'Existing') 'Correctly owned volumes should be reusable'
$partialRejected = $false
try { Get-DemoVolumeSetStatus -ExistingVolumeNames @($names[0], $names[1]) -VolumeLabelsByName $volumeLabels `
        -ExpectedProject 'supplypilot-sp903-local-demo' -ExpectedVolumes $expectedVolumes | Out-Null }
catch { $partialRejected = $true }
Assert-True $partialRejected 'Partial volume set must fail closed'
$caseMismatchNames = @($names)
$caseMismatchNames[0] = $names[0].ToUpperInvariant()
$caseMismatchRejected = $false
try { Get-DemoVolumeSetStatus -ExistingVolumeNames $caseMismatchNames -VolumeLabelsByName $volumeLabels `
        -ExpectedProject 'supplypilot-sp903-local-demo' -ExpectedVolumes $expectedVolumes | Out-Null }
catch { $caseMismatchRejected = $true }
Assert-True $caseMismatchRejected 'Case-mismatched volume name must fail closed'

function New-TestContainer([string]$Service, [object[]]$Mounts, [string]$Project = 'supplypilot-sp903-local-demo') {
    [pscustomobject]@{ Config = [pscustomobject]@{ Labels = @{
        'com.docker.compose.project' = $Project; 'com.docker.compose.service' = $Service
    } }; Mounts = $Mounts }
}
function New-TestVolumeMount([string]$Name, [string]$Source, [string]$Target, [bool]$ReadWrite = $true) {
    [pscustomobject]@{ Type = 'volume'; Name = $Name; Source = $Source; Destination = $Target; RW = $ReadWrite }
}
function New-TestBindMount([string]$Source, [string]$Target, [bool]$ReadWrite = $false) {
    [pscustomobject]@{ Type = 'bind'; Name = $null; Source = $Source; Destination = $Target; RW = $ReadWrite }
}

$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$docVolume = 'supplypilot-sp903-local-demo-documents'
$docPath = '/docker/volumes/supplypilot-sp903-local-demo-documents/_data'
$sampleSource = Join-Path $repo 'samples\documents'
$credentialSource = Join-Path $repo '.sp903-demo-credential'
$verifierSource = Join-Path $repo '.sp903-demo-verifier-client'
$volumeMetadata = @{ $docVolume = @{ Project = 'supplypilot-sp903-local-demo'; VolumeKey = 'sp903-document-storage'; Mountpoint = $docPath } }
$currentMounts = @(
    (New-TestVolumeMount $docVolume $docPath '/app/data/demo-documents'),
    (New-TestBindMount $sampleSource '/app/samples/demo-documents')
)
$current = Test-DemoContainerMountContract -Container (New-TestContainer 'backend' $currentMounts) `
    -ExpectedProject 'supplypilot-sp903-local-demo' -ExpectedService backend -RepositoryRoot $repo -VolumeMetadataByName $volumeMetadata
Assert-True $current.Valid 'New Backend mount contract must contain only the document volume and sample bind'

Assert-True (Test-DemoHostPathMatch -Expected 'C:\SupplyPilot\samples\documents' `
    -Actual '/run/desktop/mnt/host/c/SupplyPilot/samples/documents') 'Docker Desktop Windows path should normalize'
Assert-True (-not (Test-DemoHostPathMatch -Expected '/srv/supplypilot/samples/documents' `
    -Actual '/srv/SupplyPilot/samples/documents')) 'Linux path comparison must remain case sensitive'

$legacyVolumeName = 'legacy-document-volume'
$legacyVolumePath = '/docker/volumes/legacy-document-volume/_data'
$legacyMetadata = @{
    $docVolume = $volumeMetadata[$docVolume]
    $legacyVolumeName = @{ Project = 'supplypilot-sp903-local-demo'; VolumeKey = 'document-storage'; Mountpoint = $legacyVolumePath }
}
$knownLegacyMounts = @(
    @{ Mount = (New-TestBindMount $credentialSource '/run/supplypilot-demo-credentials'); Switch = 'AllowKnownLegacyCredentialMount'; Property = 'LegacyCredential' },
    @{ Mount = (New-TestBindMount $verifierSource '/run/supplypilot-demo-verifier'); Switch = 'AllowKnownLegacyVerifierMount'; Property = 'LegacyVerifier' }
)
foreach ($legacy in $knownLegacyMounts) {
    $params = @{ Container = (New-TestContainer 'backend' (@($currentMounts) + @($legacy.Mount)))
        ExpectedProject = 'supplypilot-sp903-local-demo'; ExpectedService = 'backend'; RepositoryRoot = $repo
        VolumeMetadataByName = $volumeMetadata }
    $params[$legacy.Switch] = $true
    $contract = Test-DemoContainerMountContract @params
    Assert-True ($contract.Valid -and $contract.($legacy.Property)) "Known legacy bind should be accepted for upgrade: $($legacy.Mount.Destination)"
}
$allLegacy = @($currentMounts) + @(
    (New-TestBindMount $credentialSource '/run/supplypilot-demo-credentials'),
    (New-TestBindMount $verifierSource '/run/supplypilot-demo-verifier'),
    (New-TestVolumeMount $legacyVolumeName $legacyVolumePath '/app/data/documents')
)
$allLegacyArgs = @{ Container = (New-TestContainer 'backend' $allLegacy); ExpectedProject = 'supplypilot-sp903-local-demo'
    ExpectedService = 'backend'; RepositoryRoot = $repo; VolumeMetadataByName = $legacyMetadata
    AllowKnownLegacyCredentialMount = $true; AllowKnownLegacyVerifierMount = $true; AllowKnownLegacyDocumentMount = $true }
Assert-True ((Test-DemoContainerMountContract @allLegacyArgs).Valid) 'Both supported bind generations and the retired document volume may be upgraded together'

foreach ($badMount in @(
    (New-TestBindMount (Join-Path $repo 'other') '/run/supplypilot-demo-credentials'),
    (New-TestBindMount $credentialSource '/run/supplypilot-demo-credentials' $true),
    (New-TestBindMount $verifierSource '/run/supplypilot-demo-verifier' $true),
    (New-TestBindMount $credentialSource '/run/other-credentials')
)) {
    $params = @{ Container = (New-TestContainer 'backend' (@($currentMounts) + @($badMount)))
        ExpectedProject = 'supplypilot-sp903-local-demo'; ExpectedService = 'backend'; RepositoryRoot = $repo
        VolumeMetadataByName = $volumeMetadata; AllowKnownLegacyCredentialMount = $true; AllowKnownLegacyVerifierMount = $true }
    Assert-True (-not (Test-DemoContainerMountContract @params).Valid) 'Unknown, writable, or misplaced legacy bind must fail closed'
}
$unknownExtra = Test-DemoContainerMountContract -Container (New-TestContainer 'backend' (@($currentMounts) + @((New-TestBindMount (Join-Path $repo 'other') '/other')))) `
    -ExpectedProject 'supplypilot-sp903-local-demo' -ExpectedService backend -RepositoryRoot $repo -VolumeMetadataByName $volumeMetadata
Assert-True (-not $unknownExtra.Valid) 'Unknown extra mounts must fail closed'

foreach ($fixture in @(
    @{ Service = 'mysql'; Name = 'supplypilot-sp903-local-demo-mysql'; Key = 'sp903-mysql-data'; Target = '/var/lib/mysql' },
    @{ Service = 'pgvector'; Name = 'supplypilot-sp903-local-demo-pgvector'; Key = 'sp903-pgvector-data'; Target = '/var/lib/postgresql/data' }
)) {
    $path = "/docker/volumes/$($fixture.Name)/_data"
    $metadata = @{ $fixture.Name = @{ Project = 'supplypilot-sp903-local-demo'; VolumeKey = $fixture.Key; Mountpoint = $path } }
    $contract = Test-DemoContainerMountContract -Container (New-TestContainer $fixture.Service @((New-TestVolumeMount $fixture.Name $path $fixture.Target))) `
        -ExpectedProject 'supplypilot-sp903-local-demo' -ExpectedService $fixture.Service -RepositoryRoot $repo -VolumeMetadataByName $metadata
    Assert-True $contract.Valid "$($fixture.Service) volume contract should pass"
}
Assert-True ((Test-DemoContainerMountContract -Container (New-TestContainer 'frontend' @()) `
    -ExpectedProject 'supplypilot-sp903-local-demo' -ExpectedService frontend -RepositoryRoot $repo -VolumeMetadataByName @{}).Valid) 'Frontend with no mounts should pass'

$calls = [System.Collections.Generic.List[string]]::new()
function docker { param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments); $script:calls.Add($Arguments -join ' '); $global:LASTEXITCODE = 0 }
$script:changedIds = $false
$ids = { if ($script:changedIds) { @('mysql-changed', 'pgvector-1') } else { @('mysql-1', 'pgvector-1') } }
$strictCalled = $false
Invoke-DemoBackendMountUpgrade -GetDatabaseContainerIds $ids `
    -RecreateBackend { docker compose up --detach --build --force-recreate --no-deps --wait backend } `
    -AssertStrictMounts { $script:strictCalled = $true }
Assert-True $strictCalled 'Backend recreation must be followed by strict mount checking'
Assert-True (($calls -join ' ') -match '--force-recreate --no-deps') 'Credential updates and legacy upgrades must recreate only Backend'
Assert-True (-not (($calls -join ' ') -match 'volume rm|volume prune|down -v')) 'Upgrade must not delete volumes'
$identityRejected = $false
try {
    Invoke-DemoBackendMountUpgrade -GetDatabaseContainerIds $ids `
        -RecreateBackend { $script:changedIds = $true } -AssertStrictMounts { throw 'Should not run' }
} catch { $identityRejected = $true }
Assert-True $identityRejected 'Upgrade must fail if database container identity changes'

Write-Output 'SP-903 PowerShell startup regression tests: PASS'
