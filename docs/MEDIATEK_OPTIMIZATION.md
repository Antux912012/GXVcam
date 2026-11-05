# MediaTek (Dimensity 7300 / 7400) integration notes

How GXVcam gets the best data out of the MediaTek Imagiq ISP on the Edge 50 Neo / 60 Neo
**as a regular, non-system app**.

## 1. Reality check: what a third-party app can reach

| Path | Reachable from a Play-installable app? | Notes |
| :--- | :--- | :--- |
| Standard Camera2 API (RAW, manual 3A, ZSL via ring buffer) | **Yes** | Main path. Everything the pipeline needs is standard: `SENSOR_DYNAMIC_BLACK_LEVEL` (API 29), `SENSOR_DYNAMIC_WHITE_LEVEL`, `SENSOR_NOISE_PROFILE`, `SENSOR_NEUTRAL_COLOR_POINT`, `SENSOR_FORWARD_MATRIX1/2`, OIS samples (`STATISTICS_OIS_SAMPLES`, if exposed). |
| Camera2 **Extensions** (`CameraExtensionCharacteristics`: NIGHT, HDR, BOKEH, FACE_RETOUCH) | **Yes, if Motorola ships them** | Lets us call Moto's own ISP-accelerated Night/HDR through a public API. Probe this first; it's the cheapest way to "use the MTK ISP". |
| Vendor tags (`com.mediatek.*`, `com.motorola.*`) | **Only the ones the HAL lists** in `getAvailableCaptureRequestKeys()` / characteristics keys | Many MTK features (MFNR, AIS, 4-cell remosaic) are gated to the system camera (`com.motorola.camera*`) via package allow-lists. Treat them as optional bonuses discovered at runtime; **never hard-code names that weren't seen in the probe**. |
| MTK HAL feature pipelines / `libmtkcam*`, NeuroPilot direct | **No** (system/vendor partition, SELinux) | Would need root/system signing. Out of scope. |

> [!WARNING]
> The previous plan listed tags like `com.mediatek.control.capture.zsl` and
> `com.mediatek.camerahdrservice.v1` as if confirmed. They were **unverified guesses** and have been removed.
> The real list comes from `tools/mtk_camera_probe.py` (`vendor_tags` per camera in `summary.json`).

## 2. RAW formats on Android (corrected)

| `ImageFormat` | Layout | Notes |
| :--- | :--- | :--- |
| `RAW_SENSOR` (0x20) | **16 bits/pixel, unpacked**, little-endian, `rowStride` in bytes | Most common. No bit-unpacking needed. |
| `RAW10` (0x25) | MIPI packed: 4 px in 5 bytes, LSBs in 5th byte (P0 in bits 1:0) | Smaller ring buffer (~1.25 B/px vs 2 B/px). |
| `RAW12` (0x26) | 2 px in 3 bytes | Rare on MTK. |
| `RAW_PRIVATE` | Opaque | Not usable for our own processing. |

Both paths are implemented and unit-tested in [`raw_unpacker.cpp`](../app/src/main/cpp/src/raw_unpacker.cpp);
the GPU version is [`unpack_raw10.comp`](../app/src/main/cpp/shaders/unpack_raw10.comp).

**Rules:**
* Always honour `Image.Plane.getRowStride()`; MTK commonly pads rows.
* Black level: use `CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL` per frame, fall back to
  `SENSOR_BLACK_LEVEL_PATTERN`. The 4 values are in **2x2 readout order**
  (`index = ((y&1)<<1)|(x&1)`), *not* R/Gr/Gb/B. That mislabelling is what causes the
  green/magenta shadow casts seen in many GCam ports.
* White level: `SENSOR_DYNAMIC_WHITE_LEVEL`, fall back to `SENSOR_INFO_WHITE_LEVEL`.

## 3. Quad-Bayer (LYT-700C) handling

* Default RAW is usually **binned 4096x3072** (Bayer after on-sensor 2x2 binning): use it.
* 50 MP full-res RAW is only available if the HAL advertises `ULTRA_HIGH_RESOLUTION_SENSOR`
  and `SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION`. Its CFA is Quad-Bayer, which needs **remosaic**
  (on-sensor/ISP, or our own). Phase 5+ only, and only in bright light.

## 4. Multi-camera access

* Moto typically exposes one **logical** rear camera with physical IDs behind it.
  RAW may be available only on the main sensor, or only through
  `OutputConfiguration.setPhysicalCameraId()`. The probe reports `LOGICAL_MULTI_CAMERA` and
  `android.logicalMultiCamera.physicalIds`.
* Lens-switching UX must degrade gracefully: if tele/UW give no RAW, use YUV +
  `Extensions`/single-frame processing for those lenses.

## 5. Compute on Dimensity

| Unit | API | Use |
| :--- | :--- | :--- |
| Mali-G615 MC2 GPU | **Vulkan 1.1+** compute (target 1.3 features only if probed) | Unpack, align, merge, demosaic, tone, LUT |
| CPU (A78/A55) | C++20 + NEON (`arm_neon.h`), thread pool | Reference paths, metadata, JPEG |
| APU 655 | **LiteRT (TFLite)** with GPU delegate; MediaTek NeuroPilot delegate if its licence allows | Face/skin/sky segmentation |

> [!NOTE]
> NNAPI is **deprecated since Android 15**; don't build on it.

**Zero-copy:** RAW `ImageReader` buffers are *not* guaranteed GPU-sampleable. Plan for one
CPU→GPU copy per frame (`AHardwareBuffer` with `USAGE_CPU_READ_OFTEN` → mapped Vulkan
storage buffer). Try `VK_ANDROID_external_memory_android_hardware_buffer` import as an
optimisation, keep the copy path as fallback.

## 6. Memory budget (binned 12.6 MP)

| Item | Size |
| :--- | :--- |
| 1 frame RAW_SENSOR (16-bit) | ~25 MB |
| 1 frame RAW10 | ~16 MB |
| 1 frame float32 Bayer (working) | ~50 MB |
| 8-frame RAW16 ZSL ring | ~200 MB |

Process frames **streaming into an accumulator** (merge one frame at a time into the
reference) instead of holding N float frames, and keep peak native memory < ~600 MB.
