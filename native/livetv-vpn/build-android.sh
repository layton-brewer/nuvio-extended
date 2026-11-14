#!/usr/bin/env bash
# Builds the Live TV VPN library for Android into <out>/<abi>/libmeowiovpn.so.
# Usage: build-android.sh <android-ndk-dir> <out-dir>
set -euo pipefail
ndk="$1"
out="$(mkdir -p "$2" && cd "$2" && pwd)"
here="$(cd "$(dirname "$0")" && pwd)"
case "$(uname -s)" in
  Darwin) host="darwin-x86_64" ;;
  *) host="linux-x86_64" ;;
esac
bin="$ndk/toolchains/llvm/prebuilt/$host/bin"
build() {
  local abi="$1" goarch="$2" cc="$3"
  mkdir -p "$out/$abi"
  (cd "$here" && CGO_ENABLED=1 GOOS=android GOARCH="$goarch" GOARM=7 CC="$bin/$cc" \
    CGO_LDFLAGS="-Wl,-z,max-page-size=16384" \
    go build -trimpath -buildmode=c-shared -ldflags "-s -w -buildid=" -o "$out/$abi/libmeowiovpn.so" .)
  rm -f "$out/$abi/libmeowiovpn.h"
}
build arm64-v8a arm64 aarch64-linux-android24-clang
build armeabi-v7a arm armv7a-linux-androideabi24-clang
build x86_64 amd64 x86_64-linux-android24-clang
