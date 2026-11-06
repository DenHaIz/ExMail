# Окружение сборки ExMail. Действует только в текущей сессии PowerShell:
#   . .\env.ps1
#   .\gradlew assembleDebug
$root = $PSScriptRoot
$env:JAVA_HOME = Join-Path $root '.toolchain\jdk-17'
$env:ANDROID_HOME = Join-Path $root '.toolchain\android-sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:ANDROID_USER_HOME = Join-Path $root '.toolchain\android-user-home'
$env:GRADLE_USER_HOME = Join-Path $root '.toolchain\gradle-home'
$env:PATH = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:PATH"
Write-Host "ExMail toolchain: JAVA_HOME=$env:JAVA_HOME"
