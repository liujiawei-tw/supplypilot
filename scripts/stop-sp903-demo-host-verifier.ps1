[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$serverDirectory = Join-Path $repositoryRoot '.sp903-demo-verifier-server'
$lockPath = Join-Path $serverDirectory 'supervisor.lock'
$envFile = Join-Path $repositoryRoot '.env.demo'
$supervisor = Join-Path $PSScriptRoot 'sp903-demo-host-verifier-supervisor.py'
$python = Get-Command python -ErrorAction SilentlyContinue
if (-not $python) { $python = Get-Command python3 -ErrorAction SilentlyContinue }
if (-not $python) { throw 'Python 3 is required to inspect the managed Host Verifier.' }

$status = & $python.Source $supervisor --status --lock-file $lockPath 2>$null
if ($LASTEXITCODE -ne 0 -or $status.Count -ne 1) { throw 'Could not verify Host Verifier supervisor ownership.' }
if ([string]$status[0] -eq 'not-running') {
    Write-Output 'SP-903 Host Verifier supervisor is not running.'
    exit 0
}
if ([string]$status[0] -notmatch '^running:(?<pid>\d+)$') { throw 'Host Verifier status is ambiguous; no process was stopped.' }
$processId = [int]$Matches.pid
$pythonPath = [IO.Path]::GetFullPath($python.Source)
$pathComparison = if ($IsWindows) { [StringComparison]::OrdinalIgnoreCase } else { [StringComparison]::Ordinal }
$expected = @($supervisor, $lockPath, $envFile,
    (Join-Path $serverDirectory 'server.crt'), (Join-Path $serverDirectory 'server.key'),
    (Join-Path $serverDirectory 'ca.crt'), '18443')
if ($IsWindows) {
    $process = Get-CimInstance Win32_Process -Filter "ProcessId = $processId" -ErrorAction SilentlyContinue
    if (-not $process -or -not [string]::Equals([IO.Path]::GetFullPath([string]$process.ExecutablePath),
            $pythonPath, $pathComparison)) {
        throw 'Host Verifier PID does not match the expected Python executable; no process was stopped.'
    }
    $commandLine = [string]$process.CommandLine
} else {
    $commandLine = (& ps -p $processId -o args= 2>$null) -join ' '
    $executable = (& readlink -f "/proc/$processId/exe" 2>$null) -join ''
    if (-not $commandLine -or -not $executable -or -not [string]::Equals(
            [IO.Path]::GetFullPath($executable), $pythonPath, $pathComparison)) {
        throw 'Host Verifier PID does not match the expected Python process; no process was stopped.'
    }
}
foreach ($value in $expected) {
    if ($commandLine.IndexOf($value, $pathComparison) -lt 0) {
        throw 'Host Verifier process arguments do not match the managed Demo identity; no process was stopped.'
    }
}

if ($IsWindows) {
    & taskkill.exe /PID $processId /T /F *> $null
    if ($LASTEXITCODE -ne 0) { throw 'Could not stop the verified Host Verifier process tree.' }
} else {
    Stop-Process -Id $processId -ErrorAction Stop
}
for ($attempt = 0; $attempt -lt 40; $attempt++) {
    $status = & $python.Source $supervisor --status --lock-file $lockPath 2>$null
    if ($LASTEXITCODE -eq 0 -and [string]$status[0] -eq 'not-running') {
        Write-Output 'SP-903 Host Verifier supervisor stopped.'
        exit 0
    }
    Start-Sleep -Milliseconds 250
}
throw 'Host Verifier supervisor did not release its instance lock after the stop request.'
