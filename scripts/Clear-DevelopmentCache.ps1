[CmdletBinding(SupportsShouldProcess = $true)]
param([switch]$Apply)

$ErrorActionPreference = 'Stop'
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..')).TrimEnd('\')
$relativeTargets = @(
    'build/smoke-1.2.1-26.1.2/cache',
    'build/smoke-1.2.1-26.1.2/libraries',
    'build/smoke-1.2.1-26.1.2/versions',
    'build/smoke-1.2.1-26.2/cache',
    'build/smoke-1.2.1-26.2/libraries',
    'build/smoke-1.2.1-26.2/versions',
    'build/smoke-1.3.2-beta2-pwt-leaf/cache',
    'build/smoke-1.3.2-beta2-pwt-leaf/libraries',
    'build/smoke-1.3.2-beta2-pwt-leaf/versions',
    'build/smoke-1.3.2-beta2-pwt-leaf-verified/cache',
    'build/smoke-1.3.2-beta2-pwt-leaf-verified/libraries',
    'build/smoke-1.3.2-beta2-pwt-leaf-verified/versions',
    'build/LeavesX-1.2.1-beta4-26.1.2.jar',
    'build/LeavesX-1.2.2-beta1-26.1.2.jar',
    'build/LeavesX-1.3.2-beta2-26.1.2.jar',
    'build/LeavesX-1.3.2-beta3-26.1.2-entity-pipeline.jar',
    'build/LeavesX-1.3.2-beta3-26.1.2-protocol-fix.jar',
    'build/LeavesX-1.3.2-beta3-26.1.2-rebuild.jar',
    'build/LeavesX-1.3.2-beta3-26.1.2-xp-diagnostics.jar',
    'build/LeavesX-1.3.2-beta3-26.1.2-xp-visibility.jar',
    'build/LeavesX-1.3.2-beta3-26.1.2.jar'
)

# 只处理已核对的旧产物，不删除源码、世界、配置、验证日志或当前构建依赖。
$runningJava = @(Get-CimInstance Win32_Process | Where-Object { $_.Name -in @('java.exe', 'javaw.exe') })
$plan = foreach ($relative in $relativeTargets) {
    $candidate = Join-Path $workspace $relative
    if (!(Test-Path -LiteralPath $candidate)) { continue }
    $resolved = (Resolve-Path -LiteralPath $candidate).Path
    if (!$resolved.StartsWith($workspace + '\build\', [StringComparison]::OrdinalIgnoreCase)) {
        throw "清理目标不在项目 build 目录：$resolved"
    }
    # 检查每层父目录和目标中的链接，避免经由目录联接越过已确认的范围。
    $parent = Get-Item -LiteralPath $resolved -Force
    while ($null -ne $parent -and $parent.FullName -ne $workspace) {
        if ($parent.Attributes -band [IO.FileAttributes]::ReparsePoint) {
            throw "清理目标包含目录联接或链接：$($parent.FullName)"
        }
        $parent = if ($parent -is [IO.DirectoryInfo]) { $parent.Parent } else { $parent.Directory }
    }
    if ($null -eq $parent) { throw "清理目标没有到达项目根目录：$resolved" }
    $entry = Get-Item -LiteralPath $resolved -Force
    if ($entry.PSIsContainer -and (Get-ChildItem -LiteralPath $resolved -Force -Recurse -Attributes ReparsePoint)) {
        throw "清理目录中包含链接，已保留：$resolved"
    }
    foreach ($process in $runningJava) {
        if ($process.CommandLine -and $process.CommandLine.IndexOf($resolved, [StringComparison]::OrdinalIgnoreCase) -ge 0) {
            throw "目标仍被 Java 进程使用，已保留：$resolved"
        }
    }
    $bytes = if ($entry.PSIsContainer) {
        (Get-ChildItem -LiteralPath $resolved -File -Recurse -Force | Measure-Object Length -Sum).Sum
    } else { $entry.Length }
    [pscustomobject]@{ Path = $resolved; Directory = $entry.PSIsContainer; Bytes = [long]$bytes }
}

$plan | Select-Object Path, @{ Name = 'MiB'; Expression = { [math]::Round($_.Bytes / 1MB, 1) } } | Format-Table -AutoSize
$total = ($plan | Measure-Object Bytes -Sum).Sum
Write-Output ('可清理 {0} 项，共 {1:N2} GiB。' -f @($plan).Count, ($total / 1GB))
if (!$Apply) {
    Write-Output '当前仅预览；确认清单后加 -Apply 执行。删除的依赖可重新下载，旧 JAR 可重新构建。'
    return
}
foreach ($item in $plan) {
    if ($PSCmdlet.ShouldProcess($item.Path, '删除已核对的旧缓存或构建产物')) {
        Remove-Item -LiteralPath $item.Path -Recurse:$item.Directory -Force
    }
}
