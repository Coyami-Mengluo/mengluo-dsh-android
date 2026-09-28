param([string[]]$Tasks = @('assembleDebug','testDebugUnitTest','lintDebug'), [ValidateSet('x86_64','arm64-v8a')][string]$Abi = 'x86_64')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if (!$env:JAVA_HOME) {
    $javaExecutable = Get-Command javac.exe -ErrorAction SilentlyContinue
    if ($javaExecutable) { $env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent $javaExecutable.Source) }
    else { throw 'Set JAVA_HOME to a JDK 21 installation before building.' }
}
$env:ANDROID_HOME = Join-Path $projectRoot '.tools\android-sdk'
$env:ANDROID_USER_HOME = Join-Path $projectRoot '.tools\android-user'
$env:GRADLE_USER_HOME = Join-Path $projectRoot '.tools\gradle-cache'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$proxy = Get-ItemProperty -LiteralPath 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings'
$proxyArgs = @()
if ($proxy.ProxyEnable -eq 1 -and $proxy.ProxyServer -match '^(?:http://)?(127\.0\.0\.1|localhost):(\d+)$') {
    $proxyArgs = @("-Dhttps.proxyHost=$($Matches[1])", "-Dhttps.proxyPort=$($Matches[2])", "-Dhttp.proxyHost=$($Matches[1])", "-Dhttp.proxyPort=$($Matches[2])")
}
Push-Location $projectRoot
try {
    if (!(Test-Path -LiteralPath '.tools\debug.keystore')) {
        & "$env:JAVA_HOME\bin\keytool.exe" -genkeypair -keystore '.tools\debug.keystore' -storepass android -alias androiddebugkey -keypass android -dname 'CN=Android Debug,O=Android,C=US' -keyalg RSA -keysize 2048 -validity 10000
        if ($LASTEXITCODE -ne 0) { throw 'Unable to create isolated debug key' }
    }
    & '.\.tools\gradle-8.9\bin\gradle.bat' --no-daemon --console=plain "-PruntimeAbi=$Abi" @proxyArgs @Tasks
    exit $LASTEXITCODE
} finally { Pop-Location }
