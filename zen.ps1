# Zen TV — make an Android TV / Google TV faster and ad-free, safely and reversibly.
# Windows. Double-click zen.bat (or run: powershell -ExecutionPolicy Bypass -File zen.ps1)
#
# Everything is reversible: apps are disabled (never uninstalled) and every change is logged
# in a backup folder that "Restore" uses to put the TV back exactly as it was.

param([switch]$Restore)
$ErrorActionPreference = 'Continue'
$Root = $PSScriptRoot
Set-Location $Root
$Conf = Join-Path $Root '.zen-tv.conf'
$Backups = Join-Path $Root 'backups'
$Profiles = Join-Path $Root 'profiles'
$Apk = if ($env:ZEN_APK) { $env:ZEN_APK } else { Join-Path $Root 'dist\zen-home.apk' }
$Svc = 'com.zen.tvhome/com.zen.tvhome.HomeKeyService'
$script:Adb = $null
$script:Serial = $null

function Say($m) { Write-Host $m }
function Ok($m) { Write-Host "✔ $m" -ForegroundColor Green }
function Warn($m) { Write-Host "! $m" -ForegroundColor Yellow }
function Err($m) { Write-Host "✘ $m" -ForegroundColor Red }
function Title($m) { Write-Host ""; Write-Host "== $m ==" -ForegroundColor Cyan }
function Ask($m) { Read-Host $m }
function Yes($m) { (Read-Host "$m [y/N]") -match '^(y|yes|o|oui)$' }

function AdbRaw { & $script:Adb @args 2>&1 | ForEach-Object { "$_".TrimEnd("`r") } }
function AdbS { & $script:Adb -s $script:Serial @args 2>&1 | ForEach-Object { "$_".TrimEnd("`r") } }
function Sh { AdbS shell @args }
function Pkgs($flag) { Sh pm list packages $flag | ForEach-Object { $_ -replace '^package:', '' } | Where-Object { $_ } | Sort-Object }

# ---------------------------------------------------------------- adb

