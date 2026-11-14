# Sprint 8: GXV Pro Video & Cross-Lens Cinema Pipeline (`vienna` / Dimensity 7300)

## 1. Hardware Boundaries (`MT6878` / Motorola Edge 50 Neo)

| Subsystem | Hardware / Codec Reality on Dimensity 7300 (`vienna`) | Sprint 8 Strategy |
|---|---|---|
| **H.264 / AVC** | Dedicated HW encoder (`c2.mtk.avc.encoder`), 1080p60 & 4K30 | Default high-compatibility 8-bit SDR profile |
| **H.265 / HEVC** | Dedicated HW encoder (`c2.mtk.hevc.encoder`), supports **Main10 10-bit** | Default Cinema 10-bit profile (`HEVCProfileMain10` + `COLOR_STANDARD_BT2020` + HLG / `GXV-Log`) |
| **AV1** | Dedicated **HW decoder** (`c2.mtk.av1.decoder`); no HW AV1 encoder | Dynamic `MediaCodecList` probe; falls back to tuned `c2.android.av1.encoder` (1080p60 low-latency real-time config, tagged `AV1 (Beta SW)`) |
| **Sensors** | `ULTRA_WIDE` (`GC13A2`, 0.6x), `MAIN` (`LYT-700C`, 1x/2x), `TELE_3X` (`S5K3K1`, 3x+) | Seamless lens handoff with hysteresis, standby pre-warm, EMA RGGB color lock & 12-frame dissolve |
| **Audio** | Dual internal mics + USB-C / BLE / wired headsets | `AudioDeviceCallback` enumeration, `AudioRecord.setPreferredDevice`, API 29+ `Audio Zoom`, 80Hz Butterworth HPF wind cut, stereo L/R dBFS VU telemetry |
| **Shader** | ARM Mali-G615 MC2 (OpenGL ES 3.0 / Vulkan 1.3) | Real-time 60fps `video_npu_lut_vibrance.frag` via `CameraEffect` (`SurfaceProcessor`) -> `PreviewView` + `MediaCodec.createInputSurface()` |

---

## 2. Core Components

### 2.1 Multi-Codec Video Engine (`org.gxvcam.video.GxvVideoCodecManager`)
- **Codec Probing**: Scans `MediaCodecList(MediaCodecList.REGULAR_CODECS)` to detect hardware-accelerated encoders (`!isSoftwareOnly` / `c2.mtk.*`), 10-bit HEVC `HEVCProfileMain10`, and `hasHardwareAv1Decoder()` (`c2.mtk.av1.decoder`).
- **Format Configuration**:
  - `H264_AVC`: `video/avc`, 8-bit SDR, up to 35 Mbps @ 1080p60.
  - `H265_HEVC`: `video/hevc`, `HEVCProfileMain10`, `COLOR_STANDARD_BT2020`, `COLOR_RANGE_LIMITED`, `COLOR_TRANSFER_HLG` when 10-bit `HLG_HDR10` or `GXV_LOG_ACES` is active.
  - `AV1`: `video/av01`, prefers HW encoder if present, otherwise `c2.android.av1.encoder` with real-time `KEY_OPERATING_RATE` and bounded bitrate (`14 Mbps` @ 1080p60).
- **Audio+Video Muxing Lifecycle**: Drains video `MediaCodec` + AAC `MediaCodec` (`audio/mp4a-latm`, 48kHz stereo, 192 kbps) into `MediaMuxer` (`DCIM/GXVcam` via `MediaStore`), guaranteeing `stop()` and `release()` inside `finally` blocks.

