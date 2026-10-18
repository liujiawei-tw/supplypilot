[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$evidenceDir = $PSScriptRoot
$screenshots = Join-Path $evidenceDir 'screenshots'
$null = New-Item -ItemType Directory -Path $screenshots -Force
$tag = 'ui-d-edge-zoom-' + [Guid]::NewGuid().ToString('N')
$runtimeRoot = Join-Path $evidenceDir ('.runtime-' + $tag)
$profilePath = Join-Path $runtimeRoot 'edge-profile'
$sessionPath = Join-Path $runtimeRoot 'session.json'
$pageStatePath = Join-Path $runtimeRoot 'page-state.json'
$goPath = Join-Path $runtimeRoot 'go.json'
$resultPath = Join-Path $runtimeRoot 'page-result.json'
$stdoutPath = Join-Path $runtimeRoot 'node.stdout.log'
$stderrPath = Join-Path $runtimeRoot 'node.stderr.log'
$runnerPath = Join-Path $evidenceDir 'run-native-edge-zoom-200.mjs'
$frontendUrl = 'http://127.0.0.1:5181'
$nodeProc = $null
$browserRootPid = $null
$targetHwnd = [IntPtr]::Zero
$nativeWindows = @()
$zoomMenuNames100 = @()
$zoomMenuNames200 = @()
$nativeBeforeScreenshot = Join-Path $screenshots 'edge-window-zoom-100-native.png'
$nativeAfterScreenshot = Join-Path $screenshots 'edge-window-zoom-200-native.png'
$finalEvidencePath = Join-Path $evidenceDir 'native-edge-zoom-200-final.json'

Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes
Add-Type -AssemblyName System.Drawing

$nativeCode = @'
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using System.Text;

public static class UiDWin32 {
    public sealed class WindowInfo {
        public long Hwnd { get; set; }
        public int ProcessId { get; set; }
        public string Title { get; set; }
        public int Left { get; set; }
        public int Top { get; set; }
        public int Right { get; set; }
        public int Bottom { get; set; }
        public int Width { get { return Right - Left; } }
        public int Height { get { return Bottom - Top; } }
    }

    public delegate bool EnumWindowsProc(IntPtr hwnd, IntPtr lParam);

    [StructLayout(LayoutKind.Sequential)] private struct RECT { public int Left, Top, Right, Bottom; }
    [StructLayout(LayoutKind.Sequential)] private struct KEYBDINPUT { public ushort wVk, wScan; public uint dwFlags, time; public UIntPtr dwExtraInfo; }
    [StructLayout(LayoutKind.Sequential)] private struct MOUSEINPUT { public int dx, dy; public uint mouseData, dwFlags, time; public UIntPtr dwExtraInfo; }
    [StructLayout(LayoutKind.Explicit)] private struct INPUTUNION { [FieldOffset(0)] public KEYBDINPUT ki; [FieldOffset(0)] public MOUSEINPUT mi; }
    [StructLayout(LayoutKind.Sequential)] private struct INPUT { public uint type; public INPUTUNION U; }

    [DllImport("user32.dll")] private static extern bool EnumWindows(EnumWindowsProc callback, IntPtr lParam);
    [DllImport("user32.dll")] private static extern bool IsWindowVisible(IntPtr hwnd);
    [DllImport("user32.dll", CharSet=CharSet.Unicode)] private static extern int GetWindowText(IntPtr hwnd, StringBuilder text, int maxCount);
    [DllImport("user32.dll")] private static extern uint GetWindowThreadProcessId(IntPtr hwnd, out uint processId);
    [DllImport("user32.dll")] private static extern bool GetWindowRect(IntPtr hwnd, out RECT rect);
    [DllImport("user32.dll")] private static extern bool SetForegroundWindow(IntPtr hwnd);
    [DllImport("user32.dll")] private static extern bool BringWindowToTop(IntPtr hwnd);
    [DllImport("user32.dll")] private static extern IntPtr SetActiveWindow(IntPtr hwnd);
    [DllImport("user32.dll")] private static extern bool AttachThreadInput(uint attach, uint attachTo, bool attachState);
    [DllImport("user32.dll")] private static extern bool AllowSetForegroundWindow(uint processId);
    [DllImport("user32.dll")] private static extern bool ShowWindow(IntPtr hwnd, int command);
    [DllImport("user32.dll")] private static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] private static extern uint GetDpiForWindow(IntPtr hwnd);
    [DllImport("user32.dll")] private static extern uint GetDpiForSystem();
    [DllImport("user32.dll")] private static extern int GetSystemMetrics(int index);
    [DllImport("user32.dll", SetLastError=true)] private static extern uint SendInput(uint count, INPUT[] inputs, int size);
    private const uint INPUT_KEYBOARD = 1;
    private const uint KEYEVENTF_KEYUP = 0x0002;

    public static List<WindowInfo> GetVisibleWindows() {
        var output = new List<WindowInfo>();
        EnumWindowsProc callback = (hwnd, ignored) => {
            if (!IsWindowVisible(hwnd)) return true;
            var title = new StringBuilder(1024);
            GetWindowText(hwnd, title, title.Capacity);
            uint pid; GetWindowThreadProcessId(hwnd, out pid);
            RECT rect;
            if (!GetWindowRect(hwnd, out rect)) return true;
            output.Add(new WindowInfo { Hwnd=hwnd.ToInt64(), ProcessId=(int)pid, Title=title.ToString(),
                Left=rect.Left, Top=rect.Top, Right=rect.Right, Bottom=rect.Bottom });
            return true;
        };
        EnumWindows(callback, IntPtr.Zero);
        GC.KeepAlive(callback);
        return output;
    }

    public static long ForegroundHwnd() { return GetForegroundWindow().ToInt64(); }
    public static bool Activate(long hwnd) {
        var handle = new IntPtr(hwnd);
        ShowWindow(handle, 9); // SW_RESTORE
        ShowWindow(handle, 3); // SW_MAXIMIZE
        uint targetPid; var targetThread = GetWindowThreadProcessId(handle, out targetPid);
        var foreground = GetForegroundWindow();
        uint foregroundPid; var foregroundThread = GetWindowThreadProcessId(foreground, out foregroundPid);
        AllowSetForegroundWindow(targetPid);
        var attached = foregroundThread != 0 && targetThread != 0 && AttachThreadInput(foregroundThread, targetThread, true);
        try {
            BringWindowToTop(handle);
            SetActiveWindow(handle);
            SetForegroundWindow(handle);
        } finally {
            if (attached) AttachThreadInput(foregroundThread, targetThread, false);
        }
        return GetForegroundWindow() == handle;
    }
    public static uint WindowDpi(long hwnd) { return GetDpiForWindow(new IntPtr(hwnd)); }
    public static uint SystemDpi() { return GetDpiForSystem(); }
    public static int VirtualLeft() { return GetSystemMetrics(76); }
    public static int VirtualTop() { return GetSystemMetrics(77); }
    public static int VirtualWidth() { return GetSystemMetrics(78); }
    public static int VirtualHeight() { return GetSystemMetrics(79); }

    private static void Key(ushort vk, bool keyUp) {
        var input = new INPUT { type=INPUT_KEYBOARD, U=new INPUTUNION { ki=new KEYBDINPUT {
            wVk=vk, wScan=0, dwFlags=keyUp ? KEYEVENTF_KEYUP : 0, time=0, dwExtraInfo=UIntPtr.Zero
        } } };
        var sent = SendInput(1, new INPUT[] { input }, Marshal.SizeOf(typeof(INPUT)));
        if (sent != 1) throw new InvalidOperationException("SendInput failed for virtual key " + vk + ", error=" + Marshal.GetLastWin32Error());
    }
    private static void Tap(ushort vk) { Key(vk, false); Key(vk, true); }
    public static void PressCtrlZero() { Key(0x11,false); Tap(0x30); Key(0x11,true); }
    public static void PressCtrlPlus() { Key(0x11,false); Key(0x10,false); Tap(0xBB); Key(0x10,true); Key(0x11,true); }
    public static void PressAltF() { Key(0x12,false); Tap(0x46); Key(0x12,true); }
    public static void PressEscape() { Tap(0x1B); }
}
'@
Add-Type -TypeDefinition $nativeCode -Language CSharp

