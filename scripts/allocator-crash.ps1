# Demo of failure handling: hard-kills the running allocator (like kill -9), waits, and
# restarts it from its log in a new window. Watch the Monitor: allocator DOWN, requests HELD,
# then reconciliation. Run after scripts\run-all.ps1.
#   scripts\allocator-crash.ps1 [-DownSeconds 8] [-TimeScale 10]
param([int]$DownSeconds = 8, [int]$TimeScale = 10)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
# The allocator is the java.exe whose command line runs RunwayAllocator (child of its window).
$alloc = Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
    Where-Object { $_.CommandLine -like '*atc.allocator.RunwayAllocator*' }
if (-not $alloc) { throw 'no running RunwayAllocator found' }
foreach ($a in $alloc) {
    Write-Host "HARD KILL allocator java pid $($a.ProcessId)"
    Stop-Process -Id $a.ProcessId -Force
}
Write-Host "allocator down for $DownSeconds s ..."
Start-Sleep -Seconds $DownSeconds
$cmd = "`$host.UI.RawUI.WindowTitle = 'allocator (restarted)'; java -cp target\atc.jar atc.allocator.RunwayAllocator --time-scale $TimeScale 2>&1 | ForEach-Object { `$_; Add-Content -Path data\logs\allocator-restarted.log -Value `$_ -Encoding utf8 }"
$p = Start-Process powershell -ArgumentList '-NoExit', '-NoProfile', '-Command', $cmd -PassThru
Add-Content 'data\run-all.pids' "allocator-restarted $($p.Id)"
Write-Host "allocator restarted from its log (window pid $($p.Id))"
