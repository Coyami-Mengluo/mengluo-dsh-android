param([string[]]$Tasks = @('assembleDebug','testDebugUnitTest','lintDebug'), [ValidateSet('x86_64','arm64-v8a')][string]$Abi)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if (!$Abi) {
    $deviceConfig = Join-Path $projectRoot '.tools/test-device.json'
    $Abi = if (Test-Path -LiteralPath $deviceConfig) { (Get-Content -LiteralPath $deviceConfig -Raw | ConvertFrom-Json).abi } else { 'x86_64' }
    if (!$Abi) { throw 'Set abi in .tools/test-device.json or pass -Abi explicitly.' }
}
if (!$env:JAVA_HOME) {
    $localJdk = Get-ChildItem -LiteralPath (Join-Path $projectRoot '.tools/jdk') -Directory -ErrorAction SilentlyContinue |
        Where-Object { (Test-Path -LiteralPath (Join-Path $_.FullName 'bin/javac.exe')) -and
            (Test-Path -LiteralPath (Join-Path $_.FullName 'release')) -and
            (Select-String -LiteralPath (Join-Path $_.FullName 'release') -Pattern '^JAVA_VERSION="21[."]' -Quiet) } |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    $javaExecutable = Get-Command javac.exe -ErrorAction SilentlyContinue
    if ($localJdk) { $env:JAVA_HOME = $localJdk.FullName }
    elseif ($javaExecutable) { $env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent $javaExecutable.Source) }
    else { throw 'Set JAVA_HOME to JDK 21 or unpack it under .tools/jdk before building.' }
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
    # Separate task histories prevent Gradle from deleting the other ABI's outputs.
    & '.\.tools\gradle-8.9\bin\gradle.bat' --no-daemon --console=plain --project-cache-dir ".gradle/$Abi" "-PruntimeAbi=$Abi" @proxyArgs @Tasks
    exit $LASTEXITCODE
} finally { Pop-Location }
