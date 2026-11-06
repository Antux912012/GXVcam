<div align="center">

<img src="docs/assets/icon.png" alt="GXVcam icon" width="120" />

# GXVcam

**A computational-photography camera app for the Motorola Edge 50 Neo and Edge 60 Neo.**

GCam-style interface · burst RAW fusion · vivo-like colour rendering · MediaTek-aware capture

[![Release](https://img.shields.io/badge/release-v0.3.2-blue)](https://github.com/Antux912012/GXVcam/releases/tag/v0.3.2)
[![Android](https://img.shields.io/badge/Android-14%2B%20%2F%2016-3DDC84)](#requirements)
[![License](https://img.shields.io/badge/license-MIT%20%2B%20GPLv3-lightgrey)](#license)

[**Download APK**](https://github.com/Antux912012/GXVcam/releases/download/v0.3.2/GXVcam-v0.3.2.apk) ·
[Features](#features) ·
[Build & test](#build--test) ·
[Legal](#legal-notes)

</div>

---

## Overview

GXVcam is an alternative camera app for MediaTek-based Motorola phones. It combines three ideas:

- **GCam-style UX.** The interface is our own implementation in Jetpack Compose, following familiar interaction patterns. It uses no Google code or assets.
- **Burst RAW fusion.** Multi-frame merging is based on published research (HDR+ and handheld multi-frame super-resolution).
- **vivo-like rendering.** Natural and Vivid looks with restrained highlights and natural skin tones, achieved by calibrating our own tone curves and LUTs against reference photos. It uses no vivo code or data.

Capture is MediaTek-aware: correct RAW10/RAW16 handling, per-frame dynamic black level, Camera2 Extensions, and any vendor tags the camera HAL actually exposes.

## Supported devices

| Device | SoC | Android | Status |
|:--|:--|:--|:--|
| Motorola Edge 50 Neo | MediaTek Dimensity 7300 | 16 | Tested |
| Motorola Edge 60 Neo | MediaTek Dimensity 7400 | 15 / 16 | Targeted |

### Requirements

- Android 14 or newer (`minSdk 34`), built against Android 16 (SDK 36).
- A device whose camera HAL exposes the sensors listed below. Per-device profiles live in [`configs/devices/`](configs/devices); they describe expectations, while runtime values always come from the HAL.

## Download

Pre-built APKs are published under [Releases](https://github.com/Antux912012/GXVcam/releases):

**[GXVcam v0.3.2](https://github.com/Antux912012/GXVcam/releases/download/v0.3.2/GXVcam-v0.3.2.apk)**

## Features

### Capture

| Area | Details |
|:--|:--|
| **Capture formats** | JPEG only, 16-bit uncompressed RAW (`.dng`) only, or RAW + JPEG simultaneously. |
| **RAW pipeline** | 16-bit `RAW_SENSOR` unpacker with a Vulkan compute shader and a CPU reference implementation. |
| **Imaging options** | 50MP Quad-Bayer remosaic, ADRC dynamic range, tintless lens shading, ZSL, Super Night, anti-banding, configurable burst HDR+ frame count. |
| **Colour spaces** | sRGB, DCI-P3 and Adobe RGB with embedded colour-space metadata. JPEG quality slider 50–100 % (default 85 %). |
| **Aspect ratios** | 4:3, 16:9, 1:1, 3:2 and 21:9. |
| **Orientation** | Gravity-sensor listener maps device tilt to the target rotation and writes normalised EXIF orientation, so photos appear upright in Google Photos and other galleries. |

### Lenses and zoom

| Area | Details |
|:--|:--|
| **Multi-camera access** | Bypasses `vendor.camera.aux.packagelist` to expose and bind: Camera 0 (50MP Sony IMX896 main), Camera 2 (13MP GalaxyCore GC13A2 ultra-wide, 0.6x), Camera 3 (10MP Samsung S5K3K1 telephoto, 3x), Camera 5 (logical multi-camera). Verified on the Edge 50 Neo. |
| **Continuous zoom, 0.6x–20x** | Seamless transitions across ultra-wide (16mm), main (24mm), in-sensor crop (48mm) and 3x telephoto (73mm) through MediaTek logical camera ID 5, without preview freeze or flicker. |
| **Precision zoom ruler** | Canvas ruler with `−` / `+` micro-steppers, quick-jump chips (`.6`, `1.0`, `1.2`, `1.5`, `2.0`, `3.0`, `5.0`, `10x`, `20x`) and the active focal length in mm. |
| **Portrait lens deck** | 24mm, 35mm, 50mm and 85mm looks with a bokeh aperture slider from ƒ/1.2 to ƒ/16. |
| **Auto Macro / Auto NV** | Contextual viewfinder badges. Auto Macro can be locked with one tap and uses ultra-wide close-up focus. |

### Dual capture (Picture-in-Picture)

- Simultaneous front (32MP Samsung S5KJD1) and rear (50MP Sony IMX896) capture in both **Photo** and **Video** modes.
- Draggable PIP window with rounded corners and shadow, or a **50:50 split-screen** layout.
- Instant swap (`⇄`) of the primary and secondary feeds.
- The secondary feed is composited into captured photos; video records at 1080p.
- Dispatches the Motorola OEM HAL vendor tags `is_dual_capture`, `pipzoomratio` and `com.mediatek.streamingfeature.pipDevices`.

### Video

- **Resolutions:** 4K UHD (3840×2160), 1080p, 720p.
- **Frame rates:** 30, 60 and 120 fps slow-mo.
- **Stabilisation:** Adaptive OIS+EIS (`vstab_smarteis_enable`), 360° horizon lock (`vstab_ihc_enable`), or off.
- **Video Night Vision** (`VideoNVwithoutVstab`).
- **HDR10+ 10-bit HLG** (`hdr10_mode_tuning`, `3d_hdr_mode`).
- **Directional audio zoom** that follows the lens zoom ratio.

### Controls and interface

| Area | Details |
|:--|:--|
| **Pro mode** | Live parameter read-out, per-parameter AUTO and global RESET ALL, quick-stop chips for ISO 100–6400, shutter 1/4000 s–32 s, white-balance modes with a Kelvin slider, and AF-C / Macro / Infinity. Sidebar (left/right) or strip (top/bottom) layout. |
| **AE / AF split lock** | Separate top-bar buttons and viewfinder pills for exposure and focus. AF modes: `AF-C`, `AF-S`, `AF-Auto`, `AF-L`, `AF-M`. AE modes: `AE-Multi`, `AE-Center`, `AE-Spot`, `AE-L`, `AE-M`. `[AE-L]` and `[AF-L]` lock independently. |
| **Colour profiles (LUT engine)** | Top-bar dropdown with Natural, Authentic/Vibrant, Vivid, HDR+ and Pantone-style profiles, plus a LibPatcher tuning profile. |
| **Composition grids** | Rule of thirds (3×3), square (4×4), diagonal + square, golden ratio, centre crosshair, or off. |
| **Gyro horizon level** | Planar desk detection and a 10-sample rolling average. |
| **Haptics** | Hardware LRA ticks (`EFFECT_TICK`) on every slider, including EV, Pro sliders, JPEG quality, LibPatcher tuning and the zoom ruler. Optical zoom stops use `EFFECT_CLICK`. |
| **Watermarks** | Device stamp, dark film-frame banner with a Pantone Poinciana (`#E24938`) swatch, EXIF shooting-data stamp, and a custom author signature. |
| **Theming** | Pantone-inspired accents (Poinciana, Nautical Blue, Lattè, Grisaille), Leica Amber, Expressive Mint, and Material You dynamic colour. |

## Project layout

```
GXVcam/
├── app/                       Android app (Kotlin / Jetpack Compose UI)
│   └── src/main/cpp/          native engine (C++20 + Vulkan)
│       ├── include/           raw_pipeline.hpp, vivo_engine.hpp (stage interfaces)
│       ├── src/               raw_unpacker.cpp (CPU reference unpack / normalise)
│       ├── shaders/           unpack_raw10.comp
│       └── tests/             host unit tests
├── configs/devices/           per-device profiles (expectations; runtime values come from the HAL)
├── docs/                      ARCHITECTURE, MEDIATEK_OPTIMIZATION, IMAGING_PIPELINE, LEGAL_AND_SOURCES
├── probe_out/edge50neo/       captured HAL probe output for the Edge 50 Neo
└── tools/                     mtk_camera_probe.py, testdata/
```

## Build & test

### Toolchain

JDK 17, Android SDK (platform 35+ and build-tools; target SDK 36), NDK r27+, CMake 3.22+, Android Studio (or command-line tools with the Gradle wrapper), and `glslc` (ships with the NDK) for the shaders.

### Native unit test (host, no phone needed)

```bash
cd app/src/main/cpp/tests
g++ -std=c++20 -Wall -Wextra -I../include ../src/raw_unpacker.cpp test_raw_unpacker.cpp -o /tmp/gxv_test && /tmp/gxv_test
```

### Camera probe

Probe a connected phone (USB debugging on, RSA prompt accepted):

```bash
python3 tools/mtk_camera_probe.py -o probe_out/edge50neo
```

Or parse a saved `dumpsys` dump instead:

```bash
python3 tools/mtk_camera_probe.py --from-file tools/testdata/dumpsys_sample.txt -o /tmp/probe
```

### Build the app

```bash
./gradlew assembleDebug
```

## Documentation

| Document | Topic |
|:--|:--|
| `docs/ARCHITECTURE` | Overall app and native-engine design |
| `docs/MEDIATEK_OPTIMIZATION` | MediaTek ISP / HAL specifics |
| `docs/IMAGING_PIPELINE` | RAW fusion and rendering pipeline |
| [`docs/LEGAL_AND_SOURCES.md`](docs/LEGAL_AND_SOURCES.md) | What may and may not be reused from GCam, vivo and open-source projects |

## Legal notes

GCam and vivo Camera are proprietary, so GXVcam **re-implements** their ideas rather than reusing them.

- No GCam APKs or `.so` files, no vivo binaries, models, LUTs or tuning files.
- No Google or vivo icons, sounds or animations. Material Symbols and Material 3 components are used instead.
- Third-party names and logos (for example vivo, ZEISS, Pixel, Google Camera) must not appear in the app or in a store listing. "vivo-like" is used here only to describe the visual target.
- Colour looks are calibrated from our own side-by-side reference photos (same scene, ColorChecker in frame).

See [`docs/LEGAL_AND_SOURCES.md`](docs/LEGAL_AND_SOURCES.md) for the full list, including the open-source references the project draws on: the HDR+ paper, Handheld Multi-Frame Super-Resolution, `timothybrooks/hdr-plus`, PhotonCamera, Open Camera, MotionCam, `libultrahdr` and the Android camera samples.

## License

GXVcam is dual-licensed:

| Part | Paths | License |
|:--|:--|:--|
| Core codebase and hardware engine | `cpp/`, `camera/`, `sensors/`, `configs/`, `tools/` | [MIT](LICENSE) |
| User interface | `ui/` | [GPL-3.0](LICENSE) |

Note that reusing code from GPL-licensed projects such as PhotonCamera or Open Camera would make the affected parts GPL-3.0.
