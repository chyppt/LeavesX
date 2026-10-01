[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Instance,
    [Parameter(Mandatory)][string]$Marker,
    [string]$RunName = 'smoke',
    [string]$ReportVersion = 'beta2',
    [switch]$SecureSeed
)
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$instancePath = (Resolve-Path -LiteralPath $Instance).Path
if (!$instancePath.StartsWith((Join-Path $root 'build') + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Only disposable instances inside build are allowed'
}
$log = Join-Path $instancePath 'logs/latest.log'
if (Test-Path -LiteralPath $log) {
    Move-Item -LiteralPath $log -Destination (Join-Path $instancePath ('logs/before-' + [Guid]::NewGuid().ToString('N') + '.log'))
}
$start = [Diagnostics.ProcessStartInfo]::new()
$start.FileName = Join-Path $env:JAVA_HOME 'bin/java.exe'
$jar = Join-Path $root 'leaves-server/build/libs/leavesx-26.1.2.jar'
$start.Arguments = '-Xms512M -Xmx3G -Dleavesx.probe.secure=' + $SecureSeed.IsPresent.ToString().ToLowerInvariant() + ' -jar "' + $jar + '" --nogui'
$start.WorkingDirectory = $instancePath
$start.UseShellExecute = $false
$start.CreateNoWindow = $true
$start.RedirectStandardInput = $true
$process = [Diagnostics.Process]::Start($start)
$deadline = [DateTime]::UtcNow.AddMinutes(6)
$passed = $false
# Match watchdog log records, not harmless paths such as build/smoke-watchdog-hotfix2.
$runtimeFailurePattern = 'Encountered an unexpected exception|Entity threw exception|\[[^\]\r\n]*Watchdog[^\]\r\n]*/(?:ERROR|WARN)\]|The server has not responded for \d+ seconds|PROBE_FAIL|Error occurred while enabling|Command exception'
function Read-SmokeLog {
    # Log4j briefly rotates/reopens latest.log during startup on Windows.
    try {
        $stream = [IO.File]::Open($log, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete)
        $reader = [IO.StreamReader]::new($stream)
        try { return $reader.ReadToEnd() } finally { $reader.Dispose() }
    } catch [IO.IOException] { return '' }
}
try {
    while (!$process.HasExited -and [DateTime]::UtcNow -lt $deadline) {
        if (Test-Path -LiteralPath $log) {
            $content = Read-SmokeLog
            if ($content.Contains('PROBE_FAIL')) { throw 'Integration probe failed; inspect latest.log' }
            if ($content.Contains($Marker) -and $content.Contains('Done (')) {
                $passed = $true
                foreach ($command in @('player nonexistent stop','leavesx ai','leavesx threads','leavesx parallel','paper reload confirm','leavesx reload','save-all flush','stop')) {
                    $process.StandardInput.WriteLine($command)
                }
                break
            }
        }
        Start-Sleep -Seconds 1
    }
    if (!$passed) { throw "Missing integration result $Marker" }
    if (!$process.WaitForExit(60000)) { throw 'Shutdown timed out' }
    if ($process.ExitCode -ne 0) { throw "Server exited with $($process.ExitCode)" }
    $content = [IO.File]::ReadAllText($log)
    if ($content -match $runtimeFailurePattern) {
        throw "Unexpected runtime failure in smoke log: $($Matches[0])"
    }
    $reports = Join-Path $root ('build/reports/' + $ReportVersion)
    New-Item -ItemType Directory -Path $reports -Force | Out-Null
    Copy-Item -LiteralPath $log -Destination (Join-Path $reports ($RunName + '.log'))
    Write-Output "SMOKE_PASS: $RunName; probe, commands, reload, save, shutdown"
} finally {
    if (!$process.HasExited) {
        $process.StandardInput.WriteLine('stop')
        if (!$process.WaitForExit(15000)) { $process.Kill() }
    }
    $process.Dispose()
}
