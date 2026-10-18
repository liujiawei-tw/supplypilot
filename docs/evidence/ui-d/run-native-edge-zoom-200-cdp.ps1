[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$evidenceDir = $PSScriptRoot
$screenshots = Join-Path $evidenceDir 'screenshots'
$frontendUrl = 'http://127.0.0.1:5181'
$cdpPort = 9223
$tag = [Guid]::NewGuid().ToString('N')
$runtimeRoot = Join-Path $env:TEMP "SupplyPilot-ui-d-edge-$tag"
$reuseProfilePath = $env:UI_D_REUSE_PROFILE_PATH
$profilePath = $null
$sessionPath = Join-Path $runtimeRoot 'session.json'
$pageStatePath = Join-Path $runtimeRoot 'page-state.json'
$goPath = Join-Path $runtimeRoot 'go.json'
$resultPath = Join-Path $runtimeRoot 'page-result.json'
$signalPath = Join-Path $runtimeRoot 'manual-200-confirmed.json'
$screenshotReviewPath = Join-Path $runtimeRoot 'native-zoom-screenshot-reviewed.json'
$stdoutPath = Join-Path $runtimeRoot 'runner.stdout.log'
$stderrPath = Join-Path $runtimeRoot 'runner.stderr.log'
$runnerPath = Join-Path $evidenceDir 'run-native-edge-zoom-200.mjs'
$finalEvidencePath = Join-Path $evidenceDir 'native-edge-zoom-200-cdp-final.json'
$nativeScreenshotPath = Join-Path $screenshots 'edge-window-zoom-200-cdp-verified.png'
$edgeRootPid = $null
$edgeProcesses = @()
$edgeWasVerifiedIsolated = $false
$viteProcess = $null
$nodeProcess = $null
$viteStartedHere = $false

Add-Type -AssemblyName System.Drawing
$captureCode = @'
using System;
using System.Runtime.InteropServices;
public static class UiDNativeCapture {
    [DllImport("user32.dll")] private static extern int GetSystemMetrics(int index);
    [DllImport("user32.dll")] private static extern uint GetDpiForSystem();
    public static int[] GetVirtualScreenInfo() {
        int left=GetSystemMetrics(76), top=GetSystemMetrics(77), width=GetSystemMetrics(78), height=GetSystemMetrics(79);
        if(width<400 || height<300) throw new InvalidOperationException("Virtual screen bounds are invalid.");
        return new int[] { left, top, width, height, (int)GetDpiForSystem() };
    }
}
'@
Add-Type -TypeDefinition $captureCode -Language CSharp
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes

function Write-Utf8Json([string]$Path, $Value, [int]$Depth = 30) {
    $json = $Value | ConvertTo-Json -Depth $Depth
    [IO.File]::WriteAllText($Path, $json + [Environment]::NewLine, [Text.UTF8Encoding]::new($false))
}

function Get-TcpListeners([int]$Port) {
    $netstat = Join-Path $env:WINDIR 'System32\netstat.exe'
    $listeners = @()
    foreach ($line in (& $netstat -ano -p tcp)) {
        if ($line -match '^\s*TCP\s+(\S+):(\d+)\s+\S+\s+LISTENING\s+(\d+)\s*$' -and [int]$Matches[2] -eq $Port) {
            $address = $Matches[1].Trim('[',']')
            $listeners += [PSCustomObject]@{ LocalAddress=$address; LocalPort=$Port; ProcessId=[int]$Matches[3] }
        }
    }
    return $listeners
}

function Test-IsTaskTempProfile([string]$Path) {
    if (-not $Path -or (Split-Path -Leaf $Path) -ne 'edge-profile') { return $false }
    $resolved = [IO.Path]::GetFullPath($Path)
    return $resolved -match '^[A-Za-z]:\\Users\\[^\\]+\\AppData\\Local\\Temp\\SupplyPilot-ui-d-edge-[0-9a-f]{32}\\edge-profile$' -or
        $resolved -match '^[A-Za-z]:\\Users\\[^\\]+\\AppData\\Local\\Packages\\sandbox\.\{[0-9a-f-]+\}\\AC\\Temp\\SupplyPilot-ui-d-edge-[0-9a-f]{32}\\edge-profile$'
}

function Stop-IsolatedEdgeTree([int]$RootProcessId) {
    if ($RootProcessId -le 0) { return }
    $process = Get-Process -Id $RootProcessId -ErrorAction SilentlyContinue
    if ($process) {
        $taskkill = Join-Path $env:WINDIR 'System32\taskkill.exe'
        $null = Start-Process -FilePath $taskkill -ArgumentList @('/PID',"$RootProcessId",'/T','/F') -Wait -WindowStyle Hidden -PassThru
        Start-Sleep -Milliseconds 400
    }
}

function Get-UniqueEdgeWindow([int]$ProcessId) {
    $windows = @([System.Windows.Automation.AutomationElement]::RootElement.FindAll(
        [System.Windows.Automation.TreeScope]::Children,
        [System.Windows.Automation.Condition]::TrueCondition) | Where-Object {
            $_.Current.ProcessId -eq $ProcessId -and $_.Current.ControlType -eq [System.Windows.Automation.ControlType]::Window -and $_.Current.IsOffscreen -eq $false
        })
    if ($windows.Count -ne 1) { throw "Expected one visible top-level Edge UIA window for isolated PID $ProcessId; found $($windows.Count)." }
    return $windows[0]
}

function Get-EdgeZoomLabel($Window) {
    $all = $Window.FindAll([System.Windows.Automation.TreeScope]::Descendants,[System.Windows.Automation.Condition]::TrueCondition)
    foreach ($element in $all) {
        $name = $element.Current.Name
        if ($name -match '縮放\s*[:：]\s*\d+%|Zoom\s*\d+%') { return $name }
    }
    return $null
}

function Test-EdgeSettingsMenuOpen($Window) {
    $all = $Window.FindAll([System.Windows.Automation.TreeScope]::Descendants,[System.Windows.Automation.Condition]::TrueCondition)
    foreach ($element in $all) {
        if ($element.Current.Name -match '^(新視窗|新索引標籤|新增 InPrivate|New window|New tab|New InPrivate)') { return $true }
    }
    return $false
}

function Capture-NativeScreen([string]$Path) {
    $screenInfo = [UiDNativeCapture]::GetVirtualScreenInfo()
    $bitmap = New-Object System.Drawing.Bitmap($screenInfo[2],$screenInfo[3])
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    try {
        $graphics.CopyFromScreen($screenInfo[0],$screenInfo[1],0,0,(New-Object System.Drawing.Size($screenInfo[2],$screenInfo[3])),[System.Drawing.CopyPixelOperation]::SourceCopy)
        $bitmap.Save($Path,[System.Drawing.Imaging.ImageFormat]::Png)
    } finally { $graphics.Dispose(); $bitmap.Dispose() }
    return @{ left=$screenInfo[0]; top=$screenInfo[1]; width=$screenInfo[2]; height=$screenInfo[3]; systemDpi=$screenInfo[4]; systemDpiScale=[Math]::Round($screenInfo[4]/96.0,3) }
}

try {
    $null = New-Item -ItemType Directory -Path $runtimeRoot -Force
    $null = New-Item -ItemType Directory -Path $screenshots -Force
    if ($reuseProfilePath) {
        $resolvedReuseProfile = (Resolve-Path -LiteralPath $reuseProfilePath -ErrorAction Stop).Path
        if (-not (Test-IsTaskTempProfile $resolvedReuseProfile)) {
            throw 'Reuse profile is not a verified task-specific edge-profile directory inside Temp.'
        }
        $profilePath = $resolvedReuseProfile
    }
    if (-not $profilePath) { $profilePath = Join-Path $runtimeRoot 'edge-profile' }
    Write-Output "Fixture runtime: $runtimeRoot"
    $viteJs = Join-Path $repo 'frontend/node_modules/vite/bin/vite.js'
    if (-not (Test-Path -LiteralPath $viteJs)) { throw 'Frontend Vite dependency is missing; no package install will be attempted.' }
    $nodeExe = (Get-Command node.exe -ErrorAction Stop).Source
    $env:VITE_API_BASE_URL = 'http://127.0.0.1:8080/api/v1'
    $viteListeners = @(Get-TcpListeners 5181)
    if ($viteListeners.Count) {
        if ($viteListeners.Count -ne 1 -or $viteListeners[0].LocalAddress -ne '127.0.0.1') { throw 'Port 5181 is not uniquely bound to localhost.' }
        $viteOwner = Get-CimInstance Win32_Process -Filter "ProcessId=$($viteListeners[0].ProcessId)" -ErrorAction Stop
        if (-not $viteOwner -or $viteOwner.Name -ne 'node.exe' -or -not $viteOwner.CommandLine.Contains((Join-Path $repo 'frontend')) -or $viteOwner.CommandLine -notmatch 'vite\.js' -or $viteOwner.CommandLine -notmatch '--host 127\.0\.0\.1' -or $viteOwner.CommandLine -notmatch '--port 5181') {
            throw "Port 5181 listener PID $($viteListeners[0].ProcessId) is not the SupplyPilot Vite Fixture server."
        }
        Write-Output "Reusing verified localhost SupplyPilot Vite Fixture PID $($viteOwner.ProcessId)."
    } else {
        $viteProcess = Start-Process -FilePath $nodeExe -ArgumentList @('"' + $viteJs + '"','--host','127.0.0.1','--port','5181','--strictPort') -WorkingDirectory (Join-Path $repo 'frontend') -PassThru -WindowStyle Hidden -RedirectStandardOutput (Join-Path $runtimeRoot 'vite.stdout.log') -RedirectStandardError (Join-Path $runtimeRoot 'vite.stderr.log')
        $viteStartedHere = $true
    }
    $health = $false
    $healthDeadline = [DateTime]::UtcNow.AddSeconds(30)
    while (-not $health -and [DateTime]::UtcNow -lt $healthDeadline) {
        if ($viteStartedHere -and $viteProcess.HasExited) { throw "Vite Fixture server exited during startup: $((Get-Content (Join-Path $runtimeRoot 'vite.stderr.log') -Raw -ErrorAction SilentlyContinue))" }
        try { $response = Invoke-WebRequest -Uri "$frontendUrl/purchase-orders" -TimeoutSec 2 -UseBasicParsing; $health = $response.StatusCode -eq 200 -and $response.Content -match 'SupplyPilot' } catch { Start-Sleep -Milliseconds 300 }
    }
    if (-not $health) { throw 'Controlled Vite Fixture server did not become ready.' }
    Write-Output 'Fixture server ready at http://127.0.0.1:5181.'

    $playwrightPackage = Get-ChildItem 'C:\Users\z0976\AppData\Local\npm-cache\_npx' -Directory -ErrorAction Stop |
        ForEach-Object { Join-Path $_.FullName 'node_modules\playwright\package.json' } |
        Where-Object { Test-Path -LiteralPath $_ } |
        Where-Object { (Get-Content -LiteralPath $_ -Raw | ConvertFrom-Json).version -eq '1.64.1' } |
        Select-Object -First 1
    if (-not $playwrightPackage) { throw 'Cached Playwright 1.64.1 package not found; no install will be attempted.' }
    $playwrightPackage = Split-Path -Parent $playwrightPackage

    $edgePath = 'C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe'
    if (-not (Test-Path -LiteralPath $edgePath)) { throw 'Microsoft Edge executable was not found at the installed path.' }
    $cdpListeners = @(Get-TcpListeners $cdpPort)
    if ($cdpListeners.Count) {
        if ($cdpListeners.Count -ne 1 -or $cdpListeners[0].LocalAddress -ne '127.0.0.1') { throw 'Existing CDP is not uniquely bound to localhost.' }
        $edgeRootPid = [int]$cdpListeners[0].ProcessId
        $edgeRootInfo = Get-CimInstance Win32_Process -Filter "ProcessId=$edgeRootPid" -ErrorAction Stop
        if (-not $edgeRootInfo -or $edgeRootInfo.Name -ne 'msedge.exe' -or $edgeRootInfo.CommandLine -notlike "*--user-data-dir=`"$profilePath`"*--remote-debugging-port=$cdpPort*") {
            throw "Existing CDP listener PID $edgeRootPid is not the Edge process for the verified isolated Profile."
        }
        $edgeWasVerifiedIsolated = $true
        Write-Output "Reusing verified isolated Edge PID $edgeRootPid on loopback CDP."
    } else {
        $runningForProfile = @(Get-CimInstance Win32_Process -Filter "Name='msedge.exe'" -ErrorAction Stop | Where-Object { $_.CommandLine -and $_.CommandLine.Contains($profilePath,[StringComparison]::OrdinalIgnoreCase) })
        if ($runningForProfile.Count) { throw "The isolated Profile is already open without the dedicated CDP endpoint (PIDs: $($runningForProfile.ProcessId -join ','))." }
        $edgeRoot = Start-Process -FilePath $edgePath -ArgumentList @(
            "--user-data-dir=$profilePath", "--remote-debugging-port=$cdpPort", '--remote-allow-origins=*',
            '--no-first-run', 'about:blank'
        ) -PassThru
        $edgeRootPid = [int]$edgeRoot.Id
        $edgeWasVerifiedIsolated = $true
        Write-Output "Started isolated Edge launcher PID $edgeRootPid; waiting for localhost CDP."
    }
    $edgeDeadline = [DateTime]::UtcNow.AddSeconds(30)
    $versionEndpoint = "http://127.0.0.1:$cdpPort/json/version"
    $versionInfo = $null
    while (-not $versionInfo -and [DateTime]::UtcNow -lt $edgeDeadline) {
        try { $versionInfo = Invoke-RestMethod -Uri $versionEndpoint -TimeoutSec 2 } catch { Start-Sleep -Milliseconds 250 }
    }
    if (-not $versionInfo) { throw 'Dedicated Edge CDP endpoint did not become ready.' }

    if (-not $edgeWasVerifiedIsolated) {
        $profileProcesses = @(Get-CimInstance Win32_Process -Filter "Name='msedge.exe'" -ErrorAction Stop | Where-Object {
            $_.CommandLine -and $_.CommandLine.IndexOf($profilePath,[StringComparison]::OrdinalIgnoreCase) -ge 0
        })
        $browserRoots = @($profileProcesses | Where-Object { $_.CommandLine -notmatch '--type=' })
        if ($browserRoots.Count -ne 1 -or $browserRoots[0].CommandLine -notmatch "--remote-debugging-port=$cdpPort") {
            throw "Expected one isolated Edge browser root for profile/CDP; found $($browserRoots.ProcessId -join ',')."
        }
        $edgeRootPid = [int]$browserRoots[0].ProcessId
        $edgeWasVerifiedIsolated = $true
    }

    # The listener PID was read from netstat and its process command line was matched
    # to this exact isolated userDataDir before Playwright attached.
    $cdpListeners = @(Get-TcpListeners $cdpPort)
    if (-not $cdpListeners.Count -or @($cdpListeners | Where-Object { $_.LocalAddress -notin @('127.0.0.1','::1') }).Count) {
        throw 'The Edge CDP listener is not confined to localhost; stopping before Playwright connects.'
    }
    Write-Output "Unique Edge root PID $edgeRootPid confirmed; CDP listener is loopback-only."

    $env:UI_D_FRONTEND_URL = $frontendUrl
    $env:UI_D_PROFILE_PATH = $profilePath
    $env:UI_D_CDP_ENDPOINT = "http://127.0.0.1:$cdpPort"
    $env:UI_D_SESSION_PATH = $sessionPath
    $env:UI_D_PAGE_STATE_PATH = $pageStatePath
    $env:UI_D_GO_PATH = $goPath
    $env:UI_D_RESULT_PATH = $resultPath
    $env:UI_D_CAPTURE_TAG = 'verified-200'
    $env:UI_D_PLAYWRIGHT_PACKAGE = $playwrightPackage
    $nodeProcess = Start-Process -FilePath $nodeExe -ArgumentList @('"' + $runnerPath + '"') -WorkingDirectory $repo -PassThru -WindowStyle Hidden -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath

    $sessionDeadline = [DateTime]::UtcNow.AddSeconds(60)
    while (-not (Test-Path -LiteralPath $sessionPath) -and [DateTime]::UtcNow -lt $sessionDeadline) {
        if ($nodeProcess.HasExited) { throw "CDP Fixture runner exited before readiness: $((Get-Content $stderrPath -Raw -ErrorAction SilentlyContinue))" }
        Start-Sleep -Milliseconds 250
    }
    if (-not (Test-Path -LiteralPath $sessionPath)) { throw 'Timed out waiting for the same-session CDP Fixture page.' }
    $session = Get-Content -LiteralPath $sessionPath -Raw | ConvertFrom-Json
    if ($session.url -ne "$frontendUrl/purchase-orders" -or $session.edgeVersion -eq 'unknown' -or $session.fixtureAuthMeStatus -ne 200 -or -not $session.fixtureRoutingActive -or $session.cdpEndpoint -ne "http://127.0.0.1:$cdpPort") {
        throw 'CDP session URL, Edge identity, or successful Fixture /auth/me routing assertion failed.'
    }
    Write-Output 'Playwright CDP session ready; /auth/me returned 200 through Fixture API routing.'

    Write-Output 'WAITING FOR USER — CONFIRM EDGE ZOOM IS 200%'
    Write-Output "Edge Version: $($session.edgeVersion); unique root PID: $edgeRootPid; CDP: http://127.0.0.1:$cdpPort; URL: $($session.url)"
    if ($session.initialPage.devicePixelRatio -ge 2.4) { Write-Output '隔離 Profile 已恢復原生 200% 縮放；請開啟「⋯ → 縮放」，確認顯示 200%，讓選單保持顯示並保持視窗開啟，然後回覆「已設定」。' }
    else { Write-Output '在這個獨立 Edge 視窗的「⋯ → 縮放」設為 200%，讓縮放選單保持顯示並保持視窗開啟，然後回覆「已設定」。' }
    Write-Output "Manual confirmation signal path: $signalPath"
    $zoomScreenshotCaptured = $false
    $zoomUiLabel = $null
    $screen = $null
    $manualDeadline = [DateTime]::UtcNow.AddMinutes(30)
    while (-not (Test-Path -LiteralPath $signalPath) -and [DateTime]::UtcNow -lt $manualDeadline) {
        if ($nodeProcess.HasExited) { throw "CDP runner exited while waiting for manual zoom: $((Get-Content $stderrPath -Raw -ErrorAction SilentlyContinue))" }
        $window = Get-UniqueEdgeWindow $edgeRootPid
        $zoomUiLabel = Get-EdgeZoomLabel $window
        if (-not $zoomScreenshotCaptured -and $zoomUiLabel -match '200\s*%' -and (Test-EdgeSettingsMenuOpen $window)) {
            $screen = Capture-NativeScreen $nativeScreenshotPath
            $zoomScreenshotCaptured = $true
            Write-Output "NATIVE_ZOOM_200_CAPTURED=$nativeScreenshotPath; UIA=$zoomUiLabel"
        }
        Start-Sleep -Milliseconds 100
    }
    if (-not (Test-Path -LiteralPath $signalPath)) { throw 'Timed out waiting for user confirmation of native 200% browser zoom.' }
    $manualConfirmation = Get-Content -LiteralPath $signalPath -Raw | ConvertFrom-Json
    if ($manualConfirmation.confirmation -ne '已設定' -or $manualConfirmation.cdpEndpoint -ne "http://127.0.0.1:$cdpPort") { throw 'Manual signal did not match this unique CDP session.' }
    $window = Get-UniqueEdgeWindow $edgeRootPid
    $zoomUiLabel = Get-EdgeZoomLabel $window
    if ($zoomUiLabel -notmatch '200\s*%') { throw "Native Edge UI Automation did not report 200% for the isolated Edge window; UIA label='$zoomUiLabel'." }
    if (-not $zoomScreenshotCaptured) { $screen = Capture-NativeScreen $nativeScreenshotPath; $zoomScreenshotCaptured = $true }
    Write-Output "Native screen captured at $nativeScreenshotPath; UIA reports $zoomUiLabel."
    $review = @{ reviewedAt=[DateTime]::UtcNow.ToString('o'); status='verified-200'; uiaLabel=$zoomUiLabel }
    $afterManualZoom = Get-Content -LiteralPath $pageStatePath -Raw | ConvertFrom-Json
    $zoomGate = [PSCustomObject]@{
        actualPercent = 200
        verification = 'User set the isolated Edge native Settings and more > Zoom control to 200%; read-only Windows UI Automation confirmed its 200% label and same-session CDP page metrics were cross-checked against the pre-zoom baseline.'
        evidenceSource = 'manual native Edge Zoom control plus read-only UI Automation and Windows virtual-screen screenshot; no CSS zoom, transform, deviceScaleFactor, or CDP Page Scale'
        userConfirmedAt = $manualConfirmation.confirmedAt
        screenshotReviewedAt = $review.reviewedAt
        nativeScreenshot = 'screenshots/edge-window-zoom-200-cdp-verified.png'
    }
    $goPayload = @{
        action = 'run-200-percent-checks'
        zoom = $zoomGate
        nativeWindow = @{ processId = $edgeRootPid; hwnd = $null; hwndStatus = 'Unknown; CDP/profile identity is unique, HWND foreground automation is not used.' }
        osDisplay = $screen
        zoomScreenshot = $zoomGate.nativeScreenshot
        beforeZoom = (Get-Content -LiteralPath (Join-Path $evidenceDir 'native-edge-zoom-200-cdp-drawer-failure.json') -Raw | ConvertFrom-Json).beforeZoom
        profilePageAtSessionStart = $session.initialPage
        pageStateBeforeInteraction = $afterManualZoom
    }
    Write-Utf8Json $goPath $goPayload 30

    $testDeadline = [DateTime]::UtcNow.AddMinutes(5)
    while (-not (Test-Path -LiteralPath $resultPath) -and [DateTime]::UtcNow -lt $testDeadline) {
        if ($nodeProcess.HasExited -and -not (Test-Path -LiteralPath $resultPath)) { throw "CDP runner exited without interaction results: $((Get-Content $stderrPath -Raw -ErrorAction SilentlyContinue))" }
        Start-Sleep -Milliseconds 250
    }
    if (-not (Test-Path -LiteralPath $resultPath)) { throw 'Timed out waiting for same-session 200% interactions.' }
    $pageResult = Get-Content -LiteralPath $resultPath -Raw | ConvertFrom-Json
    if ($pageResult.status -ne 'PASS') { throw "Same-session 200% interactions failed: $($pageResult.error)" }

    $runtimeEvidence = [PSCustomObject]@{
        issue = 114
        repository = 'liujiawei-tw/supplypilot'
        testedGitSha = (git -C $repo rev-parse HEAD).Trim()
        branch = (git -C $repo branch --show-current).Trim()
        status = 'PASS'
        observedAt = [DateTime]::UtcNow.ToString('o')
        session = @{ readyAt=$session.readyAt; nodePid=$session.nodePid; profilePath='[redacted]\AppData\Local\Temp\<unique-task-profile>\edge-profile'; url=$session.url; edgeVersion=$session.edgeVersion; playwrightVersion=$session.playwrightVersion; cdpEndpoint=$session.cdpEndpoint; fixtureAuthMeStatus=$session.fixtureAuthMeStatus; fixtureRoutingActive=$session.fixtureRoutingActive; viewportOption=$session.viewportOption; initialPage=$session.initialPage }
        nativeBrowser = @{ processId = $edgeRootPid; cdpEndpoint = "http://127.0.0.1:$cdpPort"; hwnd = $null; profileIsolated = $true }
        nativeZoom = $zoomGate
        osDisplay = $screen
        page = $pageResult
        nativeScreenshot = 'screenshots/edge-window-zoom-200-cdp-verified.png'
        playwrightScreenshots = @($pageResult.cases | ForEach-Object { $_.checks.screenshot; $_.checks.confirmScreenshot } | Where-Object { $_ } | Select-Object -Unique)
        fixture = @{ mode = 'Playwright API route'; backendIntegration = $false; authMeStatus = $session.fixtureAuthMeStatus; role = $pageResult.role }
    }
    Write-Utf8Json $finalEvidencePath $runtimeEvidence 40
    Write-Output 'NATIVE_EDGE_ZOOM_CDP=PASS'
    Write-Output "EDGE_PID=$edgeRootPid"
    Write-Output "EDGE_VERSION=$($session.edgeVersion)"
    Write-Output "URL=$($session.url)"
    Write-Output "EVIDENCE=$finalEvidencePath"
} catch {
    $message = $_.Exception.Message
    Write-Warning $message
    if (Test-Path -LiteralPath $resultPath) {
        try {
            $failedPage = Get-Content -LiteralPath $resultPath -Raw | ConvertFrom-Json
            $failureEvidence = [PSCustomObject]@{
                issue = 114
                repository = 'liujiawei-tw/supplypilot'
                testedGitSha = (git -C $repo rev-parse HEAD).Trim()
                branch = (git -C $repo branch --show-current).Trim()
                status = $failedPage.status
                failure = $message
                session = if (Test-Path -LiteralPath $sessionPath) { Get-Content -LiteralPath $sessionPath -Raw | ConvertFrom-Json } else { $null }
                page = $failedPage
                nativeScreenshot = if (Test-Path -LiteralPath $nativeScreenshotPath) { 'screenshots/edge-window-zoom-200-cdp-verified.png' } else { $null }
            }
            Write-Utf8Json $finalEvidencePath $failureEvidence 40
        } catch { Write-Warning "Could not persist failure evidence: $($_.Exception.Message)" }
    }
    if ($nodeProcess -and -not $nodeProcess.HasExited) { Stop-Process -Id $nodeProcess.Id -Force -ErrorAction SilentlyContinue }
    throw
} finally {
    Remove-Item Env:VITE_API_BASE_URL,Env:UI_D_FRONTEND_URL,Env:UI_D_PROFILE_PATH,Env:UI_D_CDP_ENDPOINT,Env:UI_D_SESSION_PATH,Env:UI_D_PAGE_STATE_PATH,Env:UI_D_GO_PATH,Env:UI_D_RESULT_PATH,Env:UI_D_PLAYWRIGHT_PACKAGE,Env:UI_D_CAPTURE_TAG,Env:UI_D_REUSE_PROFILE_PATH -ErrorAction SilentlyContinue
    if ($nodeProcess -and -not $nodeProcess.HasExited) { Stop-Process -Id $nodeProcess.Id -Force -ErrorAction SilentlyContinue }
    if ($viteStartedHere -and $viteProcess -and -not $viteProcess.HasExited) { Stop-Process -Id $viteProcess.Id -Force -ErrorAction SilentlyContinue }
    if ($profilePath -and (Test-Path -LiteralPath $profilePath)) {
        if ($null -eq $finalEvidencePath -or -not (Test-Path -LiteralPath $finalEvidencePath)) {
            Write-Output "Retaining temporary diagnostics and isolated Edge profile for a same-profile recheck at $runtimeRoot."
            $profilePath = $null
        }
    }
    if ($profilePath -and (Test-Path -LiteralPath $profilePath)) {
        $finalStatus = if (Test-Path -LiteralPath $finalEvidencePath) { (Get-Content -LiteralPath $finalEvidencePath -Raw | ConvertFrom-Json).status } else { $null }
        if ($finalStatus -eq 'PASS') {
            if ($edgeWasVerifiedIsolated -and $edgeRootPid) { Stop-IsolatedEdgeTree $edgeRootPid }
            $resolvedProfile = (Resolve-Path -LiteralPath $profilePath).Path
            if (-not (Test-IsTaskTempProfile $resolvedProfile)) { throw 'Refusing to remove an isolated Edge profile outside the expected Temp location.' }
            $profileRuntime = Split-Path -Parent $resolvedProfile
            Remove-Item -LiteralPath $profileRuntime -Recurse -Force
            if ($reuseProfilePath) {
                $currentRuntime = (Resolve-Path -LiteralPath $runtimeRoot).Path
                if ((Test-IsTaskTempProfile (Join-Path $currentRuntime 'edge-profile'))) { Remove-Item -LiteralPath $currentRuntime -Recurse -Force }
            }
        } else { Write-Output "Retaining this isolated profile for a safe same-profile recheck: $profilePath" }
    }
}
