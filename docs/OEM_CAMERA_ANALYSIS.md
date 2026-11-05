# Motorola Edge 50 Neo OEM Camera Architecture & Analysis

This document details the architectural findings, vendor tags, algorithm pipelines, and Camera2 extensions extracted directly from the stock Motorola camera system (`com.motorola.camera5` v5.1.x and `com.motorola.camera.ai.colortone`) on the **Motorola Edge 50 Neo** (`vienna`, Dimensity 7300, Android 16 / Vendor API 34).

---

## 1. Extracted Packages & Artifacts

The following APKs were pulled from `/product` and `/system` to `tools/oem_analysis/` (git-ignored):
* `MotCamera5.apk` (275 MB, `/product/priv-app/MotCamera5/MotCamera5.apk`): Primary camera application.
* `MotColorTone.apk` (90 MB, `/product/priv-app/MotColorTone/MotColorTone.apk`): AI Color Tone & Pantone tuning engine.
* `CameraExtensionsProxy.apk` (74 KB, `/system/app/CameraExtensionsProxy/CameraExtensionsProxy.apk`): Motorola OEM Camera2/CameraX Extensions implementation.

---

## 2. Core Image Processing Pipeline Breakdown

Analysis of native shared libraries (`lib/arm64-v8a/`) inside `MotCamera5` reveals Motorola's actual multi-vendor computational photography stack:

| Pipeline Stage | Native Library / Component | Algorithmic Purpose |
| :--- | :--- | :--- |
| **Multi-Frame HDR** | `libBSTMultiExpoGainHDR_v2.so`<br>`libanc_hdr_check_moto_vienna2.so` | Best Image / ANC multi-exposure gain HDR synthesis tailored specifically for `vienna2` (Edge 50 Neo). |
| **Night Vision / RAW Stacking** | `libanc_hdr_adapter_rear_main.so`<br>`libanc_hdr_adapter_rear_tele.so`<br>`libanc_hdr_adapter_rear_uw.so` | RAW Night Vision (`raw_nv_se`) multi-frame capture with exposure bracketing. |
| **Chromatic Aberration** | `libBSTPurpleFringeRemove_7450.so` | Eliminates purple fringing / lateral chromatic aberration on high-contrast edges for MTK Dimensity ISP. |
| **Portrait / Bokeh** | `libarcsoft_singlecam_bokeh_mtk24m_vienna2.so`<br>`libminiaturebokeh.arcsoft_mtk24m_vienna.so` | ArcSoft single-camera and multi-camera depth mapping and progressive synthetic bokeh. |
| **Skin & Beautification** | `libNvBeautyEffect_mtk.so`<br>`libNvBeautySdkCore.so`<br>`libNvBeautySdkCoreV2.so` | Meishe facial landmark tracking (`ms_face106_v3.0.0.model`) and texture-preserving micro-dermabrasion. |
| **AI Color / Tone** | `libpersonalcolortone.arcsoft.so`<br>`assets/style/portrait/model/` | ArcSoft personal color tone engine + 3D LUT mapping (`Bavarian Natural`, `Tokyo Natural`, `Nordic Vibrant`). |
| **HDR Gain Map Output** | `libultrahdr.so` | Google standard Ultra HDR JPEG encoder with embedded recovery gain map. |

---

## 3. Confirmed Camera2 Vendor Tags Used by Motorola

Motorola's camera app communicates with the MediaTek Dimensity HAL through two namespaces:
1. `com.mediatek.*` (Platform-level MediaTek Imagiq ISP controls)
2. `com.lenovo.moto.*` (Motorola custom camera framework & sensor controls)

### 3.1 MediaTek Hardware ISP Controls
* `com.mediatek.control.capture.zsl.mode`: Controls MediaTek hardware Zero Shutter Lag ring buffering (0 = Off, 1 = On).
* `com.mediatek.control.capture.early.notification.trigger`: Triggers early shutter notification to minimize perceived capture latency.
* `com.mediatek.control.capture.hintForIspFrameCount`: Informs the ISP how many burst frames are incoming.
* `com.mediatek.control.capture.hintForIspFrameIndex`: Frame index within the burst stack.
* `com.mediatek.mfnrfeature.mfbmode`: MediaTek Multi-Frame Noise Reduction (MFNR) mode selector (`[0, 1, 2, 255]`).
* `com.mediatek.mfnrfeature.bsscount`: Best Shot Selector frame sample count.
* `com.mediatek.3afeature.aeIspGain`: Direct ISP sensor analog/digital gain override.

