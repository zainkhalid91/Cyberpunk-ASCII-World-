# Builds, installs and launches NEON//ASCII on a connected phone.
# Pass -Serial <id> to pick a device when more than one is connected (see `adb devices`).
param([string]$Serial)
New-Item -ItemType Directory -Force D:\tmp | Out-Null
$env:TEMP = 'D:\tmp'; $env:TMP = 'D:\tmp'
$env:GRADLE_OPTS = '-Djdk.net.unixdomain.tmpdir=D:\tmp'
& "$PSScriptRoot\gradlew.bat" :androidApp:assembleDebug
if (-not $?) { exit 1 }
$adbArgs = @(); if ($Serial) { $adbArgs = @('-s', $Serial) }
adb @adbArgs install -r "$PSScriptRoot\androidApp\build\outputs\apk\debug\androidApp-debug.apk"
adb @adbArgs shell am start -n com.zain.neonascii/.android.MainActivity
