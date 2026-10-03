[CmdletBinding()]
param(
    [ValidateRange(2, 64)][int]$Workers = 4,
    [ValidateRange(1, 5)][int]$Forks = 3,
    [ValidatePattern('^[a-zA-Z0-9_-]+$')][string]$Label = 'current'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$reportDirectory = Join-Path $root ('build/reports/compute-' + $Label)
[IO.Directory]::CreateDirectory($reportDirectory) | Out-Null
for ($fork = 1; $fork -le $Forks; $fork++) {
    $lines = & (Join-Path $root 'gradlew.bat') ':leaves-server:benchmarkCompute' '--no-daemon' '--offline' '--max-workers=2' `
        '-I' (Join-Path $PSScriptRoot 'benchmarks/compute.init.gradle') "-PbenchmarkWorkers=$Workers" "-PbenchmarkOrder=$fork" 2>&1
    $exitCode = $LASTEXITCODE
    [IO.File]::WriteAllLines((Join-Path $reportDirectory "fork-$fork.log"), [string[]]$lines)
    $lines | Where-Object { $_ -match '^BENCH_' } | Write-Output
    if ($exitCode -ne 0 -or -not ($lines -match '^BENCH_DONE')) {
        throw "Benchmark fork $fork failed; inspect $reportDirectory/fork-$fork.log"
    }
}
