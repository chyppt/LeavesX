[CmdletBinding()]
param([ValidateSet('ai-tool', 'performance')][string]$ProbeName = 'ai-tool')
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$probe = Join-Path $PSScriptRoot ('integration-probes/' + $ProbeName)
$relativeClasses = 'build/' + $ProbeName + '-probe/classes'
$classes = Join-Path $root $relativeClasses
$outputJar = if ($ProbeName -eq 'performance') { 'build/LeavesXPerformanceProbe.jar' } else { 'build/LeavesXAiTradeProbe.jar' }
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$javaBin = Join-Path $env:JAVA_HOME 'bin'
$classpath = @('leaves-api/build/libs/leaves-api-26.1.2-R0.1-SNAPSHOT.jar', 'leaves-server/build/classes/java/main')
$classpath += Get-ChildItem (Join-Path $root 'libraries') -Recurse -Filter '*.jar' -File | ForEach-Object { [IO.Path]::GetRelativePath($root, $_.FullName) }
Push-Location $root
try {
    $sources = Get-ChildItem (Join-Path $probe 'src') -Recurse -Filter '*.java' -File | ForEach-Object { [IO.Path]::GetRelativePath($root, $_.FullName) }
    $compiler = [Diagnostics.ProcessStartInfo]::new((Join-Path $javaBin 'javac.exe'))
    $compiler.WorkingDirectory = $root
    $compiler.UseShellExecute = $false
    $compiler.CreateNoWindow = $true
    foreach ($argument in (@('-encoding','UTF-8','-source','25','-target','25','-classpath',($classpath -join ';'),'-d',$relativeClasses) + @($sources))) {
        $compiler.ArgumentList.Add($argument)
    }
    $process = [Diagnostics.Process]::Start($compiler)
    $process.WaitForExit()
    if ($process.ExitCode -ne 0) { throw 'AI probe compilation failed' }
    $process.Dispose()
    Copy-Item -LiteralPath (Join-Path $probe 'resources/plugin.yml') -Destination $classes
    & (Join-Path $javaBin 'jar.exe') --create --file $outputJar -C $relativeClasses .
    if ($LASTEXITCODE -ne 0) { throw 'AI probe packaging failed' }
} finally { Pop-Location }
