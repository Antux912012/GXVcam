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

## Status

| Area | State |
| :--- | :--- |
| Architecture & docs | ✅ Reviewed & updated for Android 16 (SDK 36) |
| Device probe tool (`tools/mtk_camera_probe.py`) | ✅ **Verified on physical Motorola Edge 50 Neo** (ZY22LWCQSB) |
| RAW unpack + black level (CPU reference) | ✅ Implemented + host unit test |
| RAW10/16 unpack (Vulkan shader) | ✅ Written + 16KB page-size alignment configured |
| Android/Gradle project | ⏳ Phase 0 |
| Capture, fusion, look, UI | ⏳ |

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
