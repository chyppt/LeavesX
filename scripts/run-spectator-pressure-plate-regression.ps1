[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$ServerJar,

    [Parameter(Mandatory = $true)]
    [string]$InstanceDirectory,

    [Parameter(Mandatory = $true)]
    [int]$Port,

    [string]$SeedDirectory
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$utf8 = [System.Text.UTF8Encoding]::new($false)
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$smokeRoot = [System.IO.Path]::GetFullPath((Join-Path $projectRoot 'build-smoke'))
$serverJarPath = (Resolve-Path -LiteralPath $ServerJar).Path
$instancePath = [System.IO.Path]::GetFullPath($InstanceDirectory)
$smokePrefix = $smokeRoot.TrimEnd([System.IO.Path]::DirectorySeparatorChar) + [System.IO.Path]::DirectorySeparatorChar

if (-not $instancePath.StartsWith($smokePrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Regression instance must be below $smokeRoot"
}
if (Test-Path -LiteralPath $instancePath) {
    throw "Regression instance already exists: $instancePath"
}

[System.IO.Directory]::CreateDirectory($instancePath) | Out-Null
[System.IO.Directory]::CreateDirectory((Join-Path $instancePath 'plugins')) | Out-Null
if ($SeedDirectory) {
    $seedPath = (Resolve-Path -LiteralPath $SeedDirectory).Path
    foreach ($directoryName in @('cache', 'libraries', 'versions')) {
        $sourceDirectory = Join-Path $seedPath $directoryName
        if (Test-Path -LiteralPath $sourceDirectory -PathType Container) {
            Copy-Item -LiteralPath $sourceDirectory -Destination $instancePath -Recurse
        }
    }
}

$probeSourceRoot = Join-Path $PSScriptRoot 'integration-probes\spectator-plate\src'
$probeResourceRoot = Join-Path $PSScriptRoot 'integration-probes\spectator-plate\resources'
$probeBuildRoot = Join-Path $smokeRoot 'spectator-plate-probe-generated'
$probeClassesRoot = Join-Path $probeBuildRoot 'classes'
$probeJar = Join-Path $smokeRoot 'LeavesXSpectatorPlateProbe.jar'
if (Test-Path -LiteralPath $probeBuildRoot) {
    Remove-Item -LiteralPath $probeBuildRoot -Recurse -Force
}
[System.IO.Directory]::CreateDirectory($probeClassesRoot) | Out-Null
$apiJar = (Resolve-Path -LiteralPath (Join-Path $projectRoot 'leaves-api\build\libs\leaves-api-26.1.2-R0.1-SNAPSHOT.jar')).Path
$classpath = [System.Collections.Generic.List[string]]::new()
$classpath.Add($apiJar)
Get-ChildItem -LiteralPath (Join-Path $projectRoot 'libraries') -Recurse -Filter '*.jar' -File |
    ForEach-Object { $classpath.Add($_.FullName) }
$probeSources = @(Get-ChildItem -LiteralPath $probeSourceRoot -Recurse -Filter '*.java' -File | ForEach-Object FullName)
$javacArguments = @(
    '-encoding'
    'UTF-8'
    '-source'
    '25'
    '-target'
    '25'
    '-classpath'
    ($classpath -join [System.IO.Path]::PathSeparator)
    '-d'
    $probeClassesRoot
) + $probeSources
& javac @javacArguments
if ($LASTEXITCODE -ne 0) {
    throw 'Failed to compile the spectator pressure-plate probe.'
}
Copy-Item -LiteralPath (Join-Path $probeResourceRoot 'plugin.yml') -Destination $probeClassesRoot
if (Test-Path -LiteralPath $probeJar) {
    Remove-Item -LiteralPath $probeJar -Force
}
& jar '--create' '--file' $probeJar '-C' $probeClassesRoot '.'
if ($LASTEXITCODE -ne 0) {
    throw 'Failed to package the spectator pressure-plate probe.'
}
Copy-Item -LiteralPath $probeJar -Destination (Join-Path $instancePath 'plugins')

[System.IO.File]::WriteAllText((Join-Path $instancePath 'eula.txt'), "eula=true`r`n", $utf8)
$serverProperties = @(
    'allow-flight=true'
    'difficulty=hard'
    'enable-status=false'
    'generate-structures=false'
    'level-name=world'
    'level-seed=LeavesX-spectator-pressure-plate-regression'
    'level-type=minecraft:normal'
    'max-players=1'
    'max-tick-time=-1'
    'online-mode=false'
    'pause-when-empty-seconds=-1'
    'server-ip=127.0.0.1'
    "server-port=$Port"
    'simulation-distance=2'
    'spawn-monsters=false'
    'spawn-protection=0'
    'sync-chunk-writes=true'
    'view-distance=2'
) -join "`r`n"
[System.IO.File]::WriteAllText((Join-Path $instancePath 'server.properties'), $serverProperties + "`r`n", $utf8)

$consoleLog = Join-Path $instancePath 'regression-console.log'
$errorLog = Join-Path $instancePath 'regression-error.log'
$startInfo = [System.Diagnostics.ProcessStartInfo]::new()
$startInfo.FileName = 'java'
$startInfo.Arguments = "-Xms512M -Xmx1G -Dfile.encoding=UTF-8 -jar `"$serverJarPath`" --nogui --leaves-settings leaves.yml --leavesx-settings leavesx.yml"
$startInfo.WorkingDirectory = $instancePath
$startInfo.UseShellExecute = $false
$startInfo.RedirectStandardInput = $true
$startInfo.RedirectStandardOutput = $true
$startInfo.RedirectStandardError = $true
$startInfo.StandardOutputEncoding = $utf8
$startInfo.StandardErrorEncoding = $utf8
$startInfo.CreateNoWindow = $true

$process = [System.Diagnostics.Process]::new()
$process.StartInfo = $startInfo
$consoleWriter = $null
$processStarted = $false
$normalStop = $false
$reachedDone = $false

function Send-Commands {
    param([string[]]$Commands)

    foreach ($command in $Commands) {
        $process.StandardInput.WriteLine($command)
    }
    $process.StandardInput.Flush()
}

try {
    if (-not $process.Start()) {
        throw 'Failed to start LeavesX.'
    }
    $processStarted = $true
    $errorTask = $process.StandardError.ReadToEndAsync()
    $consoleWriter = [System.IO.StreamWriter]::new($consoleLog, $false, $utf8)
    $consoleWriter.AutoFlush = $true

    while ($null -ne ($line = $process.StandardOutput.ReadLine())) {
        $consoleWriter.WriteLine($line)
        if (-not $reachedDone -and $line -match 'Done \(.+\)! For help') {
            $reachedDone = $true
            Send-Commands @(
                'gamerule spectators_generate_chunks false'
                'forceload add 0 0'
            )

            # Wait for the forceload ticket before placing the fixture, then allow each
            # pressure-plate transition several complete server ticks to settle.
            Start-Sleep -Seconds 3
            Send-Commands @(
                'fill -2 100 -2 2 100 2 minecraft:stone'
                'fill -2 101 -2 2 104 2 minecraft:air'
                'setblock 0 101 0 minecraft:stone_pressure_plate'
                'bot create lx_plate lx_plate minecraft:overworld 0.5 101 0.5'
            )
            Start-Sleep -Seconds 3
            Send-Commands @(
                'execute if block 0 101 0 minecraft:stone_pressure_plate[powered=true] run say LX_FIXTURE_READY'
            )
        }
        if ($line -match 'Stopping (the )?server') {
            $normalStop = $true
        }
    }

    $process.WaitForExit()
    [System.IO.File]::WriteAllText($errorLog, $errorTask.Result, $utf8)
    if ($process.ExitCode -ne 0) {
        throw "LeavesX exited with code $($process.ExitCode)."
    }
    if (-not $reachedDone -or -not $normalStop) {
        throw 'LeavesX did not start and stop normally.'
    }

    $consoleWriter.Dispose()
    $consoleWriter = $null
    $consoleText = [System.IO.File]::ReadAllText($consoleLog, $utf8)
    foreach ($marker in @('LX_PLATE_PRESSED', 'LX_BOT_SPECTATOR', 'LX_PLATE_RELEASED', 'LX_SPECTATOR_PLATE_RESULT status=PASS')) {
        if (-not $consoleText.Contains($marker)) {
            throw "Missing spectator pressure-plate marker: $marker"
        }
    }
    if ($consoleText -match 'Entity threw exception|The server has not responded') {
        throw 'The spectator pressure-plate console contains a server failure.'
    }
} finally {
    if ($null -ne $consoleWriter) {
        $consoleWriter.Dispose()
    }
    if ($processStarted -and -not $process.HasExited) {
        $process.Kill()
        $process.WaitForExit()
    }
    $process.Dispose()
}