function Read-JsonFile([string]$Path) {
    for ($attempt = 0; $attempt -lt 12; $attempt++) {
        try { return (Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json) } catch { Start-Sleep -Milliseconds 100 }
    }
    throw "Unable to read valid JSON from $Path"
}

function Write-Utf8Json([string]$Path, $Value, [int]$Depth = 30) {
    $json = $Value | ConvertTo-Json -Depth $Depth
    [IO.File]::WriteAllText($Path, $json + [Environment]::NewLine, [Text.UTF8Encoding]::new($false))
}

function Save-TargetWindowScreenshot([string]$Path, [long]$Hwnd) {
    $row = [UiDWin32]::GetVisibleWindows() | Where-Object { $_.Hwnd -eq $Hwnd }
    if (@($row).Count -ne 1) { throw 'Target Edge window disappeared or became ambiguous before screenshot.' }
    if ([UiDWin32]::ForegroundHwnd() -ne $Hwnd) { throw 'Target Edge window is no longer foreground; refusing screenshot/input.' }
    $width = $row.Width; $height = $row.Height
    if ($width -lt 400 -or $height -lt 300) { throw 'Target Edge window rect is unexpectedly small.' }
    $bitmap = New-Object System.Drawing.Bitmap($width, $height)
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    try {
        $graphics.CopyFromScreen($row.Left, $row.Top, 0, 0, (New-Object System.Drawing.Size($width, $height)), [System.Drawing.CopyPixelOperation]::SourceCopy)
        $bitmap.Save($Path, [System.Drawing.Imaging.ImageFormat]::Png)
    } finally { $graphics.Dispose(); $bitmap.Dispose() }
}

