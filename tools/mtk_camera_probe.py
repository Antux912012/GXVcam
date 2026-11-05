#!/usr/bin/env python3
"""
GXVcam camera probe.

Dumps `dumpsys media.camera` from a connected phone over ADB, keeps the raw
text, and pulls out the facts the pipeline needs:
  * hardware level, capabilities (RAW? MANUAL_SENSOR? LOGICAL_MULTI_CAMERA?)
  * CFA arrangement, white level, black-level pattern, noise profile
  * colour calibration (ColorTransform/ForwardMatrix/Calibration, 2 illuminants)
  * available RAW stream sizes (RAW_SENSOR 0x20, RAW10 0x25, RAW12 0x26)
  * physical camera IDs behind the logical camera
  * every vendor tag (com.mediatek.*, com.motorola.*, ...) the HAL exposes

Nothing is invented: with no device attached the script exits non-zero.
It also works offline on a saved dump:  --from-file dump.txt
"""

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path

PROPS = [
    "ro.product.model", "ro.product.device", "ro.product.name",
    "ro.board.platform", "ro.soc.manufacturer", "ro.soc.model",
    "ro.build.version.release", "ro.build.version.sdk", "ro.build.fingerprint",
]

CAPABILITY_NAMES = {
    0: "BACKWARD_COMPATIBLE", 1: "MANUAL_SENSOR", 2: "MANUAL_POST_PROCESSING",
    3: "RAW", 4: "PRIVATE_REPROCESSING", 5: "READ_SENSOR_SETTINGS",
    6: "BURST_CAPTURE", 7: "YUV_REPROCESSING", 8: "DEPTH_OUTPUT",
    9: "CONSTRAINED_HIGH_SPEED_VIDEO", 10: "MOTION_TRACKING",
    11: "LOGICAL_MULTI_CAMERA", 12: "MONOCHROME", 13: "SECURE_IMAGE_DATA",
    14: "SYSTEM_CAMERA", 15: "OFFLINE_PROCESSING", 16: "ULTRA_HIGH_RESOLUTION_SENSOR",
    17: "REMOSAIC_REPROCESSING", 18: "DYNAMIC_RANGE_TEN_BIT", 19: "STREAM_USE_CASE",
    20: "COLOR_SPACE_PROFILES",
}
HW_LEVELS = {0: "LIMITED", 1: "FULL", 2: "LEGACY", 3: "LEVEL_3", 4: "EXTERNAL"}
CFA_NAMES = {0: "RGGB", 1: "GRBG", 2: "GBRG", 3: "BGGR", 4: "RGB", 5: "MONO", 6: "NIR"}
RAW_FORMATS = {0x20: "RAW_SENSOR", 0x25: "RAW10", 0x26: "RAW12", 0x24: "RAW_PRIVATE"}

KEYS_OF_INTEREST = [
    "android.info.supportedHardwareLevel",
    "android.request.availableCapabilities",
    "android.lens.facing",
    "android.lens.info.availableFocalLengths",
    "android.sensor.info.colorFilterArrangement",
    "android.sensor.info.whiteLevel",
    "android.sensor.info.pixelArraySize",
    "android.sensor.info.activeArraySize",
    "android.sensor.info.exposureTimeRange",
    "android.sensor.info.sensitivityRange",
    "android.sensor.blackLevelPattern",
    "android.sensor.referenceIlluminant1",
    "android.sensor.referenceIlluminant2",
    "android.sensor.colorTransform1", "android.sensor.colorTransform2",
    "android.sensor.forwardMatrix1", "android.sensor.forwardMatrix2",
    "android.sensor.calibrationTransform1", "android.sensor.calibrationTransform2",
    "android.logicalMultiCamera.physicalIds",
    "android.lens.info.minimumFocusDistance",
]

SECTION_RE = re.compile(r"==\s*Camera HAL device\s+(\S+).*static information", re.I)
ENTRY_RE = re.compile(r"^\s*([A-Za-z_][\w.]*)\s+\((?:0x)?[0-9a-fA-F]+\):\s*(\w+)\[(\d+)\]")


def adb(args, serial=None):
    cmd = ["adb"] + (["-s", serial] if serial else []) + args
    try:
        return subprocess.run(cmd, capture_output=True, text=True, check=True,
                              timeout=60).stdout
    except FileNotFoundError:
        sys.exit("[ERROR] adb not found in PATH")
    except subprocess.CalledProcessError as e:
        sys.exit(f"[ERROR] {' '.join(cmd)} failed: {e.stderr.strip()}")


def pick_device():
    lines = adb(["devices"]).strip().splitlines()[1:]
    devs = [l.split() for l in lines if l.strip()]
    ready = [d[0] for d in devs if len(d) > 1 and d[1] == "device"]
    for d in devs:
        if len(d) > 1 and d[1] != "device":
            print(f"[WARN] {d[0]} is '{d[1]}' (authorize USB debugging on the phone)")
    if not ready:
        sys.exit("[ERROR] No authorized device. Enable USB debugging, accept the RSA prompt, retry.")
    if len(ready) > 1:
        print(f"[WARN] Multiple devices, using {ready[0]} (pass --serial to choose)")
    return ready[0]


