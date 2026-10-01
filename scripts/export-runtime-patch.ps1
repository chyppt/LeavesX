[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Repository,
    [Parameter(Mandatory)][string]$OutputDirectory,
    [Parameter(Mandatory)][int]$Number,
    [Parameter(Mandatory)][string]$Subject,
    [string[]]$Paths = @('*.java'),
    [string[]]$BasePatches = @()
)
$ErrorActionPreference = 'Stop'
$repositoryPath = (Resolve-Path -LiteralPath $Repository).Path
$outputPath = (Resolve-Path -LiteralPath $OutputDirectory).Path
function Invoke-ExportGit([string[]]$Arguments) {
    $result = & git.exe -C $repositoryPath @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Git export failed: $($Arguments -join ' ')" }
    return $result
}
# A temporary index and commit preserve both HEAD and the developer's real staging area.
$scratch = Join-Path ([IO.Path]::GetTempPath()) ('leavesx-export-' + [Guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($scratch) | Out-Null
$previousIndex = $env:GIT_INDEX_FILE
try {
    $env:GIT_INDEX_FILE = Join-Path $scratch 'index'
    Invoke-ExportGit @('read-tree', 'HEAD') | Out-Null
    # Replay already exported work only in the temporary index. Later exports must not duplicate it.
    foreach ($patch in $BasePatches) {
        $patchPath = (Resolve-Path -LiteralPath $patch).Path
        Invoke-ExportGit @('apply', '--cached', $patchPath) | Out-Null
    }
    $baseTree = Invoke-ExportGit @('write-tree')
    $parent = 'HEAD'
    if ($BasePatches.Count -gt 0) {
        $parent = Invoke-ExportGit @('-c', 'user.name=chyppt', '-c', 'user.email=pptwxo@163.com', 'commit-tree', $baseTree, '-p', 'HEAD', '-m', 'Previously exported runtime patches')
    }
    Invoke-ExportGit (@('add', '-A', '--') + $Paths) | Out-Null
    $tree = Invoke-ExportGit @('write-tree')
    if ($tree -eq $baseTree) { throw 'No source changes to export' }
    $commit = Invoke-ExportGit @('-c', 'user.name=chyppt', '-c', 'user.email=pptwxo@163.com', 'commit-tree', $tree, '-p', $parent, '-m', $Subject)
    Invoke-ExportGit @('format-patch', '-1', $commit, '--start-number', "$Number", '--no-signature', '-o', $outputPath)
} finally {
    $env:GIT_INDEX_FILE = $previousIndex
}
