# Stops the windows started by run-all.ps1 / allocator-crash.ps1 (and the java process in each).
# Only touches processes recorded in data\run-all.pids, never other Java programs (e.g. your IDE).
$root = Split-Path -Parent $PSScriptRoot
$pidFile = Join-Path $root 'data\run-all.pids'
if (-not (Test-Path $pidFile)) { Write-Host 'nothing to stop (no data\run-all.pids)'; exit 0 }
foreach ($line in Get-Content $pidFile) {
    $name, $id = $line -split ' '
    # Kill the java child first, then its window.
    Get-CimInstance Win32_Process -Filter "ParentProcessId=$id" | ForEach-Object {
        Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
    }
    Stop-Process -Id $id -Force -ErrorAction SilentlyContinue
    Write-Host "stopped $name"
}
Remove-Item $pidFile
