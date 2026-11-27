# MMA Face 3.2

The app requires Android 12 or later on an ARM64 device. Version 3.2 uses package
`com.facefusion.mobile` and version code 253 on the gated branch.

## Install

1. Download the `face-mma-apk` artifact from the build run and extract `face-mma.apk`.
2. Open the APK on the phone and allow installation from the file manager if prompted.
   For a USB install, run `adb install -r face-mma.apk`.
3. On an existing installation, Android requires the signing certificate to match.
   If it reports a conflict, keep the existing installation and use an APK signed with
   its certificate. Uninstalling the old app deletes its settings and downloaded models.
4. In MMA Face, complete the existing model setup for the device. Swapping runs locally
   after the required models are installed.

## Changes

- The Swap screen detects still-image faces and scans video from beginning to end,
  adding people as they appear. Thumbnails stay in discovery order. Tap a person to
  apply the selected source, or use **Keep face** to remove a swap for that person.
  Other faces stay unchanged in assignment mode. Turn the mode off to use the existing
  all-face or largest-face behavior.
- Video export uses the existing ArcFace embeddings plus recent position and appearance
  to keep assignments stable. Faces with uncertain matches keep their original appearance.
- The seek slider controls both preview panes. **Trim export** opens a separate range
  control. A new video exports full length unless the range is changed deliberately.
- Upright photos retain their decoded pixel dimensions and are saved as PNG. The video
  export path retains its original audio copy-through and codec capability checks.
- CI compiles the ARM64 JNI library from source with the existing QNN and ncnn Vulkan
  backends. The staged dependencies contain runtimes and libraries. Model provisioning
  follows the existing app setup and license rules.

## Reproduce the build

Use JDK 17, Android SDK Platform 35, Build Tools 34.0.0, NDK 27.2.12479018 and CMake
3.22.1. Set `ANDROID_HOME` or the ignored `local.properties` SDK path. From the
repository root run the same runtime staging script CI uses:

```sh
bash scripts/stage-android-ci.sh "$(mktemp -d)"
cd work/android
./gradlew :app:testDebugUnitTest :app:assembleRelease
```

The APK is under `work/android/app/build/outputs/apk/release`. Locally, Gradle uses
the debug certificate if there is no ignored `keystore.properties`. Use a signing
configuration you are authorized to use if you need upgrade compatibility. The
existing CI workflow uses its configured certificate. Keep new signing material
out of Git.

The older prebuilt-native option requires a library built against this checkout's JNI
interface. The upstream v0.9.41 `libffnative.so` predates the new media functions and
cannot be used for this upgrade.

## Verification checklist

- [x] Inspect the base branch, media paths and existing build configuration.
- [x] Add still-image discovery and per-person routing.
- [x] Add full-clip discovery and sequential export matching.
- [x] Separate seek and trim state and guard against stale previews.
- [x] Add native and JVM regression tests and a media export verifier.
- [ ] Qualify inference quality, offline playback, sustained throughput and memory on
  a Galaxy S23 Ultra with authorized model files and media.

Run the standalone matcher tests from the repository root:

```sh
c++ -std=c++17 -Wall -Wextra -Werror work/android/tests/media-identity.cpp -o /tmp/media-identity
/tmp/media-identity
```

On an S23 Ultra, pick an authorized still with two subjects and a clip with two
subjects who cross paths, leave, and re-enter. Include another subject who appears
after the start. Assign distinct authorized sources and leave the third person
unassigned. Compare against the original and check unassigned areas, rotation, crop,
skin texture and blend edges. Seek near both ends, then export without using trim.
For a constant-frame-rate AAC MP4 fixture, verify the exported metadata and audio bytes:

```sh
python3 work/android/tests/check-media-export.py input.mp4 exported.mp4
```

For an existing feature check, repeat a swap with assignment mode off and test the
Live camera, optional processors, a refused input, and cancellation. Microphone
acceptance checks remain in [tests/live-microphone.md](tests/live-microphone.md).

## Limits

- Video discovery observes one frame about every 500 ms and stores up to 128 people.
  Very short appearances, tiny faces, occlusion and large pose changes can be missed.
  Visually similar people or extreme changes in appearance can yield an ambiguous match
  or a duplicate thumbnail. The export matcher skips ambiguous faces.
- Per-person assignment applies to one target. Batch jobs use the existing shared-source
  behavior. Apply assignments to individual clips to use per-person export.
- Oversized photos are refused with a device-specific limit, at most 24 megapixels, to
  keep processing at the accepted image's original size. Output is 8-bit PNG. Original
  metadata and HDR color are outside this path.
- Video remains H.264/MP4. The encoder may cap size and rate on unsupported inputs.
  Supported audio is copied through. Formats rejected by MP4 use the existing AAC
  fallback, which can produce video-only output if transcoding fails. Driving audio
  deliberately replaces the original track when Lip Sync uses it.
