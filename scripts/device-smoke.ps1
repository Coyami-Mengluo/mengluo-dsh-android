param([string]$Serial='127.0.0.1:16384', [string]$Adb='C:\Program Files\Netease\MuMu Player 12\nx_main\adb.exe')
$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
    & $Adb -s $Serial install -r 'app/build/outputs/apk/debug/app-debug.apk'
    if ($LASTEXITCODE -ne 0) { throw 'Application installation failed' }
    & $Adb -s $Serial install -r 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
    if ($LASTEXITCODE -ne 0) { throw 'Test installation failed' }
    $output = & $Adb -s $Serial shell am instrument -w -r -e class 'ai.mengluo.dsh.android.DeviceSmokeTest,ai.mengluo.dsh.android.UiInsetsTest,ai.mengluo.dsh.android.ProjectBrowserTest,ai.mengluo.dsh.android.PermissionHelpTest' ai.mengluo.dsh.android.test/androidx.test.runner.AndroidJUnitRunner
    $output | Tee-Object -FilePath '.tools/device-smoke.txt'
    if (($output -join "`n") -notmatch 'OK \(16 tests\)' -or ($output -join "`n") -match 'FAILURES|INSTRUMENTATION_FAILED|Process crashed') { throw 'Device integration test failed' }
} finally { Pop-Location }
