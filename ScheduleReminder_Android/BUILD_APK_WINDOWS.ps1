# Schedule Reminder one-click Windows builder.
# Prerequisites: Android Studio (SDK 35 + NDK 27.2.12479018), Rust via rustup, Internet.
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { "$env:LOCALAPPDATA\Android\Sdk" }
$ndk = Join-Path $sdk 'ndk\27.2.12479018'
if (-not (Test-Path $ndk)) {
    throw "Android NDK not found: $ndk . In Android Studio -> SDK Manager -> SDK Tools, install NDK 27.2.12479018."
}
$env:ANDROID_HOME = $sdk
$env:ANDROID_NDK_HOME = $ndk
$env:Path += ";$env:USERPROFILE\.cargo\bin"
if (-not (Get-Command cargo -ErrorAction SilentlyContinue)) {
    throw 'Rust is missing. Install from https://rustup.rs/ and reopen PowerShell.'
}
if (-not (Get-Command 'cargo-ndk' -ErrorAction SilentlyContinue)) {
    & cargo install cargo-ndk --locked
    if ($LASTEXITCODE -ne 0) { throw 'Installing cargo-ndk failed' }
}
& rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
if ($LASTEXITCODE -ne 0) { throw 'Installing Android Rust targets failed' }
$rust = Join-Path $root 'rust'
$android = Join-Path $root 'android'
$bindings = Join-Path $android 'app\src\generated\kotlin'
$libs = Join-Path $android 'app\src\main\jniLibs'
New-Item -ItemType Directory -Force -Path $bindings, $libs | Out-Null
Push-Location $rust
try {
    Write-Host '[1/3] Compiling Rust + Kotlin bindings...'
    & cargo build --release --lib
    if ($LASTEXITCODE -ne 0) { throw 'Rust host build failed' }
    & cargo run --release --features bindgen --bin uniffi-bindgen-android -- generate 'target\release\schedule_core.dll' --language kotlin --out-dir $bindings
    if ($LASTEXITCODE -ne 0) { throw 'UniFFI binding generation failed' }
    Write-Host '[2/3] Compiling native Android Rust libraries...'
    & cargo ndk --platform 26 -t arm64-v8a -t armeabi-v7a -t x86_64 -o $libs build --release --lib
    if ($LASTEXITCODE -ne 0) { throw 'Android Rust build failed' }
} finally { Pop-Location }

# Use an official Gradle distribution directly; Gradle Wrapper jar is not required.
$gradleHome = Join-Path $env:LOCALAPPDATA 'ScheduleReminder\gradle-8.13'
$gradleExe = Join-Path $gradleHome 'gradle-8.13\bin\gradle.bat'
if (-not (Test-Path $gradleExe)) {
    New-Item -ItemType Directory -Force -Path $gradleHome | Out-Null
    $gradleZip = Join-Path $gradleHome 'gradle-8.13-bin.zip'
    Write-Host 'Downloading Gradle 8.13 from services.gradle.org...'
    Invoke-WebRequest -Uri 'https://services.gradle.org/distributions/gradle-8.13-bin.zip' -OutFile $gradleZip
    Expand-Archive -Path $gradleZip -DestinationPath $gradleHome -Force
    Remove-Item $gradleZip
}
Write-Host '[3/3] Packaging APK...'
& $gradleExe -p $android wrapper --gradle-version 8.13
if ($LASTEXITCODE -ne 0) { throw 'Generating Gradle wrapper failed' }
& $gradleExe -p $android :app:assembleDebug
if ($LASTEXITCODE -ne 0) { throw 'Android/Gradle APK build failed' }
$apk = Join-Path $android 'app\build\outputs\apk\debug\app-debug.apk'
$out = Join-Path $root 'ScheduleReminder-Android.apk'
Copy-Item $apk $out -Force
Write-Host "Success! APK: $out" -ForegroundColor Green