function Ensure-Adb {
  $local = Join-Path $Root 'tools\platform-tools\adb.exe'
  if (Test-Path $local) { $script:Adb = $local; return }
  $cmd = Get-Command adb -ErrorAction SilentlyContinue
  if ($cmd) { $script:Adb = $cmd.Source; return }
  Warn "adb (Android's official TV/phone connection tool) is not installed."
  $url = 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip'
  Say "It can be downloaded from Google: $url (~10 MB)"
  if (-not (Yes "Download it into $Root\tools ?")) { Err 'adb is required.'; exit 1 }
  New-Item -ItemType Directory -Force (Join-Path $Root 'tools') | Out-Null
  $zip = Join-Path $Root 'tools\pt.zip'
  [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
  Invoke-WebRequest -Uri $url -OutFile $zip -UseBasicParsing
  Expand-Archive -Path $zip -DestinationPath (Join-Path $Root 'tools') -Force
  Remove-Item $zip
  $script:Adb = $local
  Ok 'adb installed'
}

# ---------------------------------------------------------------- finding the TV

function Get-LocalPrefix {
  $route = Get-NetRoute -DestinationPrefix '0.0.0.0/0' -ErrorAction SilentlyContinue | Sort-Object RouteMetric | Select-Object -First 1
  if ($route) {
    $ip = Get-NetIPAddress -InterfaceIndex $route.InterfaceIndex -AddressFamily IPv4 -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($ip) { return ($ip.IPAddress -replace '\.\d+$', '') }
  }
  return $null
}

function Find-OpenPorts($prefix) {
  $tasks = @{}
  foreach ($i in 1..254) {
    $c = New-Object System.Net.Sockets.TcpClient
    $tasks["$prefix.$i"] = @($c, $c.ConnectAsync("$prefix.$i", 5555))
  }
  Start-Sleep -Milliseconds 1500
  $found = @()
  foreach ($k in $tasks.Keys) {
    $c, $t = $tasks[$k]
    if ($t.Status -eq 'RanToCompletion' -and $c.Connected) { $found += $k }
    $c.Close()
  }
  $found | Sort-Object { [int]($_ -replace '.*\.', '') }
}

function Device-Name($s) {
  $state = (& $script:Adb -s $s get-state 2>&1 | Out-String).Trim()
  if ($state -eq 'device') {
    # One getprop per call: Windows PowerShell 5.1 mangles quotes passed to native programs.
    $p = @('ro.product.manufacturer', 'ro.product.model', 'ro.build.version.release') |
      ForEach-Object { (& $script:Adb -s $s shell getprop $_ | Out-String).Trim() }
    return "$($p[0]) $($p[1]) (Android $($p[2]))"
  }
  if ($state -match 'unauthorized') { return 'waiting for approval on the TV' }
  return 'not reachable'
}

function Connect-To($target) {
  if ($target -notmatch ':') { $target = "${target}:5555" }
  $out = (AdbRaw connect $target | Out-String).Trim()
  Start-Sleep -Seconds 1
  $state = (& $script:Adb -s $target get-state 2>&1 | Out-String).Trim()
  if ($state -match 'unauthorized') {
    Warn 'Look at your TV: a window asks to allow USB debugging. Tick "Always allow" and press OK.'
    for ($i = 0; $i -lt 30 -and $state -ne 'device'; $i++) {
      Start-Sleep -Seconds 2
      $state = (& $script:Adb -s $target get-state 2>&1 | Out-String).Trim()
    }
  }
  if ($state -eq 'device') {
    $script:Serial = $target
    Set-Content -Path $Conf -Value $target
    Ok "Connected to $(Device-Name $target) at $target"
    return $true
  }
  Err "Could not connect to $target ($out)"
  return $false
}

function Help-EnableDebugging {
  Title 'Turn on network debugging on the TV (one time)'
  Say '1. Settings → System → About (or Device Preferences → About).'
  Say '2. Click "Android TV OS build" (or "Build") 7 times: "You are now a developer".'
  Say '3. Back to Settings → System → Developer options:'
  Say '   turn on "USB debugging" and, if present, "Network debugging" / "Wireless debugging".'
  Say '4. The TV and this computer must be on the same Wi-Fi / network.'
}

function Find-Tv {
  Title 'Finding your TV'
  if (Test-Path $Conf) {
    $saved = (Get-Content $Conf -Raw).Trim()
    if ($saved -and (Connect-To $saved)) { return }
  }
  $prefix = Get-LocalPrefix
  $list = @()
  if ($prefix) {
    Say "Scanning $prefix.1-254 for TVs with debugging turned on…"
    $list = @(Find-OpenPorts $prefix)
  }
  if ($list.Count -eq 0) {
    Warn 'No TV found automatically.'
    Help-EnableDebugging
    $manual = Ask "Type the TV's IP address (Settings → Network), or press Enter to quit"
    if (-not $manual) { exit 1 }
    if ($manual -match ':' -and (Yes 'Is this a "Wireless debugging" pairing address (Android 11+ with a pairing code)?')) {
      $code = Ask 'Pairing code shown on the TV'
      AdbRaw pair $manual $code | Out-Host
      $manual = Ask 'Now type the IP:port shown under "IP address & port" on the TV'
    }
    if (-not (Connect-To $manual)) { exit 1 }
    return
  }
  $n = 1
  foreach ($ip in $list) {
    AdbRaw connect "${ip}:5555" | Out-Null
    Say ("  {0}) {1,-16} {2}" -f $n, $ip, (Device-Name "${ip}:5555"))
    $n++
  }
  $pick = 1
  if ($list.Count -gt 1) { $pick = Ask "Which one is your TV? [1-$($list.Count)]" }
  if (-not ($pick -match '^\d+$') -or [int]$pick -lt 1 -or [int]$pick -gt $list.Count) { $pick = 1 }
  if (-not (Connect-To $list[[int]$pick - 1])) { exit 1 }
}

# ---------------------------------------------------------------- backup

# backups\<tv>\baseline\   first backup, taken before any change and never replaced: what Restore goes back to
# backups\<tv>\snapshots\  later backups (for reference only)
# backups\<tv>\disabled_by_us.txt, suspended_by_us.txt   every change Zen TV made on this TV
function Device-Dir { Join-Path $Backups ($script:Serial -replace '[:/]', '_') }
function Baseline {
  $d = Join-Path (Device-Dir) 'baseline'
  if (Test-Path (Join-Path $d 'packages_disabled.txt')) { return $d }
  return $null
}

function Snapshot-To($b) {
  New-Item -ItemType Directory -Force $b | Out-Null
  Sh getprop | Set-Content (Join-Path $b 'getprop.txt')
  Pkgs '-d' | Set-Content (Join-Path $b 'packages_disabled.txt')
  Pkgs '-e' | Set-Content (Join-Path $b 'packages_enabled.txt')
  Pkgs '-s' | Set-Content (Join-Path $b 'packages_system.txt')
  Pkgs '-3' | Set-Content (Join-Path $b 'packages_user.txt')
  foreach ($ns in 'global', 'secure', 'system') { Sh settings list $ns | Set-Content (Join-Path $b "settings_$ns.txt") }
  Sh cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN |
    Select-Object -Last 1 | Set-Content (Join-Path $b 'home_activity.txt')
  Screenshot-To (Join-Path $b 'screen.png')
}

function Backup {
  Title 'Backup'
  $d = Device-Dir
  New-Item -ItemType Directory -Force $d | Out-Null
  foreach ($f in 'disabled_by_us.txt', 'suspended_by_us.txt') {
    if (-not (Test-Path (Join-Path $d $f))) { New-Item -ItemType File (Join-Path $d $f) | Out-Null }
  }
  if (-not (Baseline)) {
    Snapshot-To (Join-Path $d 'baseline')
    Ok "Original state saved in $d\baseline (Restore goes back to this)"
  } else {
    $s = Join-Path $d ('snapshots\' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
    Snapshot-To $s
    Ok "Snapshot saved in $s (the original state in $d\baseline is kept)"
  }
}

function Need-Backup {
  if (Baseline) { return }
  Warn 'No backup yet for this TV: saving its original state first.'
  Backup
}

# ---------------------------------------------------------------- debloat

function Read-Protected {
  $rules = Get-Content (Join-Path $Profiles 'protected.txt') | Where-Object { $_ -and -not $_.StartsWith('#') }
  @{ Exact = @($rules | Where-Object { -not $_.StartsWith('~') }); Words = @($rules | Where-Object { $_.StartsWith('~') } | ForEach-Object { $_.Substring(1) }) }
}

function Read-Profile($file) {
  Get-Content $file | Where-Object { $_.Trim() -and -not $_.Trim().StartsWith('#') } | ForEach-Object {
    $p = $_.Split('|') | ForEach-Object { $_.Trim() }
    [pscustomobject]@{ Pkg = $p[0]; Level = $p[1]; Label = $p[2]; Desc = $p[3] }
  }
}

function Add-Line($file, $line) {
  $existing = @(Get-Content $file -ErrorAction SilentlyContinue)
  if ($existing -notcontains $line) { Add-Content -Path $file -Value $line }
}

function Disable-Pkg($pkg, [bool]$suspend) {
  $b = Device-Dir
  $out = (Sh pm disable-user --user 0 $pkg | Out-String).Trim()
  if ($out -match 'disabled-user') {
    Add-Line (Join-Path $b 'disabled_by_us.txt') $pkg
    if ($suspend) {
      Sh pm suspend --user 0 $pkg | Out-Null
      Add-Line (Join-Path $b 'suspended_by_us.txt') $pkg
    }
    Ok "Disabled $pkg"
  } else { Warn "Skipped $pkg ($out)" }
}

function Debloat {
  Need-Backup
  Title 'Clean up apps'
  $manuf = ((Sh getprop ro.product.manufacturer | Out-String).Trim().ToLower() -replace '\s', '')
  $enabled = @(Pkgs '-e')
  $prot = Read-Protected
  $files = @(Join-Path $Profiles 'common.txt')
  $brand = Join-Path $Profiles "$manuf.txt"
  if (Test-Path $brand) { $files += $brand } else { Warn "No tested list for `"$manuf`" yet: only the generic Google list is used. (The advanced scan can find brand apps.)" }
  $entries = @($files | ForEach-Object { Read-Profile $_ } | Where-Object { $enabled -contains $_.Pkg -and $prot.Exact -notcontains $_.Pkg })
  $rec = @($entries | Where-Object { $_.Level -like 'recommended*' })
  $opt = @($entries | Where-Object { $_.Level -like 'optional*' })
  $chosen = New-Object System.Collections.ArrayList

  if ($rec.Count -gt 0) {
    foreach ($e in $rec) { Say "  $($e.Label) — $($e.Desc) ($($e.Pkg))" }
    Say ''
    $a = Ask "Above: $($rec.Count) ads / telemetry / junk apps. Disable them? [A]ll / [C]hoose one by one / [N]one"
    if ($a -match '^(a|A)?$') { $rec | ForEach-Object { [void]$chosen.Add($_) } }
    elseif ($a -match '^(c|C)$') { foreach ($e in $rec) { if (Yes "  Disable $($e.Label)?") { [void]$chosen.Add($e) } } }
  } else { Ok 'No known ads / telemetry apps are active.' }

  if ($opt.Count -gt 0) {
    Title 'Features you may use (default: keep)'
    foreach ($e in $opt) { if (Yes "  Disable $($e.Label) — $($e.Desc)?") { [void]$chosen.Add($e) } }
  }

  if (Yes "Advanced: also review other $manuf system apps not in the lists (for experienced users)?") {
    $known = @($entries | ForEach-Object { $_.Pkg }) + @($files | ForEach-Object { Read-Profile $_ } | ForEach-Object { $_.Pkg })
    foreach ($pkg in (Pkgs '-s' | Where-Object { $enabled -contains $_ })) {
      if ($pkg -match '^(com\.android\.|com\.google\.|android)') { continue }
      if ($known -contains $pkg -or $prot.Exact -contains $pkg) { continue }
      if (@($prot.Words | Where-Object { $pkg.Contains($_) }).Count -gt 0) { continue }
      if (Yes "  Disable $pkg?") { [void]$chosen.Add([pscustomobject]@{ Pkg = $pkg; Level = 'scan' }) }
    }
  }

  if ($chosen.Count -eq 0) { Ok 'Nothing to change.'; return }
  Title 'Applying'
  foreach ($e in $chosen) { Disable-Pkg $e.Pkg ($e.Level -like '*+suspend') }
  Ok 'Done. Everything can be undone with "Restore".'
}

function Reapply {
  $b = Device-Dir
  if (-not (Test-Path (Join-Path $b 'disabled_by_us.txt'))) { Warn 'Nothing applied yet.'; return }
  Title 'Re-applying after a TV update / reboot'
  $enabled = @(Pkgs '-e')
  $n = 0
  foreach ($pkg in (Get-Content (Join-Path $b 'disabled_by_us.txt') | Where-Object { $_ })) {
    if ($enabled -contains $pkg) { $n++; Warn "$pkg came back: disabling it again and suspending it"; Disable-Pkg $pkg $true }
  }
  if ($n -eq 0) { Ok 'All disabled apps are still disabled.' }
}

# ---------------------------------------------------------------- launcher

function Install-Launcher {
  Need-Backup
  Title 'Install Zen Home'
  if (-not (Test-Path $Apk)) { Err 'dist\zen-home.apk is missing. Download the release zip from GitHub.'; return }
  $res = (AdbS install -r $Apk | Out-String)
  if ($res -notmatch '(?m)^Success') {
    if ($res -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE') {
      Warn 'Another build of Zen Home is installed (signed differently). It must be removed first; your dock order will reset.'
      if (-not (Yes 'Remove the old Zen Home and install this one?')) { return }
      Sh pm uninstall com.zen.tvhome | Out-Null
      $res = (AdbS install $Apk | Out-String)
    }
    if ($res -notmatch '(?m)^Success') { Err "Install failed: $($res.Trim())"; return }
  }
  $cur = (Sh settings get secure enabled_accessibility_services | Out-String).Trim()
  if ($cur -notlike "*$Svc*") {
    $new = if (-not $cur -or $cur -eq 'null') { $Svc } else { "${cur}:$Svc" }
    Sh settings put secure enabled_accessibility_services "$new" | Out-Null
  }
  Sh settings put secure accessibility_enabled 1 | Out-Null
  Sh am start -n com.zen.tvhome/.HomeActivity | Out-Null
  Ok 'Zen Home installed. The Home button of the remote now opens it.'
  Say 'To give the Home button back to the TV without a computer: Settings → Accessibility → "Zen Home – Home button" → off.'
}

# ---------------------------------------------------------------- restore

function Restore-Tv {
  $d = Device-Dir
  $b = Baseline
  if (-not $b) { Warn 'No backup for this TV.'; return }
  Title 'Restore'
  Say "Original state: $b"
  if (-not (Yes 'Put the TV back as it was before Zen TV?')) { return }
  foreach ($pkg in (Get-Content (Join-Path $d 'disabled_by_us.txt') | Where-Object { $_ })) { Say "enable ${pkg}: $(Sh pm enable --user 0 $pkg)" }
  foreach ($pkg in (Get-Content (Join-Path $d 'suspended_by_us.txt') | Where-Object { $_ })) { Sh pm unsuspend --user 0 $pkg | Out-Null }
  $secure = Get-Content (Join-Path $b 'settings_secure.txt')
  foreach ($k in 'enabled_accessibility_services', 'accessibility_enabled') {
    $line = $secure | Where-Object { $_ -like "$k=*" } | Select-Object -First 1
    if ($line) { Sh settings put secure $k "$($line.Substring($k.Length + 1))" | Out-Null } else { Sh settings delete secure $k | Out-Null }
  }
  $homeActivity = Get-Content (Join-Path $b 'home_activity.txt') | Where-Object { $_ -match '/' } | Select-Object -Last 1
  if ($homeActivity) { Sh cmd package set-home-activity $homeActivity | Out-Null }
  # Zen Home is removed only if it wasn't there in the original state.
  $hadZen = @(Get-Content (Join-Path $b 'packages_user.txt')) -contains 'com.zen.tvhome'
  if (-not $hadZen -and (Sh pm list packages) -contains 'package:com.zen.tvhome') { Sh pm uninstall com.zen.tvhome | Out-Null }
  $before = @(Get-Content (Join-Path $b 'packages_disabled.txt') | Where-Object { $_ })
  $after = @(Pkgs '-d')
  $diff = Compare-Object $before $after
  if (-not $diff) { Ok 'The TV is back to its original state.' } else { Warn 'Differences with the original state (<= before, => now):'; $diff | Out-Host }
  Clear-Content (Join-Path $d 'disabled_by_us.txt'); Clear-Content (Join-Path $d 'suspended_by_us.txt')
  Sh input keyevent KEYCODE_HOME | Out-Null
}

function Screenshot-To($file) {
  # exec-out returns binary data: go through a file on the TV instead of the PowerShell pipeline.
  Sh screencap -p /sdcard/zen-shot.png | Out-Null
  AdbS pull /sdcard/zen-shot.png $file | Out-Null
  Sh rm /sdcard/zen-shot.png | Out-Null
}

function Screenshot {
  $dir = Join-Path $Root 'screenshots'
  New-Item -ItemType Directory -Force $dir | Out-Null
  $f = Join-Path $dir ("tv-{0}.png" -f (Get-Date -Format 'yyyyMMdd-HHmmss'))
  Screenshot-To $f
  Ok "Saved $f"
}

# ---------------------------------------------------------------- menu

Say 'Zen TV — faster, ad-free Android TV / Google TV. Nothing is uninstalled; everything can be undone.'
Ensure-Adb
Find-Tv
if ($Restore) { Restore-Tv; exit }
while ($true) {
  Title "What do you want to do?  ($(Device-Name $script:Serial))"
  Say '  1) Back up the TV (done automatically before any change)'
  Say '  2) Clean up: ads, telemetry, useless apps (asks before each feature)'
  Say '  3) Install the Zen Home screen'
  Say '  4) Re-apply after a TV update (if removed apps came back)'
  Say '  5) Restore the TV to how it was'
  Say '  6) Take a screenshot of the TV'
  Say '  7) Connect to another TV'
  Say '  0) Quit'
  switch (Ask '>') {
    '1' { Backup } '2' { Debloat } '3' { Install-Launcher } '4' { Reapply } '5' { Restore-Tv } '6' { Screenshot }
    '7' { Remove-Item $Conf -ErrorAction SilentlyContinue; $script:Serial = $null; Find-Tv }
    '0' { exit 0 } 'q' { exit 0 }
  }
}
