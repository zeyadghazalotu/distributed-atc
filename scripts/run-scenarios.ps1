# Builds (if needed) and runs the evaluation scenarios; results go to results\<scenario>\.
#   scripts\run-scenarios.ps1            all 7 scenarios (~3 min)
#   scripts\run-scenarios.ps1 1 5        selected scenarios
#   scripts\run-scenarios.ps1 7 --quick  short load test
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
if (-not (Test-Path 'target\atc.jar')) {
    mvn -q -DskipTests package
    if ($LASTEXITCODE -ne 0) { throw 'build failed' }
}
java -cp target\atc.jar atc.scenario.ScenarioRunner @args
exit $LASTEXITCODE
