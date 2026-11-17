[CmdletBinding()]
param(
    [ValidateRange(2, 64)][int]$Workers = 4,
    [ValidateRange(1, 5)][int]$Forks = 3,
    [ValidatePattern('^[a-zA-Z0-9_-]+$')][string]$Label = 'current',
    [switch]$LowLoadWorkers,
    [ValidateSet('standard', 'low-load', 'small-sort', 'medium-sort', 'kernels', 'spawn-count', 'tracking-sparse', 'entity-pipeline', 'entity-compute', 'entity-kernels', 'tracker-prepare', 'snapshot-backpressure', 'tracker-player-lists', 'snapshot-reuse', 'compute-handoff', 'tracker-frame-peak', 'snapshot-cache')][string]$Suite = 'standard'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
. (Join-Path $PSScriptRoot 'Invoke-HiddenBatchCommand.ps1')
$reportDirectory = Join-Path $root ('build/reports/compute-' + $Label)
[IO.Directory]::CreateDirectory($reportDirectory) | Out-Null
for ($fork = 1; $fork -le $Forks; $fork++) {
    $arguments = @(':leaves-server:benchmarkCompute', '--no-daemon', '--no-build-cache', '--offline', '--max-workers=2',
        '-I', (Join-Path $PSScriptRoot 'benchmarks/compute.init.gradle'), "-PbenchmarkWorkers=$Workers", "-PbenchmarkOrder=$fork",
        "-PbenchmarkLowLoadWorkers=$($LowLoadWorkers.IsPresent.ToString().ToLowerInvariant())", "-PbenchmarkSuite=$Suite")
    $result = Invoke-HiddenBatchCommand `
        -BatchFile (Join-Path $root 'gradlew.bat') `
        -Arguments $arguments `
        -WorkingDirectory $root `
        -LogPath (Join-Path $reportDirectory "fork-$fork.log")
    $lines = $result.Output -split "`r?`n"
    $lines | Where-Object { $_ -match '^BENCH_' } | Write-Output
    if ($result.ExitCode -ne 0 -or -not ($lines -match '^BENCH_DONE')) {
        throw "Benchmark fork $fork failed; inspect $reportDirectory/fork-$fork.log"
    }
}