function Get-EdgeUiAutomationText([long]$Hwnd) {
    $root = [System.Windows.Automation.AutomationElement]::FromHandle([IntPtr]$Hwnd)
    $items = $root.FindAll([System.Windows.Automation.TreeScope]::Descendants, [System.Windows.Automation.Condition]::TrueCondition)
    $names = [System.Collections.Generic.List[string]]::new()
    for ($i = 0; $i -lt $items.Count; $i++) {
        try {
            $name = $items.Item($i).Current.Name
            if ($name -and $name.Trim()) { $names.Add($name.Trim()) }
        } catch { }
    }
    return @($names | Select-Object -Unique)
}

function Wait-PageStateChange([string]$AfterTimestamp) {
    $until = [DateTime]::UtcNow.AddSeconds(5)
    do {
        $state = Read-JsonFile $pageStatePath
        if ([string]$state.measuredAt -ne $AfterTimestamp) { return $state }
        Start-Sleep -Milliseconds 150
    } while ([DateTime]::UtcNow -lt $until)
    throw 'Playwright metric bridge did not publish a fresh state after the native keypress.'
}

function Get-ProfileProcessTree([int]$RootPid, $AllProcesses) {
    $ids = [System.Collections.Generic.HashSet[int]]::new()
    [void]$ids.Add($RootPid)
    do {
        $changed = $false
        foreach ($proc in $AllProcesses) {
            if ($ids.Contains([int]$proc.ParentProcessId) -and $ids.Add([int]$proc.ProcessId)) { $changed = $true }
        }
    } while ($changed)
    return $ids
}

