[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$ServerJar,

    [Parameter(Mandatory = $true)]
    [string]$InstanceDirectory,

    [Parameter(Mandatory = $true)]
    [int]$Port,

    [ValidateRange(1, 16)]
    [int]$Bots = 10,

    [ValidateRange(2, 8192)]
    [int]$Entities = 2048,

    [ValidateRange(10, 120)]
    [int]$ProfileSeconds = 20,

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

if (($Entities -band ($Entities - 1)) -ne 0) {
    throw 'Entities must be a power of two so the deterministic fixture can double to the requested size.'
}
if (-not $instancePath.StartsWith($smokePrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Profile instance must be below $smokeRoot"
}
if (Test-Path -LiteralPath $instancePath) {
    throw "Profile instance already exists: $instancePath"
}
foreach ($command in @('java', 'jcmd', 'jfr')) {
    if ($null -eq (Get-Command $command -ErrorAction SilentlyContinue)) {
        throw "Required JDK command is unavailable: $command"
    }
}

[System.IO.Directory]::CreateDirectory($instancePath) | Out-Null
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
$serverProperties = @(
    'allow-flight=true'
    'difficulty=peaceful'
    'enable-status=false'
    'generate-structures=false'
    'level-name=world'
    'level-seed=LeavesX-activation-allocation-profile'
    'level-type=minecraft:normal'
    'max-players=20'
    'max-tick-time=-1'
    'online-mode=false'
    'pause-when-empty-seconds=-1'
    'server-ip=127.0.0.1'
    "server-port=$Port"
    'simulation-distance=3'
    'spawn-monsters=false'
    'spawn-protection=0'
    'sync-chunk-writes=true'
    'view-distance=3'
) -join "`r`n"
[System.IO.File]::WriteAllText((Join-Path $instancePath 'server.properties'), $serverProperties + "`r`n", $utf8)

$consoleLog = Join-Path $instancePath 'profile-console.log'
$errorLog = Join-Path $instancePath 'profile-error.log'
$recording = Join-Path $instancePath 'activation-allocation.jfr'
$allocationView = Join-Path $instancePath 'allocation-by-site.txt'
$startInfo = [System.Diagnostics.ProcessStartInfo]::new()
$startInfo.FileName = 'java'
$startInfo.Arguments = "-Xms1G -Xmx1G -Dfile.encoding=UTF-8 -jar `"$serverJarPath`" --nogui --leaves-settings leaves.yml --leavesx-settings leavesx.yml"
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
$profileStarted = $false
$remainingOutputTask = $null

function Send-Commands {
    param([string[]]$Commands)

    foreach ($serverCommand in $Commands) {
        $process.StandardInput.WriteLine($serverCommand)
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

    # Read synchronously only until startup completes. The mass-summon fixture produces enough console feedback to
    # fill a redirected pipe, so the stable profiling phase uses ReadToEndAsync to drain stdout continuously.
    while ($null -ne ($line = $process.StandardOutput.ReadLine())) {
        $consoleWriter.WriteLine($line)
        if ($line -match 'Done \(.+\)! For help') {
            break
        }
    }

    if ($process.HasExited) {
        throw 'LeavesX exited before the activation fixture could start.'
    }
    $remainingOutputTask = $process.StandardOutput.ReadToEndAsync()
    Send-Commands @(
        'forceload add 0 0'
        'fill -2 100 -2 2 100 2 minecraft:stone'
        'kill @e[tag=lx_activation_bench]'
        'summon minecraft:marker 0.5 101 0.5 {Tags:["lx_activation_bench"]}'
    )

    $doublingCommands = [System.Collections.Generic.List[string]]::new()
    for ($count = 1; $count -lt $Entities; $count *= 2) {
        $doublingCommands.Add(
            'execute as @e[type=minecraft:marker,tag=lx_activation_bench] at @s run summon minecraft:marker ~ ~ ~ {Tags:["lx_activation_bench"]}'
        )
    }
    for ($index = 0; $index -lt $Bots; ++$index) {
        $doublingCommands.Add("bot create lx_bench_$index lx_bench_$index minecraft:overworld 0.5 101 0.5")
    }
    $doublingCommands.Add('scoreboard objectives add lx_profile dummy')
    $doublingCommands.Add(
        'execute store result score #markers lx_profile if entity @e[type=minecraft:marker,tag=lx_activation_bench]'
    )
    $doublingCommands.Add('scoreboard players get #markers lx_profile')
    Send-Commands $doublingCommands.ToArray()

    # Let entity additions, fake-player chunk tickets and the JIT settle before sampling allocations.
    Start-Sleep -Seconds 10
    & jcmd $process.Id JFR.start name=LeavesXActivation settings=profile "filename=$recording"
    if ($LASTEXITCODE -ne 0) {
        throw 'Unable to start the JFR allocation profile.'
    }
    $profileStarted = $true
    Start-Sleep -Seconds $ProfileSeconds
    & jcmd $process.Id JFR.stop name=LeavesXActivation
    if ($LASTEXITCODE -ne 0) {
        throw 'Unable to stop the JFR allocation profile.'
    }
    # Capture cumulative compute counters before shutdown. The fixture is only useful as a parallelism regression if
    # at least one entity-activation batch actually ran on a worker rather than merely creating an idle thread pool.
    Send-Commands @('leavesx threads')
    Start-Sleep -Seconds 2
    Send-Commands @('stop')

    $process.WaitForExit()
    $remainingOutput = $remainingOutputTask.Result
    $consoleWriter.Write($remainingOutput)
    [System.IO.File]::WriteAllText($errorLog, $errorTask.Result, $utf8)
    if ($process.ExitCode -ne 0) {
        throw "LeavesX exited with code $($process.ExitCode)."
    }
    $normalStop = $remainingOutput -match 'Stopping (the )?server'
    if (-not $profileStarted -or -not $normalStop -or -not (Test-Path -LiteralPath $recording -PathType Leaf)) {
        throw 'LeavesX did not produce a complete activation allocation profile.'
    }
    if ($remainingOutput -notmatch "#markers has $Entities") {
        throw "The activation fixture did not contain exactly $Entities marker entities before profiling."
    }
    $activationMetrics = [regex]::Match(
        $remainingOutput,
        '实体激活范围：(?<calls>\d+) 调用 / (?<parallel>\d+) 并行'
    )
    if (-not $activationMetrics.Success) {
        throw 'The activation profile did not emit LeavesX entity-activation metrics.'
    }
    # Console forwarding can insert a timestamp/prefix between the summary and its detail line. Match the detail
    # independently instead of relying on those two lines remaining adjacent.
    $activationLoad = [regex]::Match(
        $remainingOutput,
        '分片负载：主线程\s*\d+\s*项\s*/\s*工作线程\s*(?<workerItems>\d+)\s*项'
    )
    if (-not $activationLoad.Success) {
        throw 'The activation profile did not emit LeavesX entity-activation worker-load metrics.'
    }
    $parallelInvocations = [long]$activationMetrics.Groups['parallel'].Value
    $workerItems = [long]$activationLoad.Groups['workerItems'].Value
    if ($parallelInvocations -le 0L -or $workerItems -le 0L) {
        throw 'The activation fixture did not execute primitive AABB ranges on LeavesX workers.'
    }

    $consoleWriter.Dispose()
    $consoleWriter = $null
    $allocationLines = & jfr view allocation-by-site $recording
    if ($LASTEXITCODE -ne 0) {
        throw 'Unable to render the JFR allocation-by-site view.'
    }
    [System.IO.File]::WriteAllLines($allocationView, [string[]]$allocationLines, $utf8)
    Write-Output "JFR recording: $recording"
    Write-Output "Allocation view: $allocationView"
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
