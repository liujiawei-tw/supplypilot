[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$backendRoot = [IO.Path]::GetFullPath((Join-Path $repoRoot 'backend'))
$scriptPath = Join-Path $repoRoot 'scripts\reset-demo.ps1'
$envKeys = @(
    'DEMO_RESET_CONFIRMATION'
    'DEMO_ALLOWED_DATABASE'
    'DEMO_ALLOWED_SEMANTIC_DATABASE'
    'DOCUMENT_STORAGE_PATH'
)
$script:passed = 0
$script:failed = 0

function Get-EnvSnapshot {
    param([string[]]$Names)

    $snapshot = @{}
    foreach ($name in $Names) {
        $item = Get-Item -LiteralPath "Env:$name" -ErrorAction SilentlyContinue
        $snapshot[$name] = @{
            Exists = ($null -ne $item)
            Value = if ($null -ne $item) { [string]$item.Value } else { $null }
        }
    }
    return $snapshot
}

function Restore-EnvSnapshot {
    param([hashtable]$Snapshot, [string[]]$Names)

    foreach ($name in $Names) {
        if ($Snapshot[$name].Exists) {
            Set-Item -LiteralPath "Env:$name" -Value $Snapshot[$name].Value
        } else {
            Remove-Item -LiteralPath "Env:$name" -ErrorAction SilentlyContinue
        }
    }
}

function Assert-EnvSnapshotEqual {
    param([hashtable]$Expected, [string[]]$Names)

    foreach ($name in $Names) {
        $item = Get-Item -LiteralPath "Env:$name" -ErrorAction SilentlyContinue
        $exists = ($null -ne $item)
        if ($exists -ne $Expected[$name].Exists) {
            throw "Environment existence mismatch: $name"
        }
        if ($exists -and ([string]$item.Value -cne [string]$Expected[$name].Value)) {
            throw "Environment value mismatch: $name"
        }
    }
}

function Assert-Equal {
    param($Expected, $Actual, [string]$Message)
    if ($Expected -cne $Actual) {
        throw "$Message (expected '$Expected', actual '$Actual')"
    }
}

function Assert-TemporaryEnvironment {
    param([string]$ExpectedStorageRoot)

    Assert-Equal 'RESET-SP-901-DEMO' $env:DEMO_RESET_CONFIRMATION 'confirmation during runner'
    Assert-Equal 'supplypilot_demo_test' $env:DEMO_ALLOWED_DATABASE 'database during runner'
    Assert-Equal 'supplypilot_semantic_demo_test' $env:DEMO_ALLOWED_SEMANTIC_DATABASE 'semantic database during runner'
    Assert-Equal $ExpectedStorageRoot $env:DOCUMENT_STORAGE_PATH 'storage path during runner'
    Assert-Equal $backendRoot ([IO.Path]::GetFullPath((Get-Location).Path)) 'runner working directory'
}

function Invoke-TestCase {
    param([string]$Name, [scriptblock]$Body)

    try {
        & $Body
        $script:passed++
        Write-Host "PASS: $Name"
    } catch {
        $script:failed++
        Write-Host "FAIL: $Name`n$($_ | Out-String)" -ForegroundColor Red
    }
}

# Load the exact production function definitions without executing the script entry point.
$tokens = $null
$parseErrors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile(
    $scriptPath,
    [ref]$tokens,
    [ref]$parseErrors
)
if ($parseErrors.Count -ne 0) {
    throw "PowerShell parser errors:`n$($parseErrors | Out-String)"
}
$requiredFunctions = @(
    'Test-ExplicitDemoTargetName'
    'Resolve-DemoStorageRoot'
    'Assert-DemoStorageRoot'
    'Invoke-DemoResetCommand'
)
foreach ($functionName in $requiredFunctions) {
    $definition = $ast.FindAll({
        param($node)
        $node -is [Management.Automation.Language.FunctionDefinitionAst] -and
            $node.Name -ceq $functionName
    }, $true) | Select-Object -First 1
    if ($null -eq $definition) {
        throw "Production function not found: $functionName"
    }
    . ([scriptblock]::Create($definition.Extent.Text))
}

$sessionSnapshot = Get-EnvSnapshot -Names $envKeys
try {
    $storageRoot = Resolve-DemoStorageRoot -ConfiguredPath '.\sp901-demo-reset-storage' -BackendRoot $backendRoot

    foreach ($scenario in @(
        @{ Name = 'existing env - success'; Kind = 'success'; ExitCode = 0 }
        @{ Name = 'existing env - non-zero'; Kind = 'nonzero'; ExitCode = 17 }
        @{ Name = 'existing env - exception'; Kind = 'exception'; ExitCode = $null }
    )) {
        Invoke-TestCase $scenario.Name {
            for ($index = 0; $index -lt $envKeys.Count; $index++) {
                Set-Item -LiteralPath "Env:$($envKeys[$index])" -Value "original-$index"
            }
            $before = Get-EnvSnapshot -Names $envKeys
            $runner = if ($scenario.Kind -eq 'exception') {
                { Assert-TemporaryEnvironment $storageRoot; throw 'runner failure sentinel' }
            } else {
                $expectedExitCode = $scenario.ExitCode
                { Assert-TemporaryEnvironment $storageRoot; return $expectedExitCode }
            }
            if ($scenario.Kind -eq 'exception') {
                $caught = $null
                try {
                    Invoke-DemoResetCommand -BackendRoot $backendRoot -StorageRoot $storageRoot `
                        -Database 'supplypilot_demo_test' -SemanticDatabase 'supplypilot_semantic_demo_test' `
                        -CommandRunner $runner
                } catch {
                    $caught = $_
                }
                Assert-Equal 'runner failure sentinel' $caught.Exception.Message 'exception propagation'
            } else {
                $actual = Invoke-DemoResetCommand -BackendRoot $backendRoot -StorageRoot $storageRoot `
                    -Database 'supplypilot_demo_test' -SemanticDatabase 'supplypilot_semantic_demo_test' `
                    -CommandRunner $runner
                Assert-Equal $scenario.ExitCode $actual 'runner exit code'
            }
            Assert-EnvSnapshotEqual -Expected $before -Names $envKeys
        }
    }

    foreach ($scenario in @(
        @{ Name = 'absent env - success'; Kind = 'success'; ExitCode = 0 }
        @{ Name = 'absent env - non-zero'; Kind = 'nonzero'; ExitCode = 17 }
        @{ Name = 'absent env - exception'; Kind = 'exception'; ExitCode = $null }
    )) {
        Invoke-TestCase $scenario.Name {
            foreach ($name in $envKeys) {
                Remove-Item -LiteralPath "Env:$name" -ErrorAction SilentlyContinue
            }
            $before = Get-EnvSnapshot -Names $envKeys
            Assert-EnvSnapshotEqual -Expected $before -Names $envKeys
            $runner = if ($scenario.Kind -eq 'exception') {
                { Assert-TemporaryEnvironment $storageRoot; throw 'runner failure sentinel' }
            } else {
                $expectedExitCode = $scenario.ExitCode
                { Assert-TemporaryEnvironment $storageRoot; return $expectedExitCode }
            }
            if ($scenario.Kind -eq 'exception') {
                $caught = $null
                try {
                    Invoke-DemoResetCommand -BackendRoot $backendRoot -StorageRoot $storageRoot `
                        -Database 'supplypilot_demo_test' -SemanticDatabase 'supplypilot_semantic_demo_test' `
                        -CommandRunner $runner
                } catch {
                    $caught = $_
                }
                Assert-Equal 'runner failure sentinel' $caught.Exception.Message 'exception propagation'
            } else {
                $actual = Invoke-DemoResetCommand -BackendRoot $backendRoot -StorageRoot $storageRoot `
                    -Database 'supplypilot_demo_test' -SemanticDatabase 'supplypilot_semantic_demo_test' `
                    -CommandRunner $runner
                Assert-Equal $scenario.ExitCode $actual 'runner exit code'
            }
            Assert-EnvSnapshotEqual -Expected $before -Names $envKeys
        }
    }

    Invoke-TestCase 'relative path from repository root' {
        Push-Location $repoRoot
        try {
            $actual = Resolve-DemoStorageRoot -ConfiguredPath '.\sp901-demo-reset-storage' -BackendRoot $backendRoot
        } finally {
            Pop-Location
        }
        Assert-Equal ([IO.Path]::GetFullPath((Join-Path $backendRoot '.\sp901-demo-reset-storage'))) $actual `
            'repository-root relative path resolution'
    }

    Invoke-TestCase 'relative path from unrelated working directory' {
        $temporaryCwd = Join-Path ([IO.Path]::GetTempPath()) ("sp901-cwd-" + [guid]::NewGuid())
        [void](New-Item -ItemType Directory -Path $temporaryCwd)
        try {
            Push-Location $temporaryCwd
            try {
                $actual = Resolve-DemoStorageRoot -ConfiguredPath '.\sp901-demo-reset-storage' -BackendRoot $backendRoot
            } finally {
                Pop-Location
            }
            Assert-Equal ([IO.Path]::GetFullPath((Join-Path $backendRoot '.\sp901-demo-reset-storage'))) $actual `
                'unrelated-CWD relative path resolution'
        } finally {
            Remove-Item -LiteralPath $temporaryCwd -Recurse -Force
        }
    }

    Invoke-TestCase 'absolute storage path remains absolute' {
        $absolute = [IO.Path]::GetFullPath((Join-Path ([IO.Path]::GetTempPath()) 'sp901-absolute-demo-storage'))
        $resolved = Resolve-DemoStorageRoot -ConfiguredPath $absolute -BackendRoot $backendRoot
        Assert-Equal $absolute $resolved 'absolute path resolution'
        $before = Get-EnvSnapshot -Names $envKeys
        $actual = Invoke-DemoResetCommand -BackendRoot $backendRoot -StorageRoot $resolved `
            -Database 'supplypilot_demo_test' -SemanticDatabase 'supplypilot_semantic_demo_test' `
            -CommandRunner { Assert-TemporaryEnvironment $absolute; return 0 }
        Assert-Equal 0 $actual 'absolute path runner exit code'
        Assert-EnvSnapshotEqual -Expected $before -Names $envKeys
    }

    Invoke-TestCase 'legal demo storage root invokes runner' {
        $invocation = [pscustomobject]@{ Called = $false }
        $actual = Invoke-DemoResetCommand -BackendRoot $backendRoot -StorageRoot $storageRoot `
            -Database 'supplypilot_demo_test' -SemanticDatabase 'supplypilot_semantic_demo_test' `
            -CommandRunner { $invocation.Called = $true; return 0 }
        Assert-Equal 0 $actual 'legal root exit code'
        Assert-Equal $true $invocation.Called 'legal root runner invocation'
    }

    Invoke-TestCase 'non-demo leaf is rejected before runner and state changes' {
        $temporaryRoot = Join-Path ([IO.Path]::GetTempPath()) ("sp901-illegal-root-" + [guid]::NewGuid())
        $illegalRoot = Join-Path $temporaryRoot 'demo-project\data\documents'
        [void](New-Item -ItemType Directory -Path $illegalRoot -Force)
        $sentinel = Join-Path $illegalRoot 'keep.txt'
        Set-Content -LiteralPath $sentinel -Value 'must-not-change' -NoNewline
        $invocation = [pscustomobject]@{ Called = $false }
        $before = Get-EnvSnapshot -Names $envKeys
        try {
            $caught = $null
            try {
                Invoke-DemoResetCommand -BackendRoot $backendRoot -StorageRoot $illegalRoot `
                    -Database 'supplypilot_demo_test' -SemanticDatabase 'supplypilot_semantic_demo_test' `
                    -CommandRunner { $invocation.Called = $true; return 0 }
            } catch {
                $caught = $_
            }
            Assert-Equal 'The DOCUMENT_STORAGE_PATH root directory itself must be explicitly demo-isolated.' `
                $caught.Exception.Message 'illegal root rejection'
            Assert-Equal $false $invocation.Called 'illegal root runner invocation'
            Assert-Equal 'must-not-change' (Get-Content -Raw -LiteralPath $sentinel) 'illegal root sentinel'
            Assert-EnvSnapshotEqual -Expected $before -Names $envKeys
        } finally {
            Remove-Item -LiteralPath $temporaryRoot -Recurse -Force
        }
    }
} finally {
    Restore-EnvSnapshot -Snapshot $sessionSnapshot -Names $envKeys
}

Write-Host "P2-01_RESULTS passed=$script:passed failed=$script:failed total=$($script:passed + $script:failed)"
if ($script:failed -ne 0) {
    exit 1
}
exit 0
