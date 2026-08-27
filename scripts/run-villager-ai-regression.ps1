param(
    [Parameter(Mandatory = $true)]
    [string]$ServerJar,

    [Parameter(Mandatory = $true)]
    [string]$InstanceDirectory,

    [Parameter(Mandatory = $true)]
    [ValidateSet(0, 1)]
    [int]$AiOptimizations,

    [Parameter(Mandatory = $true)]
    [int]$Port,

    [string]$SeedDirectory
)

$ErrorActionPreference = 'Stop'
$utf8 = [System.Text.UTF8Encoding]::new($false)
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$serverJarPath = (Resolve-Path -LiteralPath $ServerJar).Path
$instancePath = [System.IO.Path]::GetFullPath($InstanceDirectory)
$smokeRoot = [System.IO.Path]::GetFullPath((Join-Path $projectRoot 'build-smoke'))

if (-not $instancePath.StartsWith($smokeRoot + [System.IO.Path]::DirectorySeparatorChar, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Regression instance must be below $smokeRoot"
}
if (Test-Path -LiteralPath $instancePath) {
    throw "Regression instance already exists: $instancePath"
}

[System.IO.Directory]::CreateDirectory($instancePath) | Out-Null
[System.IO.Directory]::CreateDirectory((Join-Path $instancePath 'plugins')) | Out-Null
$probeJar = Join-Path $smokeRoot 'LeavesXBrainProbe.jar'
if (-not (Test-Path -LiteralPath $probeJar)) {
    throw "Missing integration probe jar: $probeJar"
}
Copy-Item -LiteralPath $probeJar -Destination (Join-Path $instancePath 'plugins')
if ($SeedDirectory) {
    $seedPath = (Resolve-Path -LiteralPath $SeedDirectory).Path
    foreach ($directoryName in @('cache', 'libraries', 'versions')) {
        $sourceDirectory = Join-Path $seedPath $directoryName
        if (Test-Path -LiteralPath $sourceDirectory -PathType Container) {
            Copy-Item -LiteralPath $sourceDirectory -Destination $instancePath -Recurse
        }
    }
}

[System.IO.File]::WriteAllText((Join-Path $instancePath 'eula.txt'), "eula=true`r`n", $utf8)
[System.IO.File]::WriteAllText(
    (Join-Path $instancePath 'leavesx.yml'),
    "config-version: 58`r`nperformance:`r`n  ai-optimizations: $((($AiOptimizations -eq 1).ToString()).ToLowerInvariant())`r`n",
    $utf8
)

$serverProperties = @(
    'allow-flight=true'
    'difficulty=hard'
    'enable-status=false'
    'generate-structures=false'
    'level-name=world'
    'level-seed=LeavesX-villager-ai-regression'
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
$reachedDone = $false
$normalStop = $false

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
            @(
                'forceload add 0 0'
            ) | ForEach-Object { $process.StandardInput.WriteLine($_) }
            $process.StandardInput.Flush()

            # Wait for the forced chunk to finish loading before placing the deterministic fixture.
            Start-Sleep -Seconds 3
            @(
                # Keep every fixture command inside chunk [0, 0]. The old negative corner crossed into three
                # unforced chunks, so one unloaded corner could reject the entire platform fill and let the
                # villager fall out of sensor range.
                'fill 0 100 0 12 100 12 minecraft:stone'
                'fill 0 101 0 12 110 12 minecraft:air'
                'kill @e[type=minecraft:villager]'
                'kill @e[type=minecraft:zombie]'
                'summon minecraft:villager 2.5 101 2.5 {PersistenceRequired:1b,Invulnerable:1b,Tags:["lx_villager"]}'
                'attribute @e[type=minecraft:villager,tag=lx_villager,limit=1] minecraft:movement_speed base set 0'
                'summon minecraft:zombie 6.5 101 2.5 {NoAI:1b,Silent:1b,Invulnerable:1b,PersistenceRequired:1b,Tags:["lx_hostile"]}'
            ) | ForEach-Object { $process.StandardInput.WriteLine($_) }
            $process.StandardInput.Flush()

            # Both sensors start immediately, but several seconds also cover configured scan-rate jitter.
            Start-Sleep -Seconds 8
            @(
                'execute if entity @e[type=minecraft:villager,tag=lx_villager,limit=1] run say LX_VILLAGER_ALIVE'
                'execute if entity @e[type=minecraft:zombie,tag=lx_hostile,limit=1] run say LX_HOSTILE_ALIVE'
                'data get entity @e[type=minecraft:villager,tag=lx_villager,limit=1] Pos'
                'data get entity @e[type=minecraft:zombie,tag=lx_hostile,limit=1] Pos'
                'stop'
            ) | ForEach-Object { $process.StandardInput.WriteLine($_) }
            $process.StandardInput.Flush()
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
    if (-not $consoleText.Contains('LX_VILLAGER_ALIVE')) {
        throw 'The regression villager was not alive when inspected.'
    }
    $probeLines = $consoleText -split "`r?`n" | Where-Object { $_ -match 'LeavesXBrainProbe.*PROBE' }
    if ($probeLines.Count -lt 20) {
        throw "The Brain probe did not collect enough ticks ($($probeLines.Count))."
    }
    if (-not ($probeLines -match 'hostile=True')) {
        throw 'The villager Brain never observed minecraft:nearest_hostile.'
    }
    if (-not ($probeLines -match 'activity=\[core, panic\]')) {
        throw 'The villager Brain never entered the panic activity.'
    }
    if ($consoleText -match 'Entity threw exception|Unregistered memory fetched|\[ERROR\]') {
        throw 'The regression console contains an entity or server error.'
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
