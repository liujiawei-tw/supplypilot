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
        'DB_USERNAME', 'DB_PASSWORD', 'DEMO_ACCOUNT_PASSWORD', 'DEMO_FRONTEND_PORT')) {
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
    @{ Name = 'unknown volume key'; Names = $allNames; Labels = (@{ $allNames[0] = @{ Project = 'supplypilot-sp903-local-demo'; VolumeKey = 'unknown-key' }; $allNames[1] = $labels[$allNames[1]]; $allNames[2] = $labels[$allNames[2]] }) }
)) {
    $thrown = $false
    try {
        Get-DemoVolumeSetStatus -ExistingVolumeNames $scenario.Names -VolumeLabelsByName $scenario.Labels `
            -ExpectedProject 'supplypilot-sp903-local-demo' -ExpectedVolumes $expected | Out-Null
    } catch { $thrown = $true }
    Assert-True $thrown "Volume safety check did not reject $($scenario.Name)"
}

Write-Output 'SP-903 PowerShell startup regression tests: PASS'
