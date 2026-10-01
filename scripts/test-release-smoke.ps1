[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Jar,
    [Parameter(Mandatory)][string]$Instance,
    [Parameter(Mandatory)][string]$ExpectedVersion,
    [string]$ProbeMarker = ''
)
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$instancePath = [IO.Path]::GetFullPath($Instance)
if (!$instancePath.StartsWith((Join-Path $root 'build') + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Use a disposable instance under this repository build directory.'
}
if (!(Test-Path -LiteralPath (Join-Path $instancePath 'eula.txt'))) { throw 'Prepare an explicitly accepted test-instance eula.txt first.' }
$jarPath = (Resolve-Path -LiteralPath $Jar).Path
function Assert-ServerVersion([IO.Compression.ZipArchive]$Archive) {
    $manifest = $Archive.GetEntry('META-INF/MANIFEST.MF')
    if ($null -eq $manifest) { throw 'Server manifest is missing.' }
    $reader = [IO.StreamReader]::new($manifest.Open())
    try { $manifestText = $reader.ReadToEnd() -replace '\r?\n ', '' } finally { $reader.Dispose() }
    $version = [regex]::Match($manifestText, '(?m)^LeavesX-Version: ([^\r\n]+)\r?$')
    if (!$version.Success -or $version.Groups[1].Value -cne $ExpectedVersion) {
        throw "Wrong server version: expected $ExpectedVersion, found $($version.Groups[1].Value)"
    }
}
# Bundler embeds a server JAR; Leavesclip reconstructs it from a patch at startup.
# In both cases validate the actual server manifest, never a version in a log path.
$runtimeServerPath = $null
$expectedServerHash = $null
$bundle = [IO.Compression.ZipFile]::OpenRead($jarPath)
try {
    $servers = @($bundle.Entries | Where-Object { $_.FullName -match '^META-INF/versions/[^/]+/[^/]+\.jar$' })
    if ($servers.Count -eq 0) {
        $patches = @($bundle.Entries | Where-Object { $_.FullName -match '^META-INF/versions/[^/]+/[^/]+\.jar\.patch$' })
        $versionsList = $bundle.GetEntry('META-INF/versions.list')
        if ($patches.Count -ne 1 -or $null -eq $versionsList) { throw 'Expected one embedded server JAR or one Leavesclip server patch.' }
        $reader = [IO.StreamReader]::new($versionsList.Open())
        try { $metadata = $reader.ReadToEnd().Trim() } finally { $reader.Dispose() }
        $entry = [regex]::Match($metadata, '\A([0-9a-fA-F]{64})\t[^\t\r\n]+\t([^\t\r\n]+\.jar)\z')
        if (!$entry.Success) { throw 'Invalid Leavesclip server metadata.' }
        $versionsPath = Join-Path $instancePath 'versions'
        $runtimeServerPath = [IO.Path]::GetFullPath((Join-Path $versionsPath $entry.Groups[2].Value))
        if (!$runtimeServerPath.StartsWith($versionsPath + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
            throw 'Leavesclip server path escapes the test instance.'
        }
        $expectedServerHash = $entry.Groups[1].Value
    } elseif ($servers.Count -eq 1) {
        $buffer = [IO.MemoryStream]::new()
        try {
            $entryStream = $servers[0].Open()
            try { $entryStream.CopyTo($buffer) } finally { $entryStream.Dispose() }
            $buffer.Position = 0
            $serverArchive = [IO.Compression.ZipArchive]::new($buffer, [IO.Compression.ZipArchiveMode]::Read, $true)
            try { Assert-ServerVersion $serverArchive } finally { $serverArchive.Dispose() }
        } finally { $buffer.Dispose() }
    } else { throw 'Expected exactly one embedded server JAR.' }
} finally { $bundle.Dispose() }
function Read-Log([string]$Path) {
    if (!(Test-Path -LiteralPath $Path)) { return '' }
    # The server keeps latest.log open for writing; allow concurrent writes while polling.
    try {
        $stream = [IO.File]::Open($Path, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::ReadWrite)
        $reader = [IO.StreamReader]::new($stream)
        try { return $reader.ReadToEnd() } finally { $reader.Dispose() }
    } catch [IO.IOException] { return '' }
}
for ($round = 1; $round -le 3; $round++) {
    $log = Join-Path $instancePath 'logs/latest.log'
    if (Test-Path -LiteralPath $log) {
        Move-Item -LiteralPath $log -Destination (Join-Path $instancePath ('logs/prior-' + [Guid]::NewGuid().ToString('N') + '.log'))
    }
    $start = [Diagnostics.ProcessStartInfo]::new((Join-Path $env:JAVA_HOME 'bin/java.exe'))
    $start.WorkingDirectory = $instancePath
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardInput = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    # Keep the console reader enabled and bypass JLine so redirected stdin is deterministic.
    foreach ($arg in @('-Xms512M', '-Xmx2G', '-jar', $jarPath, '--nojline')) { $start.ArgumentList.Add($arg) }
    $process = [Diagnostics.Process]::Start($start)
    $process.StandardInput.AutoFlush = $true
    $stdout = $process.StandardOutput.ReadToEndAsync()
    $stderr = $process.StandardError.ReadToEndAsync()
    try {
        $deadline = [DateTime]::UtcNow.AddMinutes(5)
        while (!$process.HasExited -and [DateTime]::UtcNow -lt $deadline -and !(Read-Log $log).Contains('Done (')) {
            Start-Sleep -Milliseconds 500
        }
        if ($process.HasExited -or !(Read-Log $log).Contains('Done (')) { throw "Round $round did not start; inspect $log" }
        if ($runtimeServerPath) {
            if ((Get-FileHash -LiteralPath $runtimeServerPath -Algorithm SHA256).Hash -ine $expectedServerHash) {
                throw "Round $round reconstructed server does not match the release bundle"
            }
            $serverArchive = [IO.Compression.ZipFile]::OpenRead($runtimeServerPath)
            try { Assert-ServerVersion $serverArchive } finally { $serverArchive.Dispose() }
        }
        if ($ProbeMarker) {
            $deadline = [DateTime]::UtcNow.AddMinutes(3)
            do {
                Start-Sleep -Milliseconds 500
                $probeLog = Read-Log $log
                if ($probeLog.Contains('PROBE_FAIL')) { throw "Round $round integration probe failed" }
            } while (!$probeLog.Contains($ProbeMarker) -and !$process.HasExited -and [DateTime]::UtcNow -lt $deadline)
            if (!$probeLog.Contains($ProbeMarker)) { throw "Round $round missing probe marker $ProbeMarker" }
        }
        $commands = @('version', 'leavesx health', 'leavesx report', 'leavesx parallel', 'leavesx network', 'leavesx config check', 'leavesx reload', 'forceload add 0 0')
        if ($round -eq 1) {
            $commands += 'summon minecraft:zombie 0 100 0 {NoAI:1b,NoGravity:1b,Invulnerable:1b,PersistenceRequired:1b,CustomName:"LeavesXPersistenceProbe",Tags:["leavesx_persistence_probe"]}'
        }
        foreach ($command in $commands) { $process.StandardInput.WriteLine($command) }
        # Allow the forced chunk and entity data to load before checking persisted identity.
        Start-Sleep -Seconds 3
        $process.StandardInput.WriteLine('execute if entity @e[type=minecraft:zombie,tag=leavesx_persistence_probe] run say LEAVESX_PERSISTENCE_OK')
        $process.StandardInput.WriteLine('save-all flush')
        $deadline = [DateTime]::UtcNow.AddSeconds(45)
        do {
            Start-Sleep -Milliseconds 500
            $content = Read-Log $log
            $ready = $content.Contains('LEAVESX_PERSISTENCE_OK') -and $content.Contains('诊断报告已保存') -and $content.Contains('Saved the game')
        } while (!$ready -and !$process.HasExited -and [DateTime]::UtcNow -lt $deadline)
        if (!$ready) { throw "Round $round missing persistence/report/save confirmation" }
        $process.StandardInput.WriteLine('stop')
        if (!$process.WaitForExit(60000)) { throw "Round $round shutdown timeout" }
        if ($process.ExitCode -ne 0) { throw "Round $round exit code $($process.ExitCode)" }
        $content = Read-Log $log
        if (!$content.Contains('----- LeavesX 健康检查 -----') -or !$content.Contains('LeavesX 配置重载完成。')) {
            throw "Round $round missing health/reload confirmation"
        }
        if (!$content.Contains('YAML 和配置值有效；本次检查未写入文件，也未重载运行时。')) {
            throw "Round $round missing read-only configuration check confirmation"
        }
        if (!$content.Contains('区块节编码') -or !$content.Contains('编码工作池')) {
            throw "Round $round missing network encoding diagnostics"
        }
        if ($content -match 'Entity threw exception|Encountered an unexpected exception|Command exception|The server has not responded for|Error occurred while enabling|PROBE_FAIL') { throw "Round $round runtime error: $($Matches[0])" }
        Copy-Item -LiteralPath $log -Destination (Join-Path $instancePath "round-$round.log")
        Write-Output "SMOKE_PASS round $round : version, health, report, network, reload, named entity persistence, save, shutdown"
    } finally {
        if (!$process.HasExited) {
            $process.StandardInput.WriteLine('stop')
            if (!$process.WaitForExit(15000)) { $process.Kill() }
        }
        $process.WaitForExit()
        [IO.File]::WriteAllText((Join-Path $instancePath "round-$round.stdout.log"), $stdout.GetAwaiter().GetResult())
        [IO.File]::WriteAllText((Join-Path $instancePath "round-$round.stderr.log"), $stderr.GetAwaiter().GetResult())
        $process.Dispose()
    }
}