def parse_value(raw):
    """Tokenize a dumpsys value block. Handles ints, floats, rationals
    printed as '(128 / 128)', and enums printed by name ('BACK', 'FULL')."""
    vals = []
    raw = re.sub(r"\(\s*(-?\d+)\s*/\s*(-?\d+)\s*\)", r" \1/\2 ", raw)
    for tok in raw.replace("[", " ").replace("]", " ").split():
        if re.fullmatch(r"-?\d+/-?\d+", tok):
            n, d = (int(x) for x in tok.split("/"))
            vals.append(n / d if d else 0.0)
        elif re.fullmatch(r"-?\d+", tok):
            vals.append(int(tok))
        elif re.fullmatch(r"-?\d*\.\d+(?:e[-+]?\d+)?|-?\d+e[-+]?\d+", tok, re.I):
            vals.append(float(tok))
        else:
            vals.append(tok)  # enum name or string
    return vals


def enum_name(v, table):
    """Value may already be a name (newer dumpsys) or a number."""
    return v if isinstance(v, str) else table.get(v, v)


def parse_dumpsys(text):
    """Return {hal_device_name: {tag: {"type":..,"count":..,"values":[..]}}}."""
    cams, cur, entry = {}, None, None
    for line in text.splitlines():
        m = SECTION_RE.search(line)
        if m:
            cur = cams.setdefault(m.group(1), {})
            entry = None
            continue
        if line.startswith("==") and "static information" not in line:
            cur, entry = None, None  # dynamic/other sections: ignore
            continue
        if cur is None:
            continue
        m = ENTRY_RE.match(line)
        if m:
            entry = {"type": m.group(2), "count": int(m.group(3)), "raw": ""}
            cur[m.group(1)] = entry
        elif entry is not None and line.strip().startswith(("[", "(")) or (
                entry is not None and entry["raw"] and not entry["raw"].rstrip().endswith("]")):
            entry["raw"] += " " + line.strip()
    for tags in cams.values():
        for e in tags.values():
            e["values"] = parse_value(e.pop("raw"))
    return cams


def raw_stream_sizes(tags):
    out = {}
    for key in ("android.scaler.availableStreamConfigurations",
                "android.scaler.availableStreamConfigurationsMaximumResolution"):
        v = tags.get(key, {}).get("values", [])
        for i in range(0, len(v) - 3, 4):
            fmt, w, h, is_input = v[i:i + 4]
            # direction printed as 0/1 on older builds, OUTPUT/INPUT on newer ones
            if fmt in RAW_FORMATS and is_input in (0, "OUTPUT"):
                out.setdefault(RAW_FORMATS[fmt], set()).add(f"{w}x{h}")
    area = lambda s: -(int(s.split("x")[0]) * int(s.split("x")[1]))
    return {k: sorted(s, key=area) for k, s in out.items()}


def summarize(cams):
    summary = {}
    for name, tags in cams.items():
        cam_id = name.rsplit("/", 1)[-1]
        g = lambda k: tags.get(k, {}).get("values", [])
        caps = [enum_name(c, CAPABILITY_NAMES) for c in g("android.request.availableCapabilities")]
        lvl = g("android.info.supportedHardwareLevel")
        cfa = g("android.sensor.info.colorFilterArrangement")
        facing = (g("android.lens.facing") or [None])[0]
        summary[cam_id] = {
            "hal_device": name,
            "facing": str(enum_name(facing, {0: "FRONT", 1: "BACK", 2: "EXTERNAL"})).lower(),
            "hardware_level": enum_name(lvl[0], HW_LEVELS) if lvl else None,
            "capabilities": caps,
            "supports_raw": "RAW" in caps,
            "cfa": enum_name(cfa[0], CFA_NAMES) if cfa else None,
            "raw_streams": raw_stream_sizes(tags),
            "key_values": {k: g(k) for k in KEYS_OF_INTEREST if k in tags},
            "vendor_tags": sorted(k for k in tags if not k.startswith("android.")),
        }
    return summary


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--output-dir", "-o", default="probe_out")
    ap.add_argument("--serial", "-s")
    ap.add_argument("--from-file", help="parse a saved dumpsys instead of querying a device")
    a = ap.parse_args()

    out = Path(a.output_dir)
    out.mkdir(parents=True, exist_ok=True)
    props = {}

    if a.from_file:
        text = Path(a.from_file).read_text(errors="replace")
    else:
        serial = a.serial or pick_device()
        props = {p: adb(["shell", "getprop", p], serial).strip() for p in PROPS}
        print(f"[INFO] {props['ro.product.model']} ({props['ro.product.device']}), "
              f"SoC {props['ro.soc.model'] or props['ro.board.platform']}, SDK {props['ro.build.version.sdk']}")
        text = adb(["shell", "dumpsys", "media.camera"], serial)
        (out / "dumpsys_media_camera.txt").write_text(text)

    cams = parse_dumpsys(text)
    if not cams:
        sys.exit("[ERROR] No 'Camera HAL device ... static information' sections found. "
                 "Raw dump kept for manual inspection.")

    report = {"props": props, "cameras": summarize(cams)}
    (out / "summary.json").write_text(json.dumps(report, indent=2))
    (out / "all_tags.json").write_text(json.dumps(cams, indent=2))

    for cid, c in report["cameras"].items():
        print(f"  cam {cid:>3} {c['facing']:<6} {str(c['hardware_level']):<8} "
              f"RAW={'yes' if c['supports_raw'] else 'no ':<3} CFA={c['cfa']} "
              f"raw={c['raw_streams']} vendor_tags={len(c['vendor_tags'])}")
    print(f"[OK] Written to {out}/ (summary.json, all_tags.json, raw dump)")


if __name__ == "__main__":
    main()
