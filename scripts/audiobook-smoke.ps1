param(
    [Parameter(Mandatory = $true)][string]$DeviceId,
    [Parameter(Mandatory = $true)][string]$TestClass
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$appApk = Join-Path $repoRoot 'app/build/outputs/apk/debug/app-debug.apk'
$testApk = Join-Path $repoRoot 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
$testPackage = 'com.phnem.vetro.ab07smoke.test'

Push-Location $repoRoot
try {
    & .\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest -PaudiobookSmokeBuild=true -q --no-daemon --max-workers=2
    if ($LASTEXITCODE -ne 0) { throw 'Audiobook smoke build failed' }

    & adb -s $DeviceId install -r -d $appApk
    if ($LASTEXITCODE -ne 0) { throw 'Test app install failed' }
    & adb -s $DeviceId install -r -d $testApk
    if ($LASTEXITCODE -ne 0) { throw 'Test runner install failed' }

    $output = & adb -s $DeviceId shell am instrument -w -r -e class $TestClass "$testPackage/androidx.test.runner.AndroidJUnitRunner"
    $output | ForEach-Object { Write-Output $_ }
    if (($output -join "`n") -notmatch 'OK \(\d+ tests?\)') { throw 'Audiobook device smoke failed' }
} finally {
    # The side-by-side app is reused by the next ticket. The runner must not remain on the phone.
    & adb -s $DeviceId uninstall $testPackage | Out-Null
    Pop-Location
}
