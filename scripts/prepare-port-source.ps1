[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Destination
)

$ErrorActionPreference = 'Stop'
$sourceRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$targetRoot = (Resolve-Path -LiteralPath $Destination).Path
$utf8 = [Text.UTF8Encoding]::new($false)

function Invoke-Git([string]$Repository, [string[]]$Arguments) {
    $result = & git -C $Repository @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Git failed in ${Repository}: $($Arguments -join ' ')" }
    return $result
}

function Target-Path([string]$Relative) {
    $path = [IO.Path]::GetFullPath((Join-Path $targetRoot $Relative))
    if (!$path.StartsWith($targetRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Path is outside the destination worktree: $Relative"
    }
    return $path
}

if ($sourceRoot.Equals($targetRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Use a separate, clean worktree; never prepare a port over the active source.'
}
$targetGitRoot = Invoke-Git $targetRoot @('rev-parse', '--show-toplevel')
if (![IO.Path]::GetFullPath($targetGitRoot).Equals($targetRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Destination must be the root of a Git worktree.'
}
if (@(Invoke-Git $targetRoot @('status', '--porcelain')).Count -ne 0) {
    throw 'Destination has local changes; refusing to overwrite them.'
}
$sourceHead = Invoke-Git $sourceRoot @('rev-parse', 'HEAD')
if ((Invoke-Git $targetRoot @('rev-parse', 'HEAD')) -ne $sourceHead) {
    throw 'Destination must start at the source HEAD before copying uncommitted work.'
}

# Copy canonical sources, not caches, worlds, compiled jars, or sibling version repositories.
$scope = @(
    '.editorconfig', '.gitattributes', '.gitignore', 'build.gradle.kts', 'settings.gradle.kts',
    'gradle.properties', 'gradlew', 'gradlew.bat', 'gradle', 'build-data', 'licenses',
    'LICENSE.md', 'README.md', 'README_EN.md', 'leaves-api', 'leaves-server', 'scripts',
    'docs/leavesx.example.yml'
)
$paths = @(Invoke-Git $sourceRoot (@('-c', 'core.quotepath=false', 'ls-files', '--cached', '--others', '--exclude-standard', '--') + $scope) | Sort-Object -Unique)
$manifest = [Collections.Generic.List[object]]::new()
foreach ($relative in $paths) {
    $sourceFile = Join-Path $sourceRoot $relative
    if (!(Test-Path -LiteralPath $sourceFile -PathType Leaf)) { continue }
    $targetFile = Target-Path $relative
    [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($targetFile)) | Out-Null
    Copy-Item -LiteralPath $sourceFile -Destination $targetFile
    $hash = (Get-FileHash -LiteralPath $sourceFile -Algorithm SHA256).Hash
    if ((Get-FileHash -LiteralPath $targetFile -Algorithm SHA256).Hash -ne $hash) {
        throw "Source changed during snapshot: $relative"
    }
    $manifest.Add([pscustomobject]@{ path = $relative; sha256 = $hash })
}
# Remove only tracked files already deleted/renamed in the source, and only in the new worktree.
foreach ($relative in @(Invoke-Git $sourceRoot (@('-c', 'core.quotepath=false', 'diff', '--name-only', '--diff-filter=D', 'HEAD', '--') + $scope))) {
    $targetFile = Target-Path $relative
    if (Test-Path -LiteralPath $targetFile -PathType Leaf) { Remove-Item -LiteralPath $targetFile }
}

function Export-AppliedChanges([string]$RepositoryRelative, [string]$PatchRelative, [string[]]$SourcePaths) {
    $repository = Join-Path $sourceRoot $RepositoryRelative
    $patchRoot = Target-Path $PatchRelative
    $subject = Invoke-Git $repository @('log', '-1', '--format=%s')
    $features = @(Get-ChildItem -LiteralPath (Join-Path $patchRoot 'features') -Filter '*.patch' | Sort-Object Name)
    $applied = @($features | Where-Object {
        (Get-Content -LiteralPath $_.FullName -TotalCount 5) -contains "Subject: [PATCH] $subject"
    })
    if ($applied.Count -ne 1) { throw "Cannot locate applied patch boundary: $subject" }
    $scratch = Target-Path ('build/port-source/' + [Guid]::NewGuid().ToString('N'))
    [IO.Directory]::CreateDirectory($scratch) | Out-Null
    $previousIndex = $env:GIT_INDEX_FILE
    try {
        # An independent index lets us account for canonical hotfix patches without touching source staging.
        $env:GIT_INDEX_FILE = Join-Path $scratch 'index'
        Invoke-Git $repository @('read-tree', 'HEAD') | Out-Null
        foreach ($patch in $features | Where-Object { $_.Name -gt $applied[0].Name }) {
            Invoke-Git $repository @('apply', '--cached', '--whitespace=nowarn', $patch.FullName) | Out-Null
        }
        $rawDiff = Join-Path $scratch 'pending.diff'
        Invoke-Git $repository (@('diff', '--binary', '--no-ext-diff', '--no-color', "--output=$rawDiff", '--') + $SourcePaths) | Out-Null
        $diff = [IO.File]::ReadAllText($rawDiff)
        if ($diff.Length -gt 0) {
            $nextNumber = 1 + [int]$features[-1].Name.Substring(0, 4)
            $output = Join-Path $patchRoot ('features/{0:D4}-Preserve-current-runtime-before-version-port.patch' -f $nextNumber)
            $header = "From 0000000000000000000000000000000000000000 Mon Sep 17 00:00:00 2001`nFrom: LeavesX Build <build@leavesx.local>`nDate: Sat, 26 Sep 2026 00:00:00 +0800`nSubject: [PATCH] Preserve current runtime before version port`n`n"
            [IO.File]::WriteAllText($output, $header + $diff, $utf8)
            Write-Output "Exported pending runtime changes: $output"
        }
    } finally {
        $env:GIT_INDEX_FILE = $previousIndex
    }
    # New applied classes have no HEAD version. Preserve them as paperweight file patches.
    foreach ($relative in @(Invoke-Git $repository (@('ls-files', '--others', '--exclude-standard', '--') + $SourcePaths))) {
        if (!$relative.EndsWith('.java')) { continue }
        $lines = [IO.File]::ReadAllLines((Join-Path $repository $relative))
        $output = Join-Path $patchRoot ('files/' + $relative + '.patch')
        [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($output)) | Out-Null
        $text = "--- /dev/null`n+++ b/$relative`n@@ -1,0 +_,$($lines.Length) @@`n" + (($lines | ForEach-Object { '+' + $_ }) -join "`n") + "`n"
        [IO.File]::WriteAllText($output, $text, $utf8)
    }
}

Export-AppliedChanges 'leaves-server/src/minecraft/java' 'leaves-server/minecraft-patches' @('ca', 'net')
Export-AppliedChanges 'paper-server' 'leaves-server/paper-patches' @('src/main', 'src/test')
[IO.File]::WriteAllText((Target-Path 'build/port-source-manifest.json'), ($manifest | ConvertTo-Json -Depth 3), $utf8)
Write-Output "Copied and hash-checked $($manifest.Count) canonical source files. Original source and staging were not modified."
