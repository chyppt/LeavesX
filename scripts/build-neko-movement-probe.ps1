[CmdletBinding()]
param(
    [string]$NekoJar = 'D:\Users\Administrator\Desktop\NekoAntiCheat\[FN]NekoAntiCheat-2.9.8-Loader (1).jar',
    [string]$OutputJar = 'build\LeavesXNekoMovementProbe.jar'
)

$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$javaBin = Join-Path $env:JAVA_HOME 'bin'
$classes = Join-Path $root 'build\neko-movement-probe\classes'
$output = [IO.Path]::GetFullPath((Join-Path $root $OutputJar))

if (-not (Test-Path -LiteralPath $NekoJar -PathType Leaf)) { throw "Neko JAR 未找到：$NekoJar" }
New-Item -ItemType Directory -Path $classes -Force | Out-Null

# 此探针只编译现有 Leaves API 和随插件发布的 Neko API，不改写或反编译源 JAR。
$classpath = @(
    (Join-Path $root 'leaves-api\build\libs\leaves-api-26.1.2-R0.1-SNAPSHOT.jar'),
    $NekoJar
)
$classpath += Get-ChildItem (Join-Path $root 'libraries') -Recurse -Filter '*.jar' -File |
    ForEach-Object { $_.FullName }
$sources = Get-ChildItem (Join-Path $PSScriptRoot 'integration-probes\neko\src') -Recurse -Filter '*.java' -File |
    ForEach-Object { $_.FullName }

$compiler = [Diagnostics.ProcessStartInfo]::new((Join-Path $javaBin 'javac.exe'))
$compiler.WorkingDirectory = $root
$compiler.UseShellExecute = $false
$compiler.CreateNoWindow = $true
$compiler.WindowStyle = [Diagnostics.ProcessWindowStyle]::Hidden
$compiler.RedirectStandardOutput = $true
$compiler.RedirectStandardError = $true
foreach ($argument in (@(
    '-encoding', 'UTF-8', '-source', '25', '-target', '25',
    '-classpath', ($classpath -join ';'), '-d', $classes
) + $sources)) { $compiler.ArgumentList.Add($argument) }

$process = [Diagnostics.Process]::Start($compiler)
$stdout = $process.StandardOutput.ReadToEndAsync()
$stderr = $process.StandardError.ReadToEndAsync()
if (-not $process.WaitForExit(60000)) {
    $process.Kill($true)
    throw '探针编译超过 60 秒，已停止。'
}
if ($stdout.Result) { Write-Output $stdout.Result }
if ($stderr.Result) { Write-Output $stderr.Result }
if ($process.ExitCode -ne 0) { throw "探针编译失败，退出码 $($process.ExitCode)" }
$process.Dispose()

$pluginDescriptor = Join-Path $PSScriptRoot 'integration-probes\neko\resources\plugin.yml'
Copy-Item -LiteralPath $pluginDescriptor -Destination $classes
New-Item -ItemType Directory -Path (Split-Path -Parent $output) -Force | Out-Null
$jar = [Diagnostics.ProcessStartInfo]::new((Join-Path $javaBin 'jar.exe'))
$jar.WorkingDirectory = $root
$jar.UseShellExecute = $false
$jar.CreateNoWindow = $true
$jar.WindowStyle = [Diagnostics.ProcessWindowStyle]::Hidden
foreach ($argument in @('--create', '--file', $output, '-C', $classes, '.')) { $jar.ArgumentList.Add($argument) }
$packager = [Diagnostics.Process]::Start($jar)
if (-not $packager.WaitForExit(30000)) { $packager.Kill($true); throw '探针打包超过 30 秒。' }
if ($packager.ExitCode -ne 0) { throw "探针打包失败，退出码 $($packager.ExitCode)" }
$packager.Dispose()
Write-Output "已生成只读诊断插件：$output"
