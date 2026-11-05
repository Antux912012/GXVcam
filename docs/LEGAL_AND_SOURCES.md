# Legal boundaries & reusable sources

GXVcam aims for **a GCam-style UX** and a **vivo-style look** on Motorola MediaTek phones.
Neither Google Camera nor vivo Camera is open source, so the project has to **re-implement**
both rather than reuse them. This doc covers what is allowed.

## What we must NOT do

| Don't | Why |
| :--- | :--- |
| Ship, patch, or decompile-and-copy GCam APKs/`.so` files (incl. community "ports") | Proprietary Google code. GCam mods are a legal grey area and can't be the base of a distributable app. |
| Extract or copy vivo Camera binaries, models, LUTs, or tuning files | Proprietary vivo code/data. |
| Use the "ZEISS", "vivo", "Pixel", or "Google Camera" names/logos in the app or store listing | Trademarks. Internal docs can say "vivo-like look" as a description of the target. |
| Copy Google's icons, sounds, or animations 1:1 | Copyrighted assets. Material Symbols (Apache 2.0) and Material 3 components are fine. |

## What we CAN do

* Re-implement **published algorithms** (papers, patents that have expired, or are only used as ideas).
* Match the **look** of vivo output by calibrating our own tone curves/LUTs against photos we take
  ourselves with a vivo phone and the Edge 50 Neo side by side (same scene, ColorChecker in frame).
* Build a UI that follows **GCam interaction patterns** (layout, gestures) with our own assets.

## Open-source building blocks (verify licences before vendoring)

| Project | Use in GXVcam | Licence (check) |
| :--- | :--- | :--- |
| [HDR+ paper (Hasinoff et al., SIGGRAPH Asia 2016)](https://www.hdrplusdata.org/) + public burst dataset | Align/merge algorithm reference, test data | Paper / CC dataset |
| [Handheld Multi-Frame Super-Resolution (Wronski et al., 2019)](https://arxiv.org/abs/1905.03277) | Super-res zoom / merge on Bayer | Paper |
| [timothybrooks/hdr-plus](https://github.com/timothybrooks/hdr-plus) (Halide) | Readable HDR+ re-implementation | MIT |
| [PhotonCamera](https://github.com/eszdman/PhotonCamera) | Android Camera2 RAW burst + GPU pipeline, many MTK quirks documented | GPL-3.0 (copyleft: affects our licence) |
| [Open Camera](https://opencamera.org.uk/) | Camera2 edge-case handling reference | GPL-3.0 |
| [MotionCam (open version)](https://github.com/mirsadm/motioncam) | RAW capture + denoise on Android | Apache-2.0 (check repo) |
| [libultrahdr](https://github.com/google/libultrahdr) | Ultra HDR (gain-map JPEG) encoding | Apache-2.0 |
| [CameraX / Camera2 samples](https://github.com/android/camera-samples) | Session/ZSL patterns | Apache-2.0 |
| LiteRT (TensorFlow Lite) + GPU delegate / MediaTek NeuroPilot delegate | Segmentation (face/skin/sky) | Apache-2.0 / vendor SDK |

> [!IMPORTANT]
> **Licence decision (Phase 0):** reusing PhotonCamera/Open Camera code makes GXVcam GPL-3.0.
> If a permissive licence is wanted, only use them as *reading material* and stick to
> Apache/MIT components.