try {
    if (-not (Test-Path -LiteralPath (Join-Path $screenshots 'po-modal-375.png'))) {
        throw 'Expected prior 375px Fixture evidence is missing; refusing an incomplete UI-D session.'
    }
    $listener = Get-NetTCPConnection -LocalAddress '127.0.0.1' -LocalPort 5181 -State Listen -ErrorAction Stop
    $serverProcess = Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)" -ErrorAction Stop
    if ($serverProcess.Name -ne 'node.exe' -or $serverProcess.CommandLine -notmatch 'supplypilot\\frontend\\node_modules.*vite' -or $serverProcess.CommandLine -notmatch '--port 5181') {
        throw 'Port 5181 is not served by this repository Vite fixture process.'
    }
    $health = Invoke-WebRequest -Uri "$frontendUrl/purchase-orders" -TimeoutSec 10 -UseBasicParsing
    if ($health.StatusCode -ne 200 -or $health.Content -notmatch 'SupplyPilot') { throw 'Controlled SupplyPilot UI health check failed.' }

    $playwrightPackage = Get-ChildItem 'C:\Users\z0976\AppData\Local\npm-cache\_npx' -Directory -ErrorAction Stop |
        ForEach-Object { Join-Path $_.FullName 'node_modules\playwright\package.json' } |
        Where-Object { Test-Path -LiteralPath $_ } |
        Where-Object { (Get-Content -LiteralPath $_ -Raw | ConvertFrom-Json).version -eq '1.64.1' } |
        Select-Object -First 1
    if (-not $playwrightPackage) { throw 'Cached Playwright 1.64.1 package not found; no install will be attempted.' }
    $playwrightPackage = Split-Path -Parent $playwrightPackage

    $null = New-Item -ItemType Directory -Path $runtimeRoot -Force
    $env:UI_D_FRONTEND_URL = $frontendUrl
    $env:UI_D_PROFILE_PATH = $profilePath
    $env:UI_D_SESSION_PATH = $sessionPath
    $env:UI_D_PAGE_STATE_PATH = $pageStatePath
    $env:UI_D_GO_PATH = $goPath
    $env:UI_D_RESULT_PATH = $resultPath
    $env:UI_D_PLAYWRIGHT_PACKAGE = $playwrightPackage
    $nodeExe = (Get-Command node.exe -ErrorAction Stop).Source
    $nodeProc = Start-Process -FilePath $nodeExe -ArgumentList @('"' + $runnerPath + '"') -WorkingDirectory $repo -PassThru -WindowStyle Hidden -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath
    $readyUntil = [DateTime]::UtcNow.AddSeconds(90)
    while (-not (Test-Path -LiteralPath $sessionPath) -and [DateTime]::UtcNow -lt $readyUntil) {
        if ($nodeProc.HasExited) { throw "Playwright runner exited before readiness. $((Get-Content $stderrPath -Raw -ErrorAction SilentlyContinue))" }
        Start-Sleep -Milliseconds 250
    }
    if (-not (Test-Path -LiteralPath $sessionPath)) { throw 'Timed out waiting for isolated Edge/Playwright readiness.' }
    $session = Read-JsonFile $sessionPath
    if ($session.url -ne "$frontendUrl/purchase-orders" -or $session.edgeVersion -eq 'unknown' -or $session.viewportOption -ne $null) {
        throw 'Playwright session URL, Edge identity, or viewport=null assertion failed.'
    }
    if (-not (Test-Path -LiteralPath $profilePath)) { throw 'Unique Edge profile directory was not created.' }

    $allProcesses = @(Get-CimInstance Win32_Process -ErrorAction Stop)
    $profileMatches = @($allProcesses | Where-Object { $_.Name -eq 'msedge.exe' -and $_.CommandLine -and $_.CommandLine.IndexOf($profilePath, [StringComparison]::OrdinalIgnoreCase) -ge 0 })
    $browserRoots = @($profileMatches | Where-Object { $_.CommandLine -notmatch '--type=' })
    if ($browserRoots.Count -ne 1) {
        throw "Expected exactly one main Edge process for unique profile; found $($browserRoots.Count). No native input was sent."
    }
    $browserRootPid = [int]$browserRoots[0].ProcessId
    $processTree = Get-ProfileProcessTree $browserRootPid $allProcesses

    $visibleCandidates = @()
    $windowDeadline = [DateTime]::UtcNow.AddSeconds(30)
    do {
        $windows = [UiDWin32]::GetVisibleWindows()
        $visibleCandidates = @($windows | Where-Object { $processTree.Contains([int]$_.ProcessId) -and $_.Width -gt 400 -and $_.Height -gt 300 -and $_.Title })
        if ($visibleCandidates.Count -eq 1) { break }
        if ($visibleCandidates.Count -gt 1) { break }
        Start-Sleep -Milliseconds 300
        $allProcesses = @(Get-CimInstance Win32_Process -ErrorAction Stop)
        $processTree = Get-ProfileProcessTree $browserRootPid $allProcesses
    } while ([DateTime]::UtcNow -lt $windowDeadline)
    if ($visibleCandidates.Count -ne 1) {
        $details = ($visibleCandidates | ForEach-Object { "pid=$($_.ProcessId), hwnd=0x$('{0:X}' -f $_.Hwnd), title='$($_.Title)', rect=$($_.Width)x$($_.Height)" }) -join '; '
        throw "Expected exactly one visible Edge HWND for the isolated process tree; found $($visibleCandidates.Count). $details No native input was sent."
    }
    $targetHwnd = [long]$visibleCandidates[0].Hwnd
    if ($visibleCandidates[0].Title -notmatch 'SupplyPilot|Purchase Orders|採購') { throw 'Unique Edge HWND title does not identify SupplyPilot UI.' }

    $null = [UiDWin32]::Activate($targetHwnd)
    Start-Sleep -Milliseconds 800
    if ([UiDWin32]::ForegroundHwnd() -ne $targetHwnd) { throw "Could not make the uniquely identified Edge HWND foreground (target=0x$('{0:X}' -f $targetHwnd), foreground=0x$('{0:X}' -f [UiDWin32]::ForegroundHwnd()))." }
    $windowBeforeZoom = [UiDWin32]::GetVisibleWindows() | Where-Object { $_.Hwnd -eq $targetHwnd }
    if (@($windowBeforeZoom).Count -ne 1) { throw 'Target HWND disappeared after activation.' }
    Save-TargetWindowScreenshot $nativeBeforeScreenshot $targetHwnd

    $stateBeforeReset = Read-JsonFile $pageStatePath
    [UiDWin32]::PressCtrlZero()
    Start-Sleep -Milliseconds 800
    $beforeZoom = Read-JsonFile $pageStatePath
    $ratioAfterReset = [double]$beforeZoom.devicePixelRatio / [double]$stateBeforeReset.devicePixelRatio
    if ([Math]::Abs($ratioAfterReset - 1.0) -gt 0.03) { throw 'Ctrl+0 changed the page DPR baseline unexpectedly.' }

    if ([UiDWin32]::ForegroundHwnd() -ne $targetHwnd) { throw 'Edge lost foreground before checking initial browser zoom.' }
    [UiDWin32]::PressAltF()
    Start-Sleep -Milliseconds 900
    $zoomMenuNames100 = @(Get-EdgeUiAutomationText $targetHwnd)
    Save-TargetWindowScreenshot $nativeBeforeScreenshot $targetHwnd
    $uiZoom100 = @($zoomMenuNames100 | Where-Object { $_ -match '(?<!\d)100\s*%' })
    if ($uiZoom100.Count -eq 0) {
        throw 'Edge Zoom menu did not expose 100% through UI Automation; screenshot saved for diagnosis and no zoom-in key was sent.'
    }
    [UiDWin32]::PressEscape()
    Start-Sleep -Milliseconds 200

    $zoomSamples = [System.Collections.Generic.List[object]]::new()
    $current = Read-JsonFile $pageStatePath
    $baselineDpr = [double]$current.devicePixelRatio
    for ($i = 0; $i -lt 12; $i++) {
        if ([UiDWin32]::ForegroundHwnd() -ne $targetHwnd) { throw 'Foreground HWND changed during zoom; stopping without sending more input.' }
        $oldTimestamp = [string]$current.measuredAt
        [UiDWin32]::PressCtrlPlus()
        $current = Wait-PageStateChange $oldTimestamp
        $relativeZoom = [double]$current.devicePixelRatio / $baselineDpr
        $zoomSamples.Add([PSCustomObject]@{ step = $i + 1; at = $current.measuredAt; devicePixelRatio = $current.devicePixelRatio; ratioFrom100Percent = $relativeZoom; cssViewport = @{ width = $current.innerWidth; height = $current.innerHeight } })
        if ($relativeZoom -gt 2.02) { throw "Native zoom passed 200% target (DPR ratio $relativeZoom); refusing to claim exact 200%." }
        if ([Math]::Abs($relativeZoom - 2.0) -lt 0.025) { break }
    }
    if ([Math]::Abs(([double]$current.devicePixelRatio / $baselineDpr) - 2.0) -ge 0.025) {
        throw 'Native Edge zoom did not reach a DPR ratio consistent with exactly 200% within 12 increments.'
    }

    if ([UiDWin32]::ForegroundHwnd() -ne $targetHwnd) { throw 'Edge lost foreground before checking final browser zoom.' }
    [UiDWin32]::PressAltF()
    Start-Sleep -Milliseconds 900
    $zoomMenuNames200 = @(Get-EdgeUiAutomationText $targetHwnd)
    Save-TargetWindowScreenshot $nativeAfterScreenshot $targetHwnd
    $uiZoom200 = @($zoomMenuNames200 | Where-Object { $_ -match '(?<!\d)200\s*%' })
    if ($uiZoom200.Count -eq 0) {
        throw 'Edge Zoom menu did not expose 200% through UI Automation; native screenshot saved for visual confirmation.'
    }

    $windowAfterZoom = [UiDWin32]::GetVisibleWindows() | Where-Object { $_.Hwnd -eq $targetHwnd }
    if (@($windowAfterZoom).Count -ne 1 -or [UiDWin32]::ForegroundHwnd() -ne $targetHwnd) { throw 'Edge HWND/foreground changed during native zoom.' }
    $nativeWindow = [PSCustomObject]@{
        processId = $browserRootPid
        processTreeIds = @($processTree | Sort-Object)
        hwnd = ('0x{0:X}' -f $targetHwnd)
        title = $windowAfterZoom.Title
        rect = @{ left = $windowAfterZoom.Left; top = $windowAfterZoom.Top; right = $windowAfterZoom.Right; bottom = $windowAfterZoom.Bottom; width = $windowAfterZoom.Width; height = $windowAfterZoom.Height }
        dpi = [UiDWin32]::WindowDpi($targetHwnd)
        dpiScale = [Math]::Round(([UiDWin32]::WindowDpi($targetHwnd) / 96.0), 3)
        systemDpi = [UiDWin32]::SystemDpi()
        foregroundHwnd = ('0x{0:X}' -f [UiDWin32]::ForegroundHwnd())
    }
    $osDisplay = @{ virtualScreen = @{ left = [UiDWin32]::VirtualLeft(); top = [UiDWin32]::VirtualTop(); width = [UiDWin32]::VirtualWidth(); height = [UiDWin32]::VirtualHeight() }; windowDpi = $nativeWindow.dpi; windowDpiScale = $nativeWindow.dpiScale; systemDpi = $nativeWindow.systemDpi }
    $afterZoom = Read-JsonFile $pageStatePath
    $zoom = [PSCustomObject]@{
        actualPercent = 200
        initialPercent = 100
        initialZoomUiText = @($zoomMenuNames100 | Where-Object { $_ -match '100\s*%|Zoom|縮放|設定及其他' })
        finalZoomUiText = @($zoomMenuNames200 | Where-Object { $_ -match '200\s*%|Zoom|縮放|設定及其他' })
        zoomSamples = @($zoomSamples)
        before = $beforeZoom
        after = $afterZoom
        ratio = [Math]::Round(([double]$afterZoom.devicePixelRatio / [double]$beforeZoom.devicePixelRatio), 4)
        nativeScreenshots = @('screenshots/edge-window-zoom-100-native.png', 'screenshots/edge-window-zoom-200-native.png')
        verification = 'Edge native Zoom menu UIA labels show 100% and 200%; page devicePixelRatio ratio cross-checks approximately 2.0; screenshots include Edge chrome and Zoom menu.'
    }

    Write-Output 'Native Edge zoom measured as 200%. Browser Chrome screenshot is saved. Inspect the 200% screenshot now; enter CONFIRM200 only if the Edge Zoom menu visibly reads 200%.'
    $visualConfirmation = Read-Host 'Visual confirmation'
    if ($visualConfirmation -cne 'CONFIRM200') { throw 'Visual confirmation of Edge 200% screenshot was not provided; stopped before page interaction checks.' }

    [UiDWin32]::PressEscape()
    Start-Sleep -Milliseconds 250
    $afterMenuClosed = Read-JsonFile $pageStatePath
    $nativeWindow.foregroundHwnd = '0x{0:X}' -f [UiDWin32]::ForegroundHwnd()
    $goPayload = @{ action = 'run-200-percent-checks'; zoom = $zoom; nativeWindow = $nativeWindow; osDisplay = $osDisplay; zoomScreenshot = 'screenshots/edge-window-zoom-200-native.png'; beforeZoom = $beforeZoom; pageStateBeforeInteraction = $afterMenuClosed }
    Write-Utf8Json $goPath $goPayload 30

    $testUntil = [DateTime]::UtcNow.AddMinutes(5)
    while (-not (Test-Path -LiteralPath $resultPath) -and [DateTime]::UtcNow -lt $testUntil) {
        if ($nodeProc.HasExited -and -not (Test-Path -LiteralPath $resultPath)) { throw "Playwright runner exited without result. $((Get-Content $stderrPath -Raw -ErrorAction SilentlyContinue))" }
        Start-Sleep -Milliseconds 250
    }
    if (-not (Test-Path -LiteralPath $resultPath)) { throw 'Timed out waiting for 200% UI interaction checks.' }
    $pageResult = Read-JsonFile $resultPath
    if ($pageResult.status -ne 'PASS') { throw "200% Playwright interactions failed: $($pageResult.error)" }

    $runtimeEvidence = [PSCustomObject]@{
        issue = 114
        repository = 'liujiawei-tw/supplypilot'
        testedGitSha = (git -C $repo rev-parse HEAD).Trim()
        branch = (git -C $repo branch --show-current).Trim()
        observedAt = [DateTime]::UtcNow.ToString('o')
        status = 'PASS'
        source = 'Microsoft Edge native zoom UI + Win32 HWND/PID verification + Playwright DOM measurement; controlled API Fixture; no backend or r4 data'
        session = $session
        nativeWindow = $nativeWindow
        osDisplay = $osDisplay
        zoom = $zoom
        page = $pageResult
        nativeScreenshots = @('screenshots/edge-window-zoom-100-native.png', 'screenshots/edge-window-zoom-200-native.png')
        playwrightScreenshots = @($pageResult.cases | ForEach-Object { $_.checks.screenshot; $_.checks.confirmScreenshot } | Where-Object { $_ } | Select-Object -Unique)
        runtimeProfile = @{ path = $profilePath; isolated = $true; removedAfterRun = $false }
    }
    Write-Utf8Json $finalEvidencePath $runtimeEvidence 40

    if ($nodeProc -and -not $nodeProc.HasExited) { Wait-Process -Id $nodeProc.Id -Timeout 15 -ErrorAction SilentlyContinue }
    Start-Sleep -Milliseconds 500
    $remainingProfileProcesses = @(Get-CimInstance Win32_Process | Where-Object { $_.Name -eq 'msedge.exe' -and $_.CommandLine -and $_.CommandLine.IndexOf($profilePath, [StringComparison]::OrdinalIgnoreCase) -ge 0 })
    foreach ($process in $remainingProfileProcesses) { Stop-Process -Id ([int]$process.ProcessId) -Force -ErrorAction Stop }
    if (Test-Path -LiteralPath $profilePath) {
        $resolvedRuntime = (Resolve-Path -LiteralPath $runtimeRoot).Path
        if (-not $resolvedRuntime.StartsWith($repo + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Refusing to remove Edge profile outside repository workspace.' }
        Remove-Item -LiteralPath $resolvedRuntime -Recurse -Force
    }
    $evidence = Get-Content -LiteralPath $finalEvidencePath -Raw | ConvertFrom-Json
    $evidence.runtimeProfile.removedAfterRun = $true
    Write-Utf8Json $finalEvidencePath $evidence 40
    Write-Output "NATIVE_EDGE_ZOOM=PASS"
    Write-Output "EDGE_PID=$browserRootPid"
    Write-Output "EDGE_HWND=$($nativeWindow.hwnd)"
    Write-Output "EDGE_VERSION=$($session.edgeVersion)"
    Write-Output "URL=$($session.url)"
    Write-Output "DPR_RATIO=$($zoom.ratio)"
    Write-Output "EVIDENCE=$finalEvidencePath"
} catch {
    $message = $_.Exception.Message
    Write-Error $message
    if ($nodeProc -and -not (Test-Path -LiteralPath $goPath)) {
        try { Write-Utf8Json $goPath @{ action = 'abort'; reason = $message } 5 } catch { }
    }
    if ($nodeProc -and -not $nodeProc.HasExited) { Wait-Process -Id $nodeProc.Id -Timeout 15 -ErrorAction SilentlyContinue }
    if (Test-Path -LiteralPath $profilePath) {
        try {
            $profileProcesses = @(Get-CimInstance Win32_Process -ErrorAction Stop | Where-Object { $_.Name -eq 'msedge.exe' -and $_.CommandLine -and $_.CommandLine.IndexOf($profilePath, [StringComparison]::OrdinalIgnoreCase) -ge 0 })
            foreach ($process in $profileProcesses) { Stop-Process -Id ([int]$process.ProcessId) -Force -ErrorAction Stop }
            if (Test-Path -LiteralPath $profilePath) {
                $resolvedProfile = (Resolve-Path -LiteralPath $profilePath).Path
                if (-not $resolvedProfile.StartsWith($repo + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Refusing to remove Edge profile outside repository workspace.' }
                Remove-Item -LiteralPath $resolvedProfile -Recurse -Force
            }
        } catch { Write-Warning "Isolated Edge profile cleanup needs follow-up: $($_.Exception.Message)" }
        Write-Warning "Diagnostic runtime files retained at $runtimeRoot"
    }
    throw
} finally {
    Remove-Item Env:UI_D_FRONTEND_URL,Env:UI_D_PROFILE_PATH,Env:UI_D_SESSION_PATH,Env:UI_D_PAGE_STATE_PATH,Env:UI_D_GO_PATH,Env:UI_D_RESULT_PATH,Env:UI_D_PLAYWRIGHT_PACKAGE -ErrorAction SilentlyContinue
}
