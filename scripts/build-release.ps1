param([ValidateSet('x86_64','arm64-v8a')][string]$Abi='arm64-v8a', [string]$SigningConfig, [string]$VersionName='0.0.7', [int]$VersionCode=9)
$ErrorActionPreference='Stop'
if (!$SigningConfig) { throw 'Provide a local, ignored signing JSON (keystore, storePassword, keyAlias, keyPassword). Never commit this file.' }
$private = Get-Content -LiteralPath $SigningConfig -Raw | ConvertFrom-Json
$taskSaved = @{}
foreach ($taskName in @('MENG_LUO_KEYSTORE','MENG_LUO_STORE_PASSWORD','MENG_LUO_KEY_ALIAS','MENG_LUO_KEY_PASSWORD')) {
    $taskSaved[$taskName] = [Environment]::GetEnvironmentVariable($taskName, 'Process')
}
try {
    $env:MENG_LUO_KEYSTORE=$private.keystore
    $env:MENG_LUO_STORE_PASSWORD=$private.storePassword
    $env:MENG_LUO_KEY_ALIAS=$private.keyAlias
    $env:MENG_LUO_KEY_PASSWORD=$private.keyPassword
    & "$PSScriptRoot/build.ps1" -Abi $Abi -Tasks @('assembleRelease', "-PclientVersionName=$VersionName", "-PclientVersionCode=$VersionCode")
    if ($LASTEXITCODE -ne 0) { throw "Release build failed for $Abi" }
    $projectRoot = Split-Path -Parent $PSScriptRoot
    $buildFolder = if ($Abi -eq 'arm64-v8a') { 'build-arm64' } else { 'build' }
    $output = Join-Path $projectRoot "app/$buildFolder/outputs/apk/release"
    $staging = Join-Path $projectRoot "dist/staging/$Abi"
    New-Item -ItemType Directory -Force -Path $staging | Out-Null
    Copy-Item -LiteralPath "$output/app-release.apk" -Destination "$staging/app-release.apk"
    Copy-Item -LiteralPath "$output/output-metadata.json" -Destination "$staging/output-metadata.json"
    Write-Output "Staged $Abi release in $staging"
} finally {
    foreach ($taskName in $taskSaved.Keys) { [Environment]::SetEnvironmentVariable($taskName, $taskSaved[$taskName], 'Process') }
}
