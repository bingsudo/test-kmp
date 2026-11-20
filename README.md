# Kmp Test

Removes the repeated frames from stuttering camera recordings so they play smoothly again. Runs on Windows, macOS, Linux and Android.

> **This is not AI frame interpolation.** It never generates a picture. Every frame of the output is a real frame of the original recording; the tool only removes the repeated ones.

## Why

When a network camera's connection is poor, it delivers fewer real pictures than its frame rate, but the recording or player still runs at that fixed rate and fills each missing frame by showing the previous one again. The result stutters: smooth for a moment, frozen, then a sudden jump to the next picture.

A real case: saving footage from a **Xiaomi camera** at home to your phone with the **Mi Home** app while you are away. When the home upload is slow or the phone's signal is weak, the saved video stutters badly, and a large share of its frames are copies of the frame before. This tool finds those copies and removes them, keeping only frames where the picture actually changed.

## Two modes

| Mode | How | Result |
|---|---|---|
| **Remove duplicates only** (default) | Compares each frame with the last kept one and drops it when they are almost identical | Motion is continuous; frozen stretches are removed, so the video gets shorter |
| **Correct by clock** | Finds the time burned into the picture (such as the date and time in the corner of Xiaomi camera footage) and uses it as the real timeline, spreading each second's real frames evenly across that second | The original length is kept and every picture appears at its real time; a stretch with no new pictures holds the last one |

- The clock is found without OCR or any knowledge of the font: the order of the digits is learned from how the seconds digit changes.
- The rows of the clock are ignored when frames are compared, so a frame whose clock ticked but whose picture did not change still counts as a repeat.
- The output keeps the original resolution and orientation (portrait videos and videos with rotation metadata are not distorted) and is written as H.264 MP4.

## Download

Get the files from the **Releases** page. Names follow `KmpTest-<version>-<os>-<arch>[-portable].<ext>`:

| System | Installer | Portable (no installation) |
|---|---|---|
| Windows | `.msi` | `-portable.exe`: a single file, just run it |
| macOS (Apple silicon) | `.dmg` | `-portable.zip`: unzip and open `KmpTest.app` |
| Linux | `.deb` | `-portable.tar.gz`: unpack and run `bin/KmpTest` |
| Android 10 or later | `-android-arm64.apk` for phones and tablets; `-android-x86_64.apk` for emulators and x86 devices | |

- The desktop versions include their own Java runtime and FFmpeg; nothing else needs to be installed.
- The Windows portable exe unpacks itself into `%LOCALAPPDATA%\KmpTest\app` the first time each version starts (a few seconds) and starts directly after that. Older versions there are removed automatically.
- The macOS version is not notarized by Apple, so macOS blocks it the first time: open System Settings > Privacy & Security and choose "Open Anyway".
- Only an Apple silicon build is published; Intel Macs need a separate build.

## Usage

1. Click "Add videos", or drag videos or whole folders onto the window (every video inside a folder is added). On Android you can also share videos to the app from the gallery or a file manager.
2. Pick a mode and press "Start". The listed videos are processed one after another; you can cancel at any time.
3. Each output is named after its source plus a suffix (`_fixed` by default). Existing files are never overwritten; a number such as ` (2)` is added instead.
   - Desktop: saved next to the source video by default, or in a folder chosen in Settings.
   - Android: saved to `Movies/KmpTest` by default, or in a folder chosen in Settings. Processing continues in the background and a notification tells you when it is done.

Settings:

| Setting | Default | Meaning |
|---|---|---|
| Output FPS | 20 | Frame rate of the output |
| Duplicate threshold | 0.08 | Two frames whose mean brightness difference (0 to 255) is below this count as the same picture. Higher drops more frames, lower keeps more |
| Quality (CRF) | 20 | 0 to 51; lower means better quality and larger files |
| Engine | per platform | See below; engines that do not work on the device are not shown |

There are also the interface language (26 languages), the theme (system, light, dark, black) and the text size.

### Engines

| | Android | Desktop |
|---|---|---|
| MediaCodec (built into Android) | yes | |
| OpenCV | yes | |
| FFmpeg | FFmpegKit (ARM devices only) | JavaCV (bundled FFmpeg) |
| FFmpeg command line | | `ffmpeg` on the PATH, otherwise the bundled one |

