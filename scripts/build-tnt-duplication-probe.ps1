[CmdletBinding()]
param(
    [string]$ApiJar,
    [string]$OutputJar
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$utf8 = [System.Text.UTF8Encoding]::new($false)
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$probeRoot = Join-Path $PSScriptRoot 'integration-probes\tnt'
$sourceRoot = Join-Path $probeRoot 'src'
$resourceRoot = Join-Path $probeRoot 'resources'
$fixtureSource = Join-Path $probeRoot 'fixtures\osc-tnt-duper.nbt.b64'
$generatedRoot = Join-Path $projectRoot 'build-smoke\tnt-probe-generated'
$classesRoot = Join-Path $generatedRoot 'classes'
$generatedFixture = Join-Path $generatedRoot 'osc-tnt-duper.nbt'

if (-not $ApiJar) {
    $ApiJar = Join-Path $projectRoot 'leaves-api\build\libs\leaves-api-26.1.2-R0.1-SNAPSHOT.jar'
}
if (-not $OutputJar) {
    $OutputJar = Join-Path $projectRoot 'build-smoke\LeavesXTntDuplicationProbe.jar'
}

$apiJarPath = (Resolve-Path -LiteralPath $ApiJar).Path
if (-not (Test-Path -LiteralPath $fixtureSource -PathType Leaf)) {
    throw "Missing TNT fixture: $fixtureSource"
}

# Generated output is confined to build-smoke. Rebuilding the probe must never touch server data.
if (Test-Path -LiteralPath $generatedRoot) {
    Remove-Item -LiteralPath $generatedRoot -Recurse -Force
}
[System.IO.Directory]::CreateDirectory($classesRoot) | Out-Null

$encodedFixture = ([System.IO.File]::ReadAllText($fixtureSource, $utf8) -replace '\s', '')
[System.IO.File]::WriteAllBytes($generatedFixture, [System.Convert]::FromBase64String($encodedFixture))

$javaSources = @(Get-ChildItem -LiteralPath $sourceRoot -Recurse -Filter '*.java' -File |
    ForEach-Object { [System.IO.Path]::GetRelativePath($projectRoot, $_.FullName) })
$compileClasspath = [System.Collections.Generic.List[string]]::new()
$compileClasspath.Add([System.IO.Path]::GetRelativePath($projectRoot, $apiJarPath))
Get-ChildItem -LiteralPath (Join-Path $projectRoot 'libraries') -Recurse -Filter '*.jar' -File |
    ForEach-Object { $compileClasspath.Add([System.IO.Path]::GetRelativePath($projectRoot, $_.FullName)) }

Push-Location $projectRoot
try {
    $javacArguments = @(
        '-encoding'
        'UTF-8'
        '-source'
        '25'
        '-target'
        '25'
        '-classpath'
        ($compileClasspath -join [System.IO.Path]::PathSeparator)
        '-d'
        $classesRoot
    ) + $javaSources
    & javac @javacArguments
} finally {
    Pop-Location
}
if ($LASTEXITCODE -ne 0) {
    throw 'Failed to compile the TNT probe.'
}

Copy-Item -LiteralPath (Join-Path $resourceRoot 'plugin.yml') -Destination $classesRoot
Copy-Item -LiteralPath $generatedFixture -Destination $classesRoot
if (Test-Path -LiteralPath $OutputJar) {
    Remove-Item -LiteralPath $OutputJar -Force
}
& jar '--create' '--file' $OutputJar '-C' $classesRoot '.'
if ($LASTEXITCODE -ne 0) {
    throw 'Failed to package the TNT probe.'
}

Write-Output "Built $OutputJar"
