function Invoke-HiddenBatchCommand {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$BatchFile,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [Parameter(Mandatory = $true)][string]$WorkingDirectory,
        [Parameter(Mandatory = $true)][string]$LogPath
    )

    $batchPath = (Resolve-Path -LiteralPath $BatchFile).Path
    $workingPath = (Resolve-Path -LiteralPath $WorkingDirectory).Path
    $logFullPath = [IO.Path]::GetFullPath($LogPath)
    [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($logFullPath)) | Out-Null

    # 参数由 cmd 转交给 Java；拒绝会打断引号或触发变量展开的内容，避免改变原参数含义。
    foreach ($value in (@($batchPath) + $Arguments)) {
        if ($value -match '["%\r\n\x00]') {
            throw '后台批处理参数不能包含双引号、百分号或换行。'
        }
    }
    $command = '""' + $batchPath + '"'
    foreach ($argument in $Arguments) {
        # Java 的 Windows 参数解析要求末尾反斜杠加倍，防止它转义结束引号。
        $quotedArgument = '"' + ($argument -replace '(\\+)$', '$1$1') + '"'
        $command += ' ' + $quotedArgument
    }
    $command += '"'

    $startInfo = [Diagnostics.ProcessStartInfo]::new($env:ComSpec)
    $startInfo.WorkingDirectory = $workingPath
    $startInfo.Arguments = '/d /v:off /s /c ' + $command
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.WindowStyle = [Diagnostics.ProcessWindowStyle]::Hidden
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true

    $process = [Diagnostics.Process]::Start($startInfo)
    try {
        # 同时读取两条输出流，避免测试错误输出较多时堵住子进程。
        $standardOutputTask = $process.StandardOutput.ReadToEndAsync()
        $standardErrorTask = $process.StandardError.ReadToEndAsync()
        $process.WaitForExit()

        $standardOutput = $standardOutputTask.GetAwaiter().GetResult()
        $standardError = $standardErrorTask.GetAwaiter().GetResult()
        $combinedOutput = $standardOutput + $standardError
        [IO.File]::WriteAllText($logFullPath, $combinedOutput)

        return [pscustomobject]@{
            ExitCode = $process.ExitCode
            Output = $combinedOutput
            LogPath = $logFullPath
        }
    } finally {
        if (-not $process.HasExited) {
            $process.Kill($true)
            $process.WaitForExit()
        }
        $process.Dispose()
    }
}
