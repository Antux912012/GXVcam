# GXVcam

Camera app for **Android 14+ / Android 16 (SDK 36)** targeting the **Motorola Edge 50 Neo** (Dimensity 7300, tested on Android 16)
and **Edge 60 Neo** (Dimensity 7400, Android 15/16):

* **GCam-style interface**: own implementation in Jetpack Compose (no Google code/assets).
* **Burst RAW fusion** based on published HDR+ / multi-frame super-resolution research.
* **vivo-like rendering** (Natural / Vivid looks, restrained highlights, natural skin),
  achieved by our own calibration against reference photos (no vivo code/data).
* **MediaTek-aware capture**: correct RAW10/RAW16 handling, per-frame dynamic black level,
  Camera2 Extensions and any vendor tags the HAL actually exposes.

See [`docs/LEGAL_AND_SOURCES.md`](docs/LEGAL_AND_SOURCES.md) for the limits on reusing GCam/vivo.

## Download & Releases

Pre-built APKs are available under GitHub Releases:
* [**Download latest GXVcam APK (v0.2.0)**](https://github.com/Antux912012/GXVcam/releases/download/v0.2.0/GXVcam-v0.2.0.apk)

## Features & Status

| Area | State | Highlights |
| :--- | :--- | :--- |
| **Multi-Camera & Aux Sensors** | ✅ Verified on Edge 50 Neo | Bypasses `vendor.camera.aux.packagelist` to expose & bind Camera 0 (Main 50MP Sony IMX896), Camera 2 (13MP GalaxyCore GC13A2 Ultra-Wide 0.6x), Camera 3 (10MP Samsung S5K3K1 Telephoto 3x), and Camera 5 (Logical multi-camera) |
| **M3 Expressive PRO Mode** | ✅ Complete & De-cluttered | Live parameter readout, instant AUTO reset per parameter & global RESET ALL, quick-stop chips (ISO 100–6400, Shutter 1/4000s–32s, White Balance modes + Kelvin slider, AF-C / Macro / Infinity) |
| **MotCamera5 & GCam Settings** | ✅ Implemented | 50MP Quad-Bayer remosaic, ADRC dynamic range, Tintless lens shading, ZSL, Super Night, Anti-banding, RAW+JPEG DNG, Burst HDR+ frame count, Gyro Horizon level, RGB Histogram |
| **BigKaka AGC LibPatcher** | ✅ Implemented | Zeiss Natural, Leica Authentic, Leica Vibrant, Vivo Vivid, and Custom profiles with live viewfinder LUT (RenderEffect) |
| **Vulkan & RAW Pipeline** | ✅ Implemented | 16-bit RAW_SENSOR unpacker with Vulkan compute shader and CPU reference |

## Layout

```
GXVcam/
├── app/src/main/cpp/          native engine (C++20 + Vulkan)
│   ├── include/               raw_pipeline.hpp, vivo_engine.hpp (stage interfaces)
│   ├── src/raw_unpacker.cpp   CPU reference unpack/normalize
│   ├── shaders/               unpack_raw10.comp
│   └── tests/                 host unit tests
├── configs/devices/           per-device profiles (expectations; runtime values come from HAL)
├── docs/                      ARCHITECTURE, MEDIATEK_OPTIMIZATION, IMAGING_PIPELINE, LEGAL_AND_SOURCES
└── tools/                     mtk_camera_probe.py, testdata/
```

## Run what exists today

```bash
# Native unit test (host, no phone needed)
cd app/src/main/cpp/tests
g++ -std=c++20 -Wall -Wextra -I../include ../src/raw_unpacker.cpp test_raw_unpacker.cpp -o /tmp/gxv_test && /tmp/gxv_test

# Probe a connected phone (USB debugging on, RSA prompt accepted)
python3 tools/mtk_camera_probe.py -o probe_out/edge50neo
# ...or parse a saved dump
python3 tools/mtk_camera_probe.py --from-file tools/testdata/dumpsys_sample.txt -o /tmp/probe
```

## Dev environment (to install, see build plan Phase 0)

JDK 17, Android SDK (platform 35, build-tools 35), NDK r27+, CMake 3.22+, Android Studio
(or cmdline-tools + Gradle wrapper), `glslc` (ships with the NDK) for shaders.