### Command line (desktop)

```
KmpTest --cli [--engine=FFMPEG_CLI|FFMPEG] [--mode=plain|clock] [--report=result.txt] videos-or-folders...
```

Each result is saved next to its source as `<name>_<engine>.mp4`. Without `--engine`, every available engine runs once. The Windows exe is a GUI program with no console output, so use `--report` to get the results in a file.

## Development

```
shared/    Kotlin Multiplatform modules shared by Android and desktop
  domain/    algorithms and interfaces: clock detection, duplicate detection, scheduling, the processing flow
  data/      settings, the processing queue, each platform's video engines and output
  ui/        Compose Multiplatform screens, theme, 26 languages
android/   Android app
desktop/   desktop app (Compose Desktop: Windows / macOS / Linux)
assets/    source images of the app icon
scripts/   icon and test-clip generators
```

Requires JDK 17; Android also needs the Android SDK 36.

| Task | Command |
|---|---|
| Run the desktop app | `./gradlew :desktop:run` (videos or folders can be passed as arguments) |
| Desktop portable build | `./gradlew :desktop:createDistributable` -> `desktop/build/compose/binaries/main/app/` |
| Windows single-file portable exe | `./gradlew :desktop:packageSingleExe` -> `desktop/build/compose/binaries/main/single/` |
| Desktop installer | `./gradlew :desktop:packageReleaseDistributionForCurrentOS` (msi / dmg / deb; Windows needs WiX) |
| Android debug build | `./gradlew :android:assembleDebug` -> `android/build/outputs/apk/debug/` (one APK per CPU type) |
| Unit tests | `./gradlew :shared:domain:desktopTest :shared:data:desktopTest :shared:ui:desktopTest :desktop:test` |
| Android instrumented tests | with a device or emulator attached: `./gradlew :android:connectedDebugAndroidTest` |

- A desktop package contains only the native libraries of the OS it is built on, so each platform is built on its own OS (GitHub Actions does this).
- The unit tests include a real encode and decode of a synthetic stuttering clip with every engine available on the machine. To also run real recordings (including rotated ones), set `KMPTEST_SAMPLES` to a folder holding `land.mp4`, `rot90.mp4` and `port.mp4`.
- The instrumented tests create a test clip on the device and check every engine, the default output to `Movies/KmpTest` and sharing a video to the app. They delete their files afterwards.
- Translations: edit `shared/ui/src/commonMain/composeResources/files/locales/*.json`. Missing strings fall back to English, and a unit test checks that every language is complete. Code, comments and docs are English only; CI rejects CJK text outside the translation files.
- Icon: `python scripts/make_icon.py`.

### Releasing

Push a tag of the form `vX.Y.Z`. GitHub Actions then builds and tests on the three desktop platforms and Android and publishes a Release:

```
git tag v1.0.0
git push origin v1.0.0
```

The version comes from the tag. The Android versionCode is major * 10000 + minor * 100 + patch.

### Android signing

Release APKs must be signed with your own key. The key never goes into the repository; it lives in GitHub Secrets.

1. Create the key once:
   ```
   keytool -genkeypair -keystore release.jks -alias key0 -keyalg RSA -keysize 2048 -validity 10000
   ```
   **Back up the key file and its passwords.** Without them, people who installed the app can never receive an update.
2. In the GitHub repository, open Settings > Secrets and variables > Actions and add:
   - `ANDROID_KEYSTORE_BASE64`: the output of `base64 -w0 release.jks`
   - `ANDROID_KEYSTORE_PASSWORD`
   - `ANDROID_KEY_ALIAS`
   - `ANDROID_KEY_PASSWORD`

Without these secrets, a tag build fails instead of publishing an unsigned APK. To build a release locally, set the same environment variables plus `ANDROID_KEYSTORE_FILE` (the key file's path) and run `./gradlew -Papp.version=1.0.0 :android:assembleRelease`.

## License

The desktop versions bundle a GPL build of FFmpeg (for the libx264 encoder), so distributing them must follow the GPL. FFmpegKit and OpenCV in the Android version have their own licenses.
