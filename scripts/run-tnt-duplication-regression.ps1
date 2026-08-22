[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$ServerJar,

    [Parameter(Mandatory = $true)]
    [string]$InstanceDirectory,

    [Parameter(Mandatory = $true)]
    [int]$Port,

    [switch]$WithoutLeavesXSettings,

    [string]$SeedDirectory,

    [ValidateSet('VANILLA', 'ALTERNATE_CURRENT', 'EIGENCRAFT')]
    [string]$RedstoneImplementation = 'VANILLA'
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

$probeJar = Join-Path $smokeRoot 'LeavesXTntDuplicationProbe.jar'
if (-not (Test-Path -LiteralPath $probeJar -PathType Leaf)) {
    & (Join-Path $PSScriptRoot 'build-tnt-duplication-probe.ps1') -OutputJar $probeJar
}

[System.IO.Directory]::CreateDirectory($instancePath) | Out-Null
[System.IO.Directory]::CreateDirectory((Join-Path $instancePath 'plugins')) | Out-Null
Copy-Item -LiteralPath $probeJar -Destination (Join-Path $instancePath 'plugins')

if ($SeedDirectory) {
    $seedPath = (Resolve-Path -LiteralPath $SeedDirectory).Path
    foreach ($directoryName in @('cache', 'libraries', 'versions', 'config')) {
        $sourceDirectory = Join-Path $seedPath $directoryName
        if (Test-Path -LiteralPath $sourceDirectory -PathType Container) {
            Copy-Item -LiteralPath $sourceDirectory -Destination $instancePath -Recurse
        }
    }
}

$worldDefaultsPath = Join-Path $instancePath 'config\paper-world-defaults.yml'
if (Test-Path -LiteralPath $worldDefaultsPath -PathType Leaf) {
    $worldDefaults = [System.IO.File]::ReadAllText($worldDefaultsPath, $utf8)
    $worldDefaults = [regex]::Replace(
        $worldDefaults,
        '(?m)^(\s*redstone-implementation:\s*).+$',
        "`$1$RedstoneImplementation"
    )
    [System.IO.File]::WriteAllText($worldDefaultsPath, $worldDefaults, $utf8)
}

[System.IO.File]::WriteAllText((Join-Path $instancePath 'eula.txt'), "eula=true`r`n", $utf8)
$serverProperties = @(
    'allow-flight=true'
    'difficulty=hard'
    'enable-status=false'
    'generate-structures=false'
    'level-name=world'
    'level-seed=LeavesX-tnt-duplication-regression'
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
$leavesXArgument = if ($WithoutLeavesXSettings) { '' } else { ' --leavesx-settings leavesx.yml' }
$startInfo.Arguments = "-Xms512M -Xmx1G -Dfile.encoding=UTF-8 -jar `"$serverJarPath`" --nogui --leaves-settings leaves.yml$leavesXArgument"
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
$sawPass = $false
$sawReload = $false
$sawPistonDuplication = $false
$sawStartupPistonDuplication = $false
$sawStartupPistonSetting = $false
$reachedDone = $false
$deadline = [DateTime]::UtcNow.AddMinutes(3)

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
        if (-not $reachedDone -and $line -match 'TNT/.+[:：].*') {
            $sawStartupPistonDuplication = $true
        }
        if (-not $reachedDone -and $line -match 'LX_PISTON_SETTING startup=true') {
            $sawStartupPistonSetting = $true
        }
        if (-not $reachedDone -and $line -match 'Done \(.+\)! For help') {
            $reachedDone = $true
            # Exercise both configuration reload paths before the fixture starts. The
            # technical-mode override must remain effective after Paper and Leaves reloads.
            $process.StandardInput.WriteLine('paper reload confirm')
            $process.StandardInput.WriteLine('leaves reload')
            $process.StandardInput.WriteLine('leavesx config')
            $process.StandardInput.Flush()
        }
        if ($line -match 'Leaves .* LeavesX .*') {
            $sawReload = $true
        }
        if ($line -match 'TNT/.+[:：].*') {
            $sawPistonDuplication = $true
        }
        if ($line -match 'LX_TNT_RESULT status=PASS') {
            $sawPass = $true
        }
        if ($line -match 'Stopping (the )?server') {
            $normalStop = $true
        }
        if ([DateTime]::UtcNow -ge $deadline) {
            throw 'The TNT regression did not finish within three minutes.'
        }
    }

    $process.WaitForExit()
    [System.IO.File]::WriteAllText($errorLog, $errorTask.Result, $utf8)
    if ($process.ExitCode -ne 0) {
        throw "LeavesX exited with code $($process.ExitCode)."
    }
    if (-not $reachedDone -or -not $normalStop -or -not $sawPass -or -not $sawReload -or -not $sawPistonDuplication -or -not $sawStartupPistonDuplication -or -not $sawStartupPistonSetting) {
        throw 'The live TNT duplication fixture did not pass.'
    }

    $consoleWriter.Dispose()
    $consoleWriter = $null
    $consoleText = [System.IO.File]::ReadAllText($consoleLog, $utf8)
    if ($consoleText -match 'LX_TNT_RESULT status=FAIL|Entity threw exception|The server has not responded') {
        throw 'The TNT regression console contains a fixture or server failure.'
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
