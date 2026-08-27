[CmdletBinding()]
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $GradleArguments
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$gradleWrapper = Join-Path $repositoryRoot "gradlew.bat"
$hasOriginalGradleOptions = Test-Path Env:GRADLE_OPTS
$originalGradleOptions = if ($hasOriginalGradleOptions) { $env:GRADLE_OPTS } else { $null }

function Get-JavaNativeEncoding {
    $javaExecutable = if ($env:JAVA_HOME) {
        Join-Path $env:JAVA_HOME "bin\java.exe"
    } else {
        (Get-Command java -ErrorAction Stop).Source
    }

    $nativeEncodingLine = & $javaExecutable "-XshowSettings:properties" "-version" 2>&1 |
        Select-String -Pattern '^\s*native\.encoding\s*=\s*(\S+)\s*$' |
        Select-Object -First 1

    if ($null -eq $nativeEncodingLine) {
        throw "Unable to determine the Java native encoding."
    }

    return $nativeEncodingLine.Matches[0].Groups[1].Value
}

try {
    # Java decodes launcher argument files with native.encoding. Gradle 9 writes
    # them with file.encoding, so both must match when the project path is non-ASCII.
    $runningOnWindows = [System.Environment]::OSVersion.Platform -eq [System.PlatformID]::Win32NT
    # Gradle canonicalizes directory junctions before constructing worker classpaths. Inspect the canonical target as
    # well as the visible workspace path so Java decodes the generated argument file with the matching encoding.
    $resolvedRoot = [System.IO.Directory]::ResolveLinkTarget($repositoryRoot, $true)
    $physicalRepositoryRoot = if ($null -eq $resolvedRoot) { $repositoryRoot } else { $resolvedRoot.FullName }
    $hasNonAsciiPath = $repositoryRoot -match '[^\x00-\x7F]' -or $physicalRepositoryRoot -match '[^\x00-\x7F]'
    if ($runningOnWindows -and $hasNonAsciiPath) {
        $nativeEncoding = Get-JavaNativeEncoding
        $encodingOption = "-Dfile.encoding=$nativeEncoding"
        $currentOptions = if ($null -eq $env:GRADLE_OPTS) { "" } else { $env:GRADLE_OPTS }

        if ($currentOptions -match '(?<!\S)-Dfile\.encoding=\S+') {
            $env:GRADLE_OPTS = $currentOptions -replace '(?<!\S)-Dfile\.encoding=\S+', $encodingOption
        } else {
            $env:GRADLE_OPTS = ($currentOptions, $encodingOption -join ' ').Trim()
        }
    }

    $arguments = @(':leaves-server:test', '--no-daemon') + $GradleArguments
    & $gradleWrapper @arguments
    exit $LASTEXITCODE
} finally {
    if ($hasOriginalGradleOptions) {
        $env:GRADLE_OPTS = $originalGradleOptions
    } else {
        Remove-Item Env:GRADLE_OPTS -ErrorAction SilentlyContinue
    }
}
