#!/usr/bin/env bash
set -euo pipefail

scratch="${1:?Pass an existing scratch directory}"
repo="$(cd "$(dirname "$0")/.." && pwd)"
runtime="$repo/work/android/app/src/main/jniLibs/arm64-v8a"
mkdir -p "$runtime" "$repo/work/android/ncnn"

fetch() {
    local url="$1" path="$2" checksum="$3"
    if [[ ! -f "$path" ]]; then curl -fL --retry 3 "$url" -o "$path"; fi
    echo "$checksum  $path" | sha256sum --check
}

fetch "https://github.com/AbrahamPaulJ/facefusion-mobile/releases/download/v0.9.41/facefusion-mobile-0.9.41-release.apk" \
    "$scratch/upstream.apk" 86f7bac62e7ab095658d4f6289b400d7de195f48390c8d9bbebfb2f66f1cc0ae
unzip -jo "$scratch/upstream.apk" 'lib/arm64-v8a/libQnn*.so' -d "$runtime"

fetch "https://github.com/Tencent/ncnn/releases/download/20250503/ncnn-20250503-android-vulkan.zip" \
    "$scratch/ncnn.zip" 4b54cd11094dae1371c59fb202dfd92d22962ffaef04317bdf99b12c09f44ed3
unzip -oq "$scratch/ncnn.zip" '*/arm64-v8a/*' -d "$scratch"
cp -R "$scratch/ncnn-20250503-android-vulkan/arm64-v8a/include" \
      "$scratch/ncnn-20250503-android-vulkan/arm64-v8a/lib" "$repo/work/android/ncnn/"