### 2.2 Seamless Lens Handoff & Cross-Lens Color Lock (`org.gxvcam.video.SeamlessLensZoomController`)
- **Zoom Handoff Boundaries**: Routes between `ULTRA_WIDE` (`0.6x..0.95x`), `MAIN` (`1.0x..2.99x`), and `TELE_3X` (`>=3.0x`) with hysteresis so jitter around `1.0x` or `3.0x` does not ping-pong.
- **Standby Pre-Warm**: Emits pre-warm hints when zoom enters `[0.85x..1.05x]` or `[2.65x..3.05x]`.
- **Cross-Lens AWB Color Lock**:
  - Tracks `CaptureResult.COLOR_CORRECTION_GAINS` (`RggbChannelVector`) on the `MAIN` (`LYT-700C`) anchor.
  - Calibrates `ULTRA_WIDE` (`GC13A2`) and `TELE_3X` (`S5K3K1`) relative white-balance scalars and applies Exponential Moving Average (`alpha = 0.18`) to `unifiedRggbGains` so lens switches never jump in tint/white balance.
  - Drives a 12-frame (~200ms at 60fps) `transitionBlendFactor` dissolve across lens switches.
- **180-Degree Shutter Rule**: `compute180ShutterExposureNs(fps)` locks exposure time to `1 / (2 * fps)` (`8,333,333 ns` = `1/120s` at 60fps; `16,666,667 ns` = `1/60s` at 30fps).

### 2.3 Pro Audio Multi-Mic Router & Live VU Meter (`org.gxvcam.audio.ProAudioInputRouter`)
- Enumerates `AudioManager.getDevices(GET_DEVICES_INPUTS)` and registers an `AudioDeviceCallback` (always unregistered in `stopMonitoring()` / `close()`).
- Applies `AudioRecord.setPreferredDevice()` for Built-in Dual Mic, Audio Zoom, or external USB-C / Bluetooth LE/SCO / Wired mics.
- Couples camera zoom ratio to API 29+ `MicrophoneDirection` (`setPreferredMicrophoneFieldDimension`).
- Filters 48kHz stereo PCM through a 2nd-order **80 Hz Butterworth High-Pass Biquad Filter** (`WindCutBiquadFilter`) when wind-noise cut is active.
- Emits real-time stereo L/R dBFS (`-60..0 dBFS`) via `StateFlow<Pair<Float, Float>>` and shares the single `AudioRecord` PCM stream with `GxvVideoCodecManager` to avoid dual-`AudioRecord` contention.

### 2.4 Real-Time 60fps GLES Shader (`app/src/main/shaders/video_npu_lut_vibrance.frag` + `GxvVideoGlRenderer`)
- Attached to CameraX `Preview` via `CameraEffect` (`SurfaceProcessor`).
- Executes per-frame on Mali-G615 GPU:
  1. External OES sampling with `SurfaceOutput.updateTransformMatrix`.
  2. Cross-lens `uUnifiedRggbGains` AWB stabilization + 12-frame dissolve.
  3. 4-way weighted `33^3` 3D-LUT blending (`uLutWeights` from `Apu655HardwareAccelerator` / Cinema LUT presets).
  4. Optional `GXV-Log` (ACEScct-style logarithmic shoulder/toe) transfer curve.
  5. Opponent-chroma / ICtCp skin-protected vibrance (`uVibrance`).
- Renders simultaneously to the viewfinder `SurfaceOutput` and (while recording) to `MediaCodec.createInputSurface()`.

### 2.5 Material 3 Expressive Pro Video Cinema HUD & Tactile Haptics
- `org.gxvcam.ui.ProVideoCinemaOverlay`:
  - Top capsule: live recording timecode, active codec/fps/color badge, `180° Shutter` lock chip (`1/120s`), and Stereo L/R dBFS VU meter + Audio Matrix quick drawer.
  - Bottom/side cinema deck: Optical Detents (`.6x`, `1x`, `2x`, `3x`, `6x`), **Motorized Smooth Zoom Rocker** (logarithmic constant-velocity zoom that resets `rockerVelocity = 0f` on `onDragEnd`/`onDragCancel`), and Cinema Look / Codec sheet (codec picker locked while `isRecording == true`).
- **User Haptic Feedback Setting**: `CameraManualSettings.isZoomScrollHapticsEnabled` (persisted via DataStore and exposed in `CameraSettingsSheet` -> `Pro & UI`) globally controls tactile ticks/snaps on all zoom controls and scroll/slider bars.
