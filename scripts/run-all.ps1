# Starts the whole system for a live demo (Windows PowerShell 5.1+).
#   scripts\run-all.ps1 [-TimeScale 10] [-Emergency YYZ-AC3] [-Count 7] [-KeepLog]
# Each service gets its own window; the Monitor runs in a window too. Logs go to data\logs\.
# Stop everything with scripts\stop-all.ps1 (only stops processes this script started).
param(
    [int]$TimeScale = 10,
    [string]$Emergency = "",
    [int]$Count = 7,
    [switch]$KeepLog
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
if (-not (Test-Path 'target\atc.jar')) {
    Write-Host 'Building target\atc.jar ...'
    mvn -q -DskipTests package
    if ($LASTEXITCODE -ne 0) { throw 'build failed' }
}
New-Item -ItemType Directory -Force 'data\logs' | Out-Null
$pidFile = 'data\run-all.pids'
if (Test-Path $pidFile) { Remove-Item $pidFile }
# A demo starts with an empty allocator log (pass -KeepLog to continue the previous one).
# allocator-crash.ps1 deliberately does NOT do this: it restarts from the log.
if (-not $KeepLog -and (Test-Path 'data\allocator-log.jsonl')) { Remove-Item 'data\allocator-log.jsonl' }

function Start-AtcService([string]$name, [string]$javaArgs) {
    # A window per service that shows its log live and also keeps it in data\logs\<name>.log.
    $cmd = "`$host.UI.RawUI.WindowTitle = '$name'; java -cp target\atc.jar $javaArgs 2>&1 | ForEach-Object { `$_; Add-Content -Path data\logs\$name.log -Value `$_ -Encoding utf8 }"
    $p = Start-Process powershell -ArgumentList '-NoExit', '-NoProfile', '-Command', $cmd -PassThru
    Add-Content $pidFile "$name $($p.Id)"
    Write-Host ("started {0,-14} (window pid {1})" -f $name, $p.Id)
}

Start-AtcService 'allocator' "atc.allocator.RunwayAllocator --time-scale $TimeScale"
Start-Sleep -Seconds 2
Start-AtcService 'control-YYZ' 'atc.control.ControlService --airport YYZ'
Start-AtcService 'control-YOW' 'atc.control.ControlService --airport YOW'
Start-AtcService 'monitor' 'atc.monitor.Monitor --events-log data\logs\monitor-events.log'
Start-AtcService 'web' 'atc.monitor.WebDashboard'
Start-Sleep -Seconds 2
$em = ''
if ($Emergency -ne '') { $em = "--emergency $Emergency" }
Start-AtcService 'sim-YYZ' "atc.aircraft.AircraftSimulator --airport YYZ --count $Count --time-scale $TimeScale $em"
Start-AtcService 'sim-YOW' "atc.aircraft.AircraftSimulator --airport YOW --count $Count --time-scale $TimeScale --seed 2"
Write-Host ''
Write-Host 'Web dashboard: http://localhost:8080'
Write-Host 'All services started. Crash test: scripts\allocator-crash.ps1   Stop: scripts\stop-all.ps1'
