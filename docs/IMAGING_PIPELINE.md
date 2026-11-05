# Imaging pipeline: fusion + "vivo-like" look

> [!NOTE]
> vivo's algorithms are proprietary and undocumented. Earlier drafts described a
> "reverse-engineered vivo Nice engine" with exact formulas and matrices: those were
> **illustrative, not real vivo data** and have been replaced. GXVcam uses **published
> algorithms** for fusion and gets the vivo-like *rendering* by **calibrating our own
> tone/colour against reference photos** (see [LEGAL_AND_SOURCES.md](LEGAL_AND_SOURCES.md)).

## 0. What "vivo look" means as a measurable target

Shoot the same scenes with a vivo reference phone (X100/X200 class) and the Edge 50 Neo.
Measure on our own photos:

| Trait | Metric we tune against |
| :--- | :--- |
| Restrained highlights, no "HDR-flat" faces | Highlight shoulder position, local contrast at faces |
| Natural/accurate colour mode | ΔE2000 on ColorChecker patches (target mean < 3, skin patches < 2) |
| Vivid mode: denser sky, cleaner greens, skin protected | Hue/chroma shift per patch vs Natural |
| Retained skin texture | High-frequency energy in face crops vs reference |
| Warm, contrasty night shots with dark blacks | Black point, shadow noise σ, WB in tungsten scenes |

## 1. Pipeline (all stages on linear data until tone mapping)

```mermaid
flowchart LR
    A["RAW burst (ZSL ring)"] --> B["Unpack + black level"]
    B --> C["Reference pick (sharpness + gyro)"]
    C --> D["Tile align (pyramid, HDR+)"]
    D --> E["Robust merge (Wiener/noise-model)"]
    E --> F["Demosaic"]
    F --> G["WB + HAL colour matrices"]
    G --> H["Local tone map + highlight shoulder"]
    H --> I["Look LUT (Natural / Vivid)"]
    I --> J["Skin + sharpen"]
    J --> K["Ultra HDR JPEG / DNG"]
```

### 1.1 Exposure strategy
* **Day / HDR:** constant **underexposed** burst (HDR+ approach): 4–8 frames at an EV that
  protects highlights, shadows recovered by merging. No bracketing needed, avoids ghosting.
* **Night:** 8–15 frames, longer exposures bounded by gyro motion (shorter when handheld,
  up to ~1 s/frame on a tripod, limited by `SENSOR_INFO_EXPOSURE_TIME_RANGE`).
* Exposure targets driven by the dual sliders (global EV + shadows) as in GCam.

### 1.2 Alignment (Hasinoff et al. 2016)
* Work on a 2x2-averaged grayscale of the Bayer frame.
* 4-level Gaussian pyramid, tile 16 (coarse levels) / 8 (fine), L2 coarse → L1 fine search.
* Seed with gyro/OIS motion when available to reduce the search radius.

### 1.3 Merge
Per tile, frequency-domain robust merge (HDR+) or the spatial variant:

$$
w_k(x) = \exp\!\left(-\frac{\max(0,\ |I_k(x+v_k) - I_\text{ref}(x)| - c\,\sigma(x))^2}{2\,\sigma(x)^2}\right),\qquad
I_\text{out}(x)=\frac{\sum_k w_k\, I_k(x+v_k)}{\sum_k w_k}
$$

with $w_\text{ref}=1$ and $\sigma^2(x) = S\cdot I(x) + O$ taken from **`SENSOR_NOISE_PROFILE`**
(per-frame, per-CFA channel) rather than a hand-tuned constant. Merge into an accumulator
one frame at a time (memory, see MTK doc §6).

### 1.4 Demosaic
Start with Malvar-He-Cutler (cheap, GPU-friendly); upgrade to a gradient-directed method
(e.g. RCD) if zipper artefacts appear on fine textures.

### 1.5 Colour
1. White balance from `SENSOR_NEUTRAL_COLOR_POINT` (as-shot neutral), user override in Pro.
2. Camera RGB → XYZ(D50) by interpolating **`SENSOR_FORWARD_MATRIX1/2`** between the two
   reference illuminants by estimated CCT (DNG spec method).
3. XYZ → linear Display P3 (wide gamut output) / sRGB.
4. Our own **ColorChecker-based correction** (3x3 or root-polynomial) per sensor, computed by
   `tools/color_checker_eval.py` (to be written), applied *after* the HAL matrices.

### 1.6 Tone
* Local tone mapping (exposure-fusion of synthetic exposures, as in HDR+) for shadows.
* Global highlight shoulder, continuous at the knee $x_0$ (default 0.72):

$$
f(x)=\begin{cases}x & x\le x_0\\ x_0+(1-x_0)\tanh\!\left(\dfrac{x-x_0}{1-x_0}\right) & x>x_0\end{cases}
$$

  (slope 1 at $x_0$, approaches 1.0 asymptotically → no hard clip).
* Ultra HDR output keeps the pre-shoulder highlights in the gain map (libultrahdr).

### 1.7 Look LUTs (Natural / Vivid)
* 33³ `.cube` LUTs in a log/display-referred space, **authored by us** to minimise the
  difference to vivo reference photos (fit on paired shots, then hand-tune).
* Vivid = chroma boost with **skin-hue protection** (hue band ~20–50° in OKLCh kept within
  ±3° hue, ≤ +5 % chroma), sky/foliage chroma +10–15 %.
* Files: `configs/luts/natural.cube`, `configs/luts/vivid.cube` (not created yet).

### 1.8 Skin
* Face/skin mask from a LiteRT segmentation model (GPU delegate).
* Frequency separation on luma inside the mask:
  $Y_\text{low}=\text{guided\_filter}(Y)$, $Y_\text{high}=Y-Y_\text{low}$,
  $Y'=\text{lerp}(Y_\text{low},\ \text{smooth}(Y_\text{low}),\ s) + g\cdot Y_\text{high}$
  with `smoothing` $s$ = 0.40 and `detail_gain` $g$ = 1.0 by default (texture kept as-is;
  > 1 only if testing shows it looks natural). *(Previous docs contradicted themselves:
  1.15× in one file, 85 % in another. Now a single tunable parameter.)*
