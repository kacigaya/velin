#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "$0")/../.." && pwd)
out="$root/core/sdk/build/native"
ref=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["commit"])' "$root/tools/backend/upstream.json")
tag=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["tag"])' "$root/tools/backend/upstream.json")
reference=${TUTA_REFERENCE:?Set TUTA_REFERENCE to a tutanota checkout that has the pinned release tag}
[[ $(git -C "$reference" rev-parse "$tag^{commit}") == "$ref" ]] || { echo 'Release tag does not match pin' >&2; exit 1; }
[[ $(rustc --version) == "rustc 1.97.1 "* ]] || { echo 'Rust 1.97.1 required' >&2; exit 1; }
rm -rf "$out/source" "$out/artifacts"
mkdir -p "$out/source" "$out/artifacts"
# Export tracked source only. Never modify the reference checkout.
git -C "$reference" archive "$ref" | tar -x -C "$out/source"
python3 "$root/tools/backend/check-pin.py" "$out/source"
cat "$root/tools/backend/overlay/mail_read.rs" >> "$out/source/tuta-sdk/rust/sdk/src/mail_facade.rs"
python3 "$root/tools/backend/overlay/guard-crypto.py" "$out/source"
if [[ ! -f "$out/ndk.zip" ]]; then
  curl -fL --retry 3 https://dl.google.com/android/repository/android-ndk-r29-linux.zip -o "$out/ndk.zip"
fi
ndk_sha256=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["ndkSha256"])' "$root/tools/backend/upstream.json")
printf '%s  %s\n' "$ndk_sha256" "$out/ndk.zip" | sha256sum -c -
if [[ ! -d "$out/android-ndk-r29" ]]; then
  unzip -q "$out/ndk.zip" 'android-ndk-r29/toolchains/llvm/prebuilt/linux-x86_64/sysroot/*' -d "$out"
fi
unzip -qo "$out/ndk.zip" 'android-ndk-r29/toolchains/llvm/prebuilt/linux-x86_64/lib/clang/21/lib/linux/*' -d "$out"
sysroot="$out/android-ndk-r29/toolchains/llvm/prebuilt/linux-x86_64/sysroot"
rustup target add aarch64-linux-android x86_64-linux-android
cd "$out/source"
export CARGO_TARGET_DIR="$out/target"
export CARGO_BUILD_JOBS=2
for target in aarch64-linux-android x86_64-linux-android; do
  case "$target" in
    aarch64*) triple=aarch64-linux-android; abi=arm64-v8a; arch=aarch64 ;;
    x86_64*) triple=x86_64-linux-android; abi=x86_64; arch=x86_64 ;;
  esac
  wrapper="$out/clang-$abi"
  printf '#!/bin/sh\nexec /usr/bin/clang --target=%s26 --sysroot="%s" -fuse-ld=lld -L"%s" -Wl,-z,max-page-size=16384 "$@"\n' "$triple" "$sysroot" "$out/android-ndk-r29/toolchains/llvm/prebuilt/linux-x86_64/lib/clang/21/lib/linux/$arch" > "$wrapper"
  chmod +x "$wrapper"
  key=${target//-/_}
  export "CC_$key=$wrapper" "AR_$key=/usr/bin/llvm-ar-18"
  upper=${key^^}
  export "CARGO_TARGET_${upper}_LINKER=$wrapper"
  cargo build --locked -p tuta-sdk --release --target "$target"
  mkdir -p "$out/artifacts/jniLibs/$abi"
  cp "$out/target/$target/release/libtutasdk.so" "$out/artifacts/jniLibs/$abi/"
done
# Host library permits offline execution of the same UniFFI API in JVM tests.
cargo build --locked -p tuta-sdk --release
mkdir -p "$out/artifacts/host"
cp "$out/target/release/libtutasdk.so" "$out/artifacts/host/"
cargo run --locked -p uniffi-bindgen -- generate --library "$out/artifacts/jniLibs/arm64-v8a/libtutasdk.so" --language kotlin --config tuta-sdk/rust/sdk/uniffi.toml --out-dir "$out/artifacts/kotlin"
# Generated bindings are module-internal. No generated types enter Velin contracts.
python3 "$root/tools/backend/internalize-bindings.py" "$out/artifacts/kotlin"
mkdir -p "$out/artifacts/notices"
cp LICENSE.txt "$out/artifacts/notices/TUTA-LICENSE.txt"
printf '%s\n' "$ref" > "$out/artifacts/UPSTREAM_COMMIT"
(cd "$out" && sha256sum ndk.zip source/Cargo.lock source/tuta-sdk/rust/sdk/uniffi.toml) > "$out/artifacts/INPUTS-SHA256"
(cd "$root" && sha256sum tools/backend/overlay/* tools/backend/build-sdk.sh) > "$out/artifacts/OVERLAY-SHA256"
rustc --version > "$out/artifacts/TOOLCHAIN"
clang --version >> "$out/artifacts/TOOLCHAIN"
(cd "$out/artifacts" && find . -type f ! -name SHA256SUMS -print0 | sort -z | xargs -0 sha256sum > SHA256SUMS)
