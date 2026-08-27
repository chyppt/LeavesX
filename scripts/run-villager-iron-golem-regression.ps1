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

    [ValidateSet(0, 1)]
    [int]$VillagerCompatibilityMode = 1,

    [ValidateSet(0, 1)]
    [int]$TickInactiveVillagers = 1,

    [ValidateSet(0, 1)]
    [int]$VillagersActiveForPanic = 1,

    [ValidateRange(0, 256)]
    [int]$VillagerActivationRange = 32,

    [ValidateSet(0, 1)]
    [int]$RequireInactiveVillager = 0,

    [string]$SeedDirectory
)

$ErrorActionPreference = 'Stop'
$utf8 = [System.Text.UTF8Encoding]::new($false)

# Resolve paths from the repository instead of relying on the historical X: drive mapping.
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
    "config-version: 58`r`nperformance:`r`n  ai-optimizations: $((($AiOptimizations -eq 1).ToString()).ToLowerInvariant())`r`n  villager-compatibility-mode: $((($VillagerCompatibilityMode -eq 1).ToString()).ToLowerInvariant())`r`n",
    $utf8
)
$tickInactiveVillagersText = (($TickInactiveVillagers -eq 1).ToString()).ToLowerInvariant()
$villagersActiveForPanicText = (($VillagersActiveForPanic -eq 1).ToString()).ToLowerInvariant()
$wakeUpVillagersPerTick = if ($RequireInactiveVillager -eq 1) { 0 } else { 4 }
$spigotConfig = @(
    'config-version: 13'
    'world-settings:'
    '  default:'
    '    entity-activation-range:'
    "      villagers: $VillagerActivationRange"
    "      villagers-active-for-panic: $villagersActiveForPanicText"
    "      tick-inactive-villagers: $tickInactiveVillagersText"
    '      wake-up-inactive:'
    "        villagers-max-per-tick: $wakeUpVillagersPerTick"
) -join "`r`n"
[System.IO.File]::WriteAllText((Join-Path $instancePath 'spigot.yml'), $spigotConfig + "`r`n", $utf8)

$serverProperties = @(
    'allow-flight=true'
    'difficulty=hard'
    'enable-status=false'
    'generate-structures=false'
    'level-name=world'
    'level-seed=LeavesX-villager-iron-golem-regression'
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
$state = 'boot'
$summonedVillagers = 0
$sawHostile = $false
$sawGolem = $false
$sawVillagersAlive = $false
$sawInactiveVillager = $false
$commandsSent = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
$sleepCheckAttempt = 0
$chunkReadyCheckAttempt = 0
$golemCheckAttempt = 0
$chunkReadyCheckAt = $null
$chunkReadyDeadline = $null
$golemDeadline = $null

function Send-Commands {
    param([string[]]$Commands)

    foreach ($command in $Commands) {
        $process.StandardInput.WriteLine($command)
    }
    $process.StandardInput.Flush()
}

function Send-Once {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Key,

        [Parameter(Mandatory = $true)]
        [string[]]$Commands
    )

    if ($commandsSent.Add($Key)) {
        Send-Commands $Commands
        return $true
    }
    return $false
}

function Start-HostileScenario {
    Send-Once 'zombie' @(
        # Keep the hostile in the same open line of sight as the first villager. Placing it behind a bed
        # makes vanilla visibility fail and turns this into a fixture test rather than an AI test.
        # Iron-farm villagers are normally confined; zero movement prevents random scattering while
        # still exercising sensor memories, panic activity and the natural golem-spawn decision.
        'tp @e[type=minecraft:villager,tag=lx_iron_villager_1,limit=1] 0.5 101 2.5'
        'tp @e[type=minecraft:villager,tag=lx_iron_villager_2,limit=1] 3.5 101 2.5'
        'tp @e[type=minecraft:villager,tag=lx_iron_villager_3,limit=1] 6.5 101 2.5'
        # The attribute command accepts one target. Execute it once per villager so
        # the selector cannot fail and silently leave the fixture free to wander.
        'execute as @e[type=minecraft:villager,tag=lx_iron_villager] run attribute @s minecraft:movement_speed base set 0'
        'fill -1 103 5 8 103 7 minecraft:stone'
        'summon minecraft:zombie 3.5 101 6.5 {NoAI:1b,Silent:1b,Invulnerable:1b,PersistenceRequired:1b,Tags:["lx_iron_hostile"]}'
        'execute if entity @e[type=minecraft:zombie,tag=lx_iron_hostile,limit=1] run say LX_HOSTILE_ALIVE'
        'data get entity @e[type=minecraft:zombie,tag=lx_iron_hostile,limit=1] Pos'
    ) | Out-Null
    $script:state = 'zombie'
}

