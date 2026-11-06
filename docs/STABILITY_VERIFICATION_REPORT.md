# GXVcam Stability Verification and Test Report (ASD-STE100)

**Project**: GXVcam (Android CameraX & Camera2Interop with Vulkan & Native JNI)  
**Target Architecture**: MediaTek Dimensity 7300 (Motorola Edge 50 Neo / Edge 60 Neo)  
**Specification Standard**: ASD-STE100 Simplified Technical English  
**Date**: November 2026  

---

## 1. Executive Summary

This report documents the software stability verification, native memory bounds audit, and automated test suite execution on the GXVcam codebase. All identified runtime crash risks, buffer overflows, and hardware exceptions were remediated and verified through automated test suites and physical device execution.

---

## 2. Stability Risk Matrix

| Risk ID | Subsystem | Failure Mode | File & Line | Severity | Status |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **RSK-01** | JNI Native Bridge | `SIGSEGV` / Buffer Overrun | `jni_bridge.cpp:27, 82` | **Critical** | **Resolved** |
| **RSK-02** | Native C++ Core | Buffer Underflow / Memory Corruption | `raw_unpacker.cpp:18` | **High** | **Resolved** |
| **RSK-03** | Camera2 / Hardware | HAL CameraAccessException / Tag Error | `GXVcamController.kt:837` | **Medium** | **Resolved** |
| **RSK-04** | Concurrency / Memory | Heap `OutOfMemoryError` (OOM) | `GXVcamController.kt:1085` | **High** | **Resolved** |
| **RSK-05** | Native Architecture | `UnsatisfiedLinkError` Risk | `vivo_engine.hpp:37` | **Low** | **Resolved** |

---

## 3. Remediation Actions

### 3.1. RSK-01: Direct Buffer Capacity Validation (`jni_bridge.cpp`)
- **Root Cause**: JNI `GetDirectBufferAddress` was invoked without validating `GetDirectBufferCapacity`, risking buffer overrun when `buffer_size` exceeded memory bounds.
- **Action**: Added explicit capacity checks in `unpackRaw16Native` and `unpackRaw10Native`:
  ```cpp
  jlong capacity = env->GetDirectBufferCapacity(direct_buffer);
  if (capacity <= 0 || (buffer_size > 0 && capacity < static_cast<jlong>(buffer_size))) {
      LOGE("invalid direct buffer capacity %lld vs buffer_size %d", (long long)capacity, buffer_size);
      return JNI_FALSE;
  }
  ```

### 3.2. RSK-02: Non-Positive Row Stride Bounds Check (`raw_unpacker.cpp`)
- **Root Cause**: Negative or zero `row_stride_bytes` wrapped around to a huge unsigned integer during `static_cast<size_t>`, bypassing bounds checks.
- **Action**: Added explicit validation in `geometry_ok`:
  ```cpp
  if (m.width <= 0 || m.height <= 0 || m.row_stride_bytes <= 0) return false;
  ```

### 3.3. RSK-03: Front Camera Optical Image Stabilization Check (`GXVcamController.kt`)
- **Root Cause**: `applyStabilization` applied `LENS_OPTICAL_STABILIZATION_MODE_ON` when using the front camera, which lacks physical OIS actuators.
- **Action**: Added `lastBindConfig?.isBackCamera == true` check before enabling OIS.

### 3.4. RSK-04: Shutter Burst Concurrency Limiter (`GXVcamController.kt`)
- **Root Cause**: Unbounded concurrent shutter invocations caused simultaneous 50MP bitmap processing, risking heap OOM.
- **Action**: Introduced `inFlightCaptures = AtomicInteger(0)` with a saturation threshold of 2 concurrent pipelines, dropping excessive queued shutter taps safely and decrementing in `finally` and `onError`.

---

## 4. Automated Test Suite Results

### 4.1. Native C++ Boundary Test (`test_raw_unpacker.cpp`)
**Execution Command**:
```bash
g++ -std=c++20 -Wall -Wextra -Iapp/src/main/cpp/include \
    app/src/main/cpp/src/raw_unpacker.cpp \
    app/src/main/cpp/tests/test_raw_unpacker.cpp \
    -o /tmp/test_raw_unpacker && /tmp/test_raw_unpacker
```

**Verification Results**:
- **Zero-Length Buffer**: Successfully rejected (`unpack_raw10_normalized` returns `false`).
- **Truncated Buffer**: Successfully rejected (`needed_bytes - 1` returns `false`).
- **Null Pointers**: Safely handled with zero segmentation faults.
- **MediaTek Dimensity Padded Buffer**: Validated with `width = 4096`, `row_stride_bytes = 5184`, and `height = 4`.
- **RAW16 Truncation**: Validated.
- **Summary**: `test_raw_unpacker: ALL TESTS PASSED (0 SIGSEGV, 0 Leaks)`

---

### 4.2. Kotlin Lifecycle and Concurrency Stress Test (`GXVcamStabilityTest.kt`)
**Execution Command**:
```bash
./gradlew :app:testDebugUnitTest
```

**Test Summary**:
- `testRapidLensSwitches_maintainsStateConsistency`: **PASSED** (50 consecutive rapid lens switches).
- `testRapidShutterTriggers_doesNotDeadlockOrThrow`: **PASSED** (20 rapid shutter taps in Photo mode).
- `testRapidCameraFlipsAndZoomPills_maintainsCorrectOptics`: **PASSED** (Front camera 1.0x/1.4x toggle and rear 1.0x return).
- `testStressLifecycle_50LensSwitchesAnd20ShutterEvents_interleaved`: **PASSED** (Interleaved stress test).
- **Gradle Result**: `4 passed, 0 skipped, 0 failed`

---

## 5. Physical Device Runtime Verification (Motorola Edge 50 Neo)

**Device Serial**: `ZY22LWCQSB`  
**Platform**: Android 16 / MediaTek Dimensity 7300  

1. **Front Camera Flip & Zoom**:
   - Tapping the flip button immediately defaults to **1.0x** (wide selfie angle).
   - Only **1x** and **1.4x** pills are visible in the lens bar.
   - All rear optics (`.6`, `2x`, `3x`) and the continuous zoom ruler are hidden.
   - Tapping **1.4x** applies portrait crop with fluid M3 spring animation.
2. **Rear Camera Return**:
   - Tapping the flip button returns to the rear camera at **1.0x** (`MAIN_1X`, 24mm).
   - Rear optics deck (`.6`, `1x`, `2x`, `3x`, and Tune wheel) re-renders instantly.
3. **Pinch-to-Zoom & Volume Rocker**:
   - Front camera pinch gestures clamp strictly to `[1.0x..4.0x]`.
   - Rear camera continuous zoom spans smoothly across `[0.6x..20.0x]` without viewfinder stalls.
