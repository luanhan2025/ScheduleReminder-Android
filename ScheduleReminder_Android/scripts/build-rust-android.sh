#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT/rust"
: "${ANDROID_NDK_HOME:?Please set ANDROID_NDK_HOME to the installed Android NDK directory}"
command -v cargo >/dev/null || { echo 'Rust/Cargo is missing'; exit 1; }
command -v cargo-ndk >/dev/null || { echo 'Install cargo-ndk: cargo install cargo-ndk --locked'; exit 1; }

# Bindings generated from host shared library; no need to execute Android binaries.
echo '[1/3] Generating UniFFI Kotlin bindings'
cargo build --release --lib
mkdir -p "$ROOT/android/app/src/generated/kotlin"
cargo run --release --features bindgen --bin uniffi-bindgen-android -- \
  generate target/release/libschedule_core.so --language kotlin \
  --out-dir "$ROOT/android/app/src/generated/kotlin"

echo '[2/3] Cross compiling Rust for Android ABIs'
cargo ndk --platform 26 -t arm64-v8a -t armeabi-v7a -t x86_64 \
  -o "$ROOT/android/app/src/main/jniLibs" build --release --lib

echo '[3/3] Checking output'
for abi in arm64-v8a armeabi-v7a x86_64; do
  test -s "$ROOT/android/app/src/main/jniLibs/$abi/libschedule_core.so"
done
find "$ROOT/android/app/src/generated/kotlin" -name '*.kt' | grep -q .
echo 'Rust and Kotlin bindings generated successfully.'
