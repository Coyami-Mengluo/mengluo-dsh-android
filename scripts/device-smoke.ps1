param([string]$Serial, [string]$Adb, [ValidateSet('x86_64','arm64-v8a')][string]$Abi, [string]$Listener)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
$configPath = Join-Path $projectRoot '.tools/test-device.json'
$target = if (Test-Path -LiteralPath $configPath) { Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json } else { $null }
if (!$Serial -and $target) { $Serial = $target.serial }
if (!$Adb -and $target) { $Adb = $target.adb }
if (!$Abi -and $target) { $Abi = $target.abi }
if (!$PSBoundParameters.ContainsKey('Listener') -and $target) { $Listener = $target.instrumentationListener }
if (!$Adb) { $Adb = 'adb' }
if (!$Serial -or !$Abi) { throw 'Set serial and abi in .tools/test-device.json or pass -Serial and -Abi explicitly.' }
$buildDirectory = if ($Abi -eq 'arm64-v8a') { 'app/build-arm64' } else { 'app/build' }
Push-Location $projectRoot
try {
    $state = & $Adb -s $Serial get-state
    if ($LASTEXITCODE -ne 0 -or ($state -join '').Trim() -ne 'device') { throw "Selected device $Serial is not connected and authorized" }
    $deviceAbis = & $Adb -s $Serial shell getprop ro.product.cpu.abilist
    if ($LASTEXITCODE -ne 0 -or $Abi -notin (($deviceAbis -join '').Trim() -split ',')) { throw "Selected device does not support $Abi" }
    Write-Host "Installing application on $Serial; check the phone for an installation confirmation."
    & $Adb -s $Serial install -r "$buildDirectory/outputs/apk/debug/app-debug.apk"
    if ($LASTEXITCODE -ne 0) { throw 'Application installation failed' }
    Write-Host "Installing test APK on $Serial; check the phone for an installation confirmation."
    & $Adb -s $Serial install -r "$buildDirectory/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
    if ($LASTEXITCODE -ne 0) { throw 'Test installation failed' }
    $listenerArgs = if ($Listener) { @('-e', 'listener', $Listener) } else { @() }
    & $Adb -s $Serial shell am instrument -w -r @listenerArgs -e class 'ai.mengluo.dsh.android.DeviceSmokeTest,ai.mengluo.dsh.android.UiInsetsTest,ai.mengluo.dsh.android.ProjectBrowserTest,ai.mengluo.dsh.android.PermissionHelpTest' ai.mengluo.dsh.android.test/androidx.test.runner.AndroidJUnitRunner | Tee-Object -FilePath '.tools/device-smoke.txt'
    $output = Get-Content -LiteralPath '.tools/device-smoke.txt' -Raw
    if ($LASTEXITCODE -ne 0 -or $output -notmatch 'OK \(16 tests\)' -or $output -match 'FAILURES|INSTRUMENTATION_FAILED|Process crashed') { throw 'Device integration test failed' }
} finally { Pop-Location }