### 3.2 Motorola Custom Sensor & Capture Controls
* `com.lenovo.moto.control.capture_zsl_mode`: Motorola HAL ZSL capture latch.
* `com.lenovo.moto.control.supernight`: Super Night mode toggle.
* `com.lenovo.moto.control.raw_nv_se_capture`: RAW Night Vision Special Edition trigger (captures multi-frame RAW bursts with sub-frame EV brackets).
* `com.lenovo.moto.control.raw_nv_se_frame_number`: Explicit frame count for RAW Night bursts.
* `com.lenovo.moto.control.raw_nv_se_ev0_exp_time`: EV0 reference frame exposure time in nanoseconds.
* `com.lenovo.moto.control.raw_nv_se_low_ev`: Underexposed bracket frame for highlight recovery.
* `com.lenovo.moto.control.raw_nv_se_ultralow_ev`: Ultra-low exposure bracket frame for intense point-light sources.
* `com.lenovo.moto.sensor.info.exposureTimeRange`: **[100000, 32000000000]** (Enables long exposures up to **32 seconds** for astro/tripod night mode, compared to the standard Camera2 limit of 0.4 seconds).
* `com.lenovo.moto.quadra_cfa.do_remosaic`: Hardware Quad-Bayer remosaic trigger for full 50 MP (8192x6144) Sony IMX896 capture.
* `com.lenovo.moto.quadra_cfa.is_remosaic_by_hw`: Confirmed `1` (hardware-accelerated remosaicing on Dimensity 7300).
* `com.lenovo.moto.adrc.enable` & `com.lenovo.moto.adrc.gain`: Adaptive Dynamic Range Control.
* `com.lenovo.moto.SATFallback.W2UW`: Spatial Alignment Tracking for smooth transitions between Wide (Cam 0) and Ultrawide (Cam 2).

---

## 4. Hardware Camera2 Extensions Implemented by Motorola

Through `CameraExtensionsProxy.apk`, Motorola provides public `androidx.camera.extensions` implementations hooked directly to their ISP algorithms:
* **`NIGHT`** (`NightImageCaptureExtenderImpl`): OEM multi-frame night synthesis.
* **`HDR`** (`HdrImageCaptureExtenderImpl`): OEM multi-exposure HDR synthesis.
* **`BOKEH`** (`BokehImageCaptureExtenderImpl`): OEM ArcSoft-powered portrait depth blur.
* **`BEAUTY`** (`BeautyImageCaptureExtenderImpl`): Meishe-powered facial smoothing.
* **`AUTO`** (`AutoImageCaptureExtenderImpl`): Automatic scene-based processing.

---

## 5. Architectural Implications & Optimizations for GXVcam

1. **Hybrid Capture Pipeline**:
   * **Primary Mode (GXV Custom Engine)**: RAW_SENSOR (16-bit) → Custom Vivo Nice multi-frame fusion on Vulkan → Zeiss/Vivid color science → Ultra HDR JPEG.
   * **Night Mode Extension**: When on a tripod, utilize `com.lenovo.moto.sensor.info.exposureTimeRange` to unlock 32-second exposures for astrophotography.
   * **Fallback Mode (OEM Extensions)**: Seamlessly invoke Motorola's native `CameraExtensionCharacteristics` for quick single-tap Night or Portrait captures without running full Vulkan pipelines if the battery is low.
2. **Dynamic Black Level & ADRC**:
   * We know Motorola uses `com.lenovo.moto.adrc.enable` and `adrc.gain` alongside MediaTek ISP gain. Integrating these tags guarantees zero magenta cast in high-contrast scenes.
3. **Hardware 50 MP Remosaic**:
   * The Sony IMX896 (LYT-700C) supports full-resolution output via `com.lenovo.moto.quadra_cfa.do_remosaic = 1`, offloading the heavy 8192x6144 demosaic directly to the Dimensity 7300 hardware.
