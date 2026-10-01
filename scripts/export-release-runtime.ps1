[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$utf8 = [Text.UTF8Encoding]::new($false)

function Invoke-Git([string]$Repository, [string[]]$Arguments) {
    $result = & git -C $Repository @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Git failed: $($Arguments -join ' ')" }
    return $result
}

function Export-Runtime([string]$RepositoryPath, [string]$PatchPath, [string[]]$Scope) {
    $repository = Join-Path $root $RepositoryPath
    $patchRoot = Join-Path $root $PatchPath
    $features = @(Get-ChildItem (Join-Path $patchRoot 'features') -Filter '*.patch' | Sort-Object Name)
    $subject = Invoke-Git $repository @('log', '-1', '--format=%s')
    $boundary = @($features | Where-Object {
        (Get-Content -LiteralPath $_.FullName -TotalCount 5) -contains "Subject: [PATCH] $subject"
    })
    if ($boundary.Count -ne 1) { throw "Unknown applied patch boundary: $subject" }
    $scratch = Join-Path $root ('build/release-patches/' + [Guid]::NewGuid().ToString('N'))
    [IO.Directory]::CreateDirectory($scratch) | Out-Null
    $previousIndex = $env:GIT_INDEX_FILE
    try {
        # Never stage, reset or commit the developer's real index.
        $env:GIT_INDEX_FILE = Join-Path $scratch 'index'
        Invoke-Git $repository @('read-tree', 'HEAD') | Out-Null
        foreach ($feature in $features | Where-Object { $_.Name -gt $boundary[0].Name }) {
            Invoke-Git $repository @('apply', '--cached', '--whitespace=nowarn', $feature.FullName) | Out-Null
        }
        $diffPath = Join-Path $scratch 'runtime.diff'
        Invoke-Git $repository (@('diff', '--binary', '--no-ext-diff', '--no-color', "--output=$diffPath", '--') + $Scope) | Out-Null
        $diff = [IO.File]::ReadAllText($diffPath)
        if ($diff.Length -gt 0) {
            $number = 1 + [int]$features[-1].Name.Substring(0, 4)
            $output = Join-Path $patchRoot ('features/{0:D4}-Complete-26.2-release-runtime.patch' -f $number)
            $header = "From 0000000000000000000000000000000000000000 Mon Sep 17 00:00:00 2001`nFrom: LeavesX <build@leavesx.local>`nDate: Thu, 1 Oct 2026 00:00:00 +0800`nSubject: [PATCH] Complete 26.2 release runtime`n`n"
            [IO.File]::WriteAllText($output, $header + $diff, $utf8)
            Invoke-Git $repository @('apply', '--cached', '--whitespace=nowarn', $output) | Out-Null
            Invoke-Git $repository (@('diff', '--exit-code', '--quiet', '--') + $Scope) | Out-Null
            Write-Output "Exported and replay-checked: $output"
        }
        foreach ($relative in @(Invoke-Git $repository (@('ls-files', '--others', '--exclude-standard', '--') + $Scope))) {
            if (!$relative.EndsWith('.java')) { continue }
            $lines = [IO.File]::ReadAllLines((Join-Path $repository $relative))
            $output = Join-Path $patchRoot ('files/' + $relative + '.patch')
            [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($output)) | Out-Null
            $patch = "--- /dev/null`n+++ b/$relative`n@@ -1,0 +_,$($lines.Length) @@`n" + (($lines | ForEach-Object { '+' + $_ }) -join "`n") + "`n"
            [IO.File]::WriteAllText($output, $patch, $utf8)
        }
    } finally {
        $env:GIT_INDEX_FILE = $previousIndex
    }
}

Export-Runtime 'leaves-server/src/minecraft/java' 'leaves-server/minecraft-patches' @('ca', 'net')
Export-Runtime 'paper-server' 'leaves-server/paper-patches' @('src/main', 'src/test')
