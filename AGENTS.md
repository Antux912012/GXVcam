# GXVcam — Project Rules & Hardware Context

## Target Device Profile (`vienna` / Motorola Edge 50 Neo & Edge 60 Neo)
- **Primary Target**: Motorola Edge 50 Neo (`vienna`, MediaTek Dimensity 7300 `MT6878`, Imagiq 950 12-bit ISP, ARM Mali-G615 MC2 Vulkan 1.3 GPU, MediaTek APU 655, System Android 16 / SDK 36, Vendor HAL API 34).
- **Secondary Target**: Motorola Edge 60 Neo (Dimensity 7400, Android 15/16).

## Verified `vienna` Camera2 Sensor Table
| Physical ID | Role | Sensor | CFA | Native / Max-Res RAW16 | Notes |
|:--|:--|:--|:--|:--|:--|
| `0` | `MAIN` (1x / 2x) | Sony IMX896 / LYT-700C | `BGGR` | `4096x3072` (12.5MP binned) / `8192x6144` (50MP max-res) | OIS, Quad-Bayer remosaic at `1.5x–2.99x` or Ultra-Res 50M |
| `2` | `ULTRA_WIDE` (0.6x) | GalaxyCore GC13A2 | `GBRG` | `4160x3120` (13MP) | Fixed OIS off, 120° FOV, macro focus support |
| `3` | `TELE_3X` (3x–20x) | Samsung S5K3K1 | `GRBG` | `3648x2736` (10MP) | 73mm optical tele, OIS, Drizzle MFSR at `6x–10x` |
| `1` | `FRONT` (1x / 1.4x) | Samsung S5KJD1 | `GRBG` | `3280x2460` / `6560x4920` (32MP high-res) | Fixed-focus (`CONTROL_AF_MODE_OFF` guard required); ID `4` is secure-only & avoided |
| `5` | `LOGICAL_BACK` | Multi-camera `[0, 2, 3]` | — | `4096x3072` | Seamless `0.5x–30x` SAT zoom |

## Engineering Rules
- **No Qualcomm CamX Dependencies**: Never link or reference `libmcf-jni.so` or `com.arcsoft.node.*`. All Signature 27 pipelines execute via CameraX/Camera2 `RAW_SENSOR` ZSL bursts, C++20/Vulkan 1.3 compute shaders (`arm64-v8a`, 16 KB page-size aligned `-Wl,-z,max-page-size=16384`), and NNAPI.
- **Complete Implementations**: Always output complete functions; never leave `// TODO` or placeholder stubs in JNI or GLSL shaders.
- **Threading & Lifecycle**: Run heavy image/JNI/Vulkan work on `Dispatchers.Default` / `Dispatchers.IO` or `cameraExecutor`. Never block the UI thread or drop viewfinder FPS below 60.
- **UI & Theme**: Jetpack Compose Material 3 Expressive with `WindowInsets.safeDrawing` edge-to-edge compliance, supporting Material You, Pantone swatches, and Nord Frost (`#2E3440`, `#3B4252`, `#88C0D0`, `#81A1C1`, `#ECEFF4`).
- **Verification**: Run `./gradlew :app:testDebugUnitTest` and `./gradlew :app:assembleDebug` after every change.