try {
    if (-not $process.Start()) {
        throw 'Failed to start LeavesX.'
    }
    $processStarted = $true
    $errorTask = $process.StandardError.ReadToEndAsync()
    $consoleWriter = [System.IO.StreamWriter]::new($consoleLog, $false, $utf8)
    $consoleWriter.AutoFlush = $true

    # Consume stdout synchronously. This is intentionally compatible with Windows PowerShell 5,
    # where Process.OutputDataReceived is unreliable during JVM startup.
    while ($null -ne ($line = $process.StandardOutput.ReadLine())) {
        $consoleWriter.WriteLine($line)

        if ($line -match 'Done \(.+\)! For help') {
            # The fixture spans negative and positive X/Z, so load all four covered chunks.
            Send-Once 'forceload' @('forceload add -16 -16 15 15') | Out-Null
            $state = 'forceload'
        }

        if ($state -eq 'forceload' -and $line -match 'Marked .+chunk') {
            # Let the four force-load tickets finish before probing their FULL status.
            Start-Sleep -Seconds 5
            # 26.1.2 renamed the rule from doDaylightCycle to advance_time.
            Send-Once 'setup' @(
                'gamerule advance_time false'
                'scoreboard objectives add lx_regression dummy'
            ) | Out-Null
            $state = 'objective'
        }

        if ($state -eq 'objective' -and $line -match 'Created new objective') {
            # Chunk tickets are installed asynchronously. Wait until the target chunk is
            # actually present before issuing block/entity commands; otherwise the fixture can
            # silently drop commands and produce a false AI failure.
            $chunkReadyCheckAt = [DateTime]::UtcNow
            $chunkReadyDeadline = [DateTime]::UtcNow.AddSeconds(30)
            ++$chunkReadyCheckAttempt
            Send-Once "chunk-ready-check-$chunkReadyCheckAttempt" @(
                # `execute if loaded` accepts block positions. Check every corner so a
                # partially loaded platform cannot create a false villager-AI failure.
                'scoreboard players set #loaded_chunks lx_regression 0'
                'execute if loaded -8 100 -8 run scoreboard players add #loaded_chunks lx_regression 1'
                'execute if loaded 12 100 -8 run scoreboard players add #loaded_chunks lx_regression 1'
                'execute if loaded -8 100 8 run scoreboard players add #loaded_chunks lx_regression 1'
                'execute if loaded 12 100 8 run scoreboard players add #loaded_chunks lx_regression 1'
                'execute if score #loaded_chunks lx_regression matches 4 run say LX_CHUNK_READY'
                'execute unless score #loaded_chunks lx_regression matches 4 run say LX_CHUNK_NOT_READY'
            ) | Out-Null
            $state = 'chunk-ready-marker'
        }

        # Match only the server's successful `say` output. Command echoes and parser
        # diagnostics may contain the marker text but do not prove that the chunk is ready.
        if ($state -eq 'chunk-ready-marker' -and $line -match '\[Server\] LX_CHUNK_READY\s*$') {
            Send-Once 'fixture' @(
                'kill @e[type=minecraft:villager]'
                'kill @e[type=minecraft:zombie]'
                'kill @e[type=minecraft:iron_golem]'
                'fill -8 100 -8 12 100 8 minecraft:stone'
                'fill -8 101 -8 12 110 8 minecraft:air'
                # Keep the villagers inside the fixture without changing their AI. This
                # mirrors an enclosed iron farm and prevents random walks off the platform.
                'fill -8 101 -8 12 102 -8 minecraft:stone'
                'fill -8 101 8 12 102 8 minecraft:stone'
                'fill -8 101 -7 -8 102 7 minecraft:stone'
                'fill 12 101 -7 12 102 7 minecraft:stone'
                'setblock 0 101 0 minecraft:red_bed[part=foot,facing=south]'
                'setblock 0 101 1 minecraft:red_bed[part=head,facing=south]'
                'setblock 3 101 0 minecraft:red_bed[part=foot,facing=south]'
                'setblock 3 101 1 minecraft:red_bed[part=head,facing=south]'
                'setblock 6 101 0 minecraft:red_bed[part=foot,facing=south]'
                'setblock 6 101 1 minecraft:red_bed[part=head,facing=south]'
                'summon minecraft:villager 0.5 101 2.5 {PersistenceRequired:1b,Invulnerable:1b,Tags:["lx_villager","lx_iron_villager","lx_iron_villager_1"]}'
                'summon minecraft:villager 3.5 101 2.5 {PersistenceRequired:1b,Invulnerable:1b,Tags:["lx_iron_villager","lx_iron_villager_2"]}'
                'summon minecraft:villager 6.5 101 2.5 {PersistenceRequired:1b,Invulnerable:1b,Tags:["lx_iron_villager","lx_iron_villager_3"]}'
                'time set midnight'
                # A force-loaded chunk settles at BLOCK_TICKING when no player is nearby. Keep a
                # real Leaves fakeplayer in range so this fixture exercises entity AI, just like
                # an iron farm on a live server, instead of testing a non-ticking chunk.
                'bot create lx_probe lx_probe minecraft:overworld 10.5 101 2.5'
            ) | Out-Null
            $state = 'villagers'
        }

        if ($state -eq 'chunk-ready-marker' -and $line -match '\[Server\] LX_CHUNK_NOT_READY\s*$') {
            if ([DateTime]::UtcNow -ge $chunkReadyDeadline) {
                throw 'The regression chunk did not become loaded within 30 seconds.'
            }
            throw 'The complete four-chunk regression fixture was not loaded after five seconds.'
        }

        if ($state -eq 'villagers' -and $line -match 'Summoned new Villager') {
            ++$summonedVillagers
            if ($summonedVillagers -ge 3) {
                $state = 'sleeping'
                # Wait for game progress instead of assuming wall-clock time equals a fixed number of ticks.
                $sleepCheckAt = [DateTime]::UtcNow.AddSeconds(5)
                $sleepDeadline = [DateTime]::UtcNow.AddSeconds(90)
            }
        }

        if ($state -eq 'sleeping' -and [DateTime]::UtcNow -ge $sleepCheckAt) {
            ++$sleepCheckAttempt
            Send-Once "sleep-check-$sleepCheckAttempt" @(
                'scoreboard players set #sleeping lx_regression 0'
                'execute as @e[type=minecraft:villager,tag=lx_iron_villager] if data entity @s sleeping_pos run scoreboard players add #sleeping lx_regression 1'
                'execute if score #sleeping lx_regression matches 3 run say LX_ALL_VILLAGERS_SLEPT'
                'scoreboard players get #sleeping lx_regression'
            ) | Out-Null
            $state = 'sleep-marker'
        }

        if ($state -eq 'sleep-marker' -and $line -match 'LX_ALL_VILLAGERS_SLEPT') {
            Send-Once 'wake-time' @('time set day') | Out-Null
            $wakeCheckAt = [DateTime]::UtcNow.AddSeconds(3)
            $state = 'wake'
        }

        if ($state -eq 'sleep-marker' -and [DateTime]::UtcNow -ge $sleepCheckAt.AddSeconds(3)) {
            if ([DateTime]::UtcNow -ge $sleepDeadline) {
                throw 'The regression villagers did not all claim beds within 90 seconds.'
            }
            $sleepCheckAt = [DateTime]::UtcNow.AddSeconds(2)
            $state = 'sleeping'
        }

        if ($state -eq 'wake' -and [DateTime]::UtcNow -ge $wakeCheckAt) {
            if ($RequireInactiveVillager -eq 1) {
                # Stop fixture movement without disabling AI. Once the new-entity grace period expires, the probe
                # must observe a stale activated tick before the zombie is introduced.
                Send-Once 'prepare-inactive' @(
                    'execute as @e[type=minecraft:villager,tag=lx_iron_villager] run attribute @s minecraft:movement_speed base set 0'
                ) | Out-Null
                $inactiveDeadline = [DateTime]::UtcNow.AddSeconds(40)
                $state = 'inactive-wait'
            } else {
                Start-HostileScenario
            }
        }

        if ($state -eq 'inactive-wait' -and $line -match 'LeavesXBrainProbe.*PROBE tick=(\d+).*activated-tick=(-?\d+).*tick-inactive-villagers=false') {
            $probeTick = [int64]$Matches[1]
            $activatedTick = [int64]$Matches[2]
            if ($probeTick - $activatedTick -ge 40) {
                $sawInactiveVillager = $true
                Start-HostileScenario
            }
        }

        if ($state -eq 'inactive-wait' -and [DateTime]::UtcNow -ge $inactiveDeadline) {
            throw 'The fixture villager did not become inactive within 40 seconds.'
        }

        if ($state -eq 'zombie' -and $line -match 'Summoned new Zombie') {
            $hostileDeadline = [DateTime]::UtcNow.AddSeconds(20)
            $state = 'hostile'
        }

        if ($state -eq 'hostile' -and $line -match 'LeavesXBrainProbe.*hostile=True') {
            $sawHostile = $true
            # Natural iron-golem attempts are randomized. Poll for a bounded interval
            # instead of treating one fixed observation point as authoritative.
            $golemCheckAt = [DateTime]::UtcNow.AddSeconds(5)
            $golemDeadline = [DateTime]::UtcNow.AddSeconds(60)
            $state = 'golem-wait'
        }

        if ($state -eq 'hostile' -and [DateTime]::UtcNow -ge $hostileDeadline) {
            throw 'The villagers did not observe the hostile zombie within 20 seconds.'
        }

        if ($state -eq 'golem-wait' -and [DateTime]::UtcNow -ge $golemCheckAt) {
            ++$golemCheckAttempt
            Send-Once "golem-check-$golemCheckAttempt" @(
                # Capture every villager's sleep age, hostile memory, activation state and
                # current golem eligibility. This keeps intermittent failures diagnosable
                # without changing any entity or world state.
                'leavesx villagers 3'
                'execute if entity @e[type=minecraft:iron_golem] run say LX_IRON_GOLEM_SPAWNED'
                'execute unless entity @e[type=minecraft:iron_golem] run say LX_IRON_GOLEM_PENDING'
            ) | Out-Null
            $state = 'golem-marker'
        }

        if ($state -eq 'golem-marker' -and $line -match '\[Server\] LX_IRON_GOLEM_SPAWNED\s*$') {
            $sawGolem = $true
            Send-Once 'inspect' @(
                'execute if entity @e[type=minecraft:villager,tag=lx_iron_villager] run say LX_VILLAGERS_ALIVE'
                'bot remove lx_probe'
                'stop'
            ) | Out-Null
            $state = 'inspect'
        }

        if ($state -eq 'golem-marker' -and $line -match '\[Server\] LX_IRON_GOLEM_PENDING\s*$') {
            if ([DateTime]::UtcNow -ge $golemDeadline) {
                throw 'The panic cycle did not naturally spawn an iron golem within 60 seconds.'
            }
            $golemCheckAt = [DateTime]::UtcNow.AddSeconds(2)
            $state = 'golem-wait'
        }

        if ($line -match '\[Server\] LX_VILLAGERS_ALIVE\s*$') {
            $sawVillagersAlive = $true
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
    if (-not $normalStop) {
        throw 'LeavesX did not start and stop normally.'
    }

    $consoleWriter.Dispose()
    $consoleWriter = $null
    $consoleText = [System.IO.File]::ReadAllText($consoleLog, $utf8)
    if (-not $consoleText.Contains('LX_ALL_VILLAGERS_SLEPT')) {
        throw 'All three villagers did not naturally claim beds and sleep.'
    }
    if (-not $sawHostile -or -not ($consoleText -match 'activity=\[core, panic\]')) {
        throw 'The villagers never entered the panic activity after observing the hostile zombie.'
    }
    if (-not $consoleText.Contains('LX_HOSTILE_ALIVE')) {
        throw 'The hostile zombie was not alive when the panic probe started.'
    }
    if (-not $sawVillagersAlive) {
        throw 'The regression villagers were not alive when inspected.'
    }
    if ($RequireInactiveVillager -eq 1 -and -not $sawInactiveVillager) {
        throw 'The regression never observed an inactive villager.'
    }
    if (-not $sawGolem) {
        throw 'The panic cycle did not naturally spawn an iron golem.'
    }
    if ($consoleText -match 'Entity threw exception|Unregistered memory fetched|The server has not responded|Only one entity is allowed|Can''t find element') {
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
