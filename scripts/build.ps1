param([switch]$DeviceTests)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectRoot

if (-not (Test-Path -LiteralPath 'local.properties')) {
    $sdkPath = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
    if (-not (Test-Path -LiteralPath $sdkPath)) { throw 'Android SDK not found. Set ANDROID_HOME.' }
    'sdk.dir=' + $sdkPath.Replace('\', '/').Replace(':', '\:') | Set-Content -LiteralPath 'local.properties' -Encoding ascii
}

$signingDirectory = Join-Path $env:USERPROFILE '.android\traffic-board-signing'
$signingProperties = Join-Path $signingDirectory 'signing.properties'
$keyFile = Join-Path $signingDirectory 'release.jks'
if (-not (Test-Path -LiteralPath $signingProperties)) {
    if (Test-Path -LiteralPath $keyFile) { throw 'Signing key already exists without properties. Restore its credentials instead of replacing the key.' }
    New-Item -ItemType Directory -Path $signingDirectory -Force | Out-Null
    $sid = [System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    & icacls.exe $signingDirectory '/inheritance:r' '/grant:r' "*${sid}:(OI)(CI)F" '*S-1-5-18:(OI)(CI)F' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not restrict signing directory permissions.' }
    $random = New-Object byte[] 32
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($random)
    $password = [Convert]::ToHexString($random)
    $env:TRAFFIC_BOARD_KEY_PASSWORD = $password
    try {
        & keytool.exe '-genkeypair' '-keystore' $keyFile '-storetype' 'JKS' '-alias' 'traffic-board' '-keyalg' 'RSA' '-keysize' '3072' '-validity' '10000' '-dname' 'CN=Traffic Board, O=Local Android Apps, C=CN' '-storepass:env' 'TRAFFIC_BOARD_KEY_PASSWORD' '-keypass:env' 'TRAFFIC_BOARD_KEY_PASSWORD' '-noprompt'
        if ($LASTEXITCODE -ne 0) { throw 'Could not create the signing key.' }
        @("storeFile=$($keyFile.Replace('\', '/'))", "storePassword=$password", 'keyAlias=traffic-board', "keyPassword=$password") | Set-Content -LiteralPath $signingProperties -Encoding ascii
    } finally { Remove-Item Env:TRAFFIC_BOARD_KEY_PASSWORD -ErrorAction SilentlyContinue; $password = $null }
}

$tasks = @(':app:testDebugUnitTest', ':app:lintDebug', ':app:lintRelease', ':app:assembleRelease')
if ($DeviceTests) { $tasks += ':app:connectedDebugAndroidTest' }
& .\gradlew.bat @tasks '--console=plain'
if ($LASTEXITCODE -ne 0) { throw 'Gradle validation/build failed.' }
$releaseApk = Join-Path $projectRoot 'app\build\outputs\apk\release\app-release.apk'
if (-not (Test-Path -LiteralPath $releaseApk)) { throw 'Signed release APK was not produced.' }
$outputDirectory = Join-Path $projectRoot 'dist'
New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
$versionName = [regex]::Match((Get-Content -LiteralPath 'app/build.gradle.kts' -Raw), 'versionName = "([^"]+)"').Groups[1].Value
if (-not $versionName) { throw 'Could not read the app version.' }
$outputFile = Join-Path $outputDirectory "TrafficBoard-$versionName.apk"
Copy-Item -LiteralPath $releaseApk -Destination $outputFile -Force
$checksum = (Get-FileHash -LiteralPath $outputFile -Algorithm SHA256).Hash
"$checksum  TrafficBoard-$versionName.apk" | Set-Content -LiteralPath (Join-Path $outputDirectory 'SHA256SUMS.txt') -Encoding ascii
Write-Output "APK: $outputFile"
Write-Output "SHA256: $checksum"
Write-Output "Signing key (keep private, retain for app updates): $signingDirectory"
