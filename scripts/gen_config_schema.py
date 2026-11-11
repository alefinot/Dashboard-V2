#!/usr/bin/env python3
"""Generate the Android app's settings schema from the two authoritative sources.

    python scripts/gen_config_schema.py            # write the asset
    python scripts/gen_config_schema.py --check     # fail if the asset is stale

Inputs (never edited by hand on the app side):
  * src/config.cpp - CFG_TABLE. Every writable key, its kind, its firmware band
                      and its compiled default. This is what config.set accepts.
  * scripts/config_schema_presentation.json - the presentation tree: human labels,
                      grouping into groups -> cards -> sections, units, dropdown
                      options, which rows are advanced, which cards a custom widget
                      drives, the unit scales and the group icon names.

Output:
  android/app/src/main/assets/config_schema.json

The JSON is data, not code: the app renders from it and never decides on its own
which keys exist. Two settings screens that drift apart is exactly the failure
this file exists to prevent (NATIVE-SETTINGS-BLE-PLAN.md SS4), so the drift alarm
lives next to the generator: --check rebuilds the schema and compares it byte for
byte against the checked-in asset, and exits non-zero on any difference.

The presentation tree used to be scraped out of src/webui.html by a small JS reader
that understood that page's configMap. The unit's web page is gone (BLE-ONLY-
ARCHITECTURE-PLAN.md SS6), so the tree was extracted once into the JSON named above
and that reader went away with the page. Labels and grouping are hand-edited there
now; CFG_TABLE still decides which keys exist at all, and a row the presentation
never names still reaches the app under a generated label.

Output:
  android/app/src/main/assets/config_schema.json

The JSON is data, not code: the app renders from it and never decides on its own
which keys exist. Two settings screens that drift apart is exactly the failure
this file exists to prevent (NATIVE-SETTINGS-BLE-PLAN.md SS4), so the drift alarm
lives next to the generator: --check re-runs both parsers, compares the key sets
against CFG_TABLE, and exits non-zero on any difference.

The generator does not replace the host-side table checks. scripts/check_config_table.py
still validates config.cpp itself, and SS8's "read back what you wrote" rule lives in
the app, not here.
"""

import argparse
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CONFIG_CPP = os.path.join(ROOT, "src", "config.cpp")
# The presentation half of the schema, kept in the repo after the unit's web page
# was deleted (BLE-ONLY-ARCHITECTURE-PLAN.md SS6). `--dump-presentation` writes it
# out of webui.html; the generator reads this file instead of the page.
PRESENTATION_JSON = os.path.join(ROOT, "scripts", "config_schema_presentation.json")
OUT_JSON = os.path.join(ROOT, "android", "app", "src", "main", "assets", "config_schema.json")

# ---------------------------------------------------------------------------
# 1. CFG_TABLE
# ---------------------------------------------------------------------------

KIND_BY_TAG = {
    "CK_INT": "int",
    "CK_UINT": "int",
    "CK_FLT": "float",
    "CK_BOOL": "bool",
    "CK_STR": "text",
    "CK_SECRET": "secret",
}

# One row of CFG_TABLE, spelled out by hand in config.cpp: twelve fields, in the
# order of struct CfgParam. A band or a default may be a named constant
# (SPI_SPEED_MIN_HZ, AP_PASSWORD_DEFAULT) rather than a literal, so every numeric
# field also accepts an identifier, which is resolved through the headers below.
NUM = r"-?(?:0x[0-9a-fA-F]+|\d+|[A-Za-z_][\w]*)"
FLT = r"-?(?:[\d.]+(?:[eE][-+]?\d+)?[fF]?|[A-Za-z_][\w]*)"

ROW_RE = re.compile(
    r"^\s*\{\s*&(?P<var>\w+)\s*,\s*\"(?P<name>\w+)\"\s*,\s*\"(?P<nvs>\w+)\"\s*,"
    r"\s*(?P<dstr>nullptr|\x22(?:[^\x22\x5c]|\x5c.)*\x22|[A-Za-z_]\w*)\s*,"
    r"\s*(?P<lo>" + NUM + r")\s*,\s*(?P<hi>" + NUM + r")\s*,\s*(?P<di>" + NUM + r")\s*,"
    r"\s*(?P<flo>" + FLT + r")\s*,\s*(?P<fhi>" + FLT + r")\s*,\s*(?P<df>" + FLT + r")\s*,"
    r"\s*(?P<size>\d+|\(uint16_t\)\s*sizeof\s*\(\s*\w+\s*\))\s*,\s*(?P<kind>CK_\w+)\s*\}",
    re.MULTILINE,
)

# char WIFI_SSID[64] - the buffer a CK_STR row writes into, and therefore the
# maxLength the app should hold a field to. sizeof() in the table is resolved
# through this map rather than by trusting the macro's spelling.
ARRAY_RE = re.compile(r"^char\s+(\w+)\[(\d+)\]", re.MULTILINE)

# const char AP_PASSWORD_DEFAULT[] = "12345678" - a shipped default spelled out
# as a named constant instead of inline in the table.
CONST_CHAR_RE = re.compile(r"^const char\s+(\w+)\[\]\s*=\s*(\x22(?:[^\x22\x5c]|\x5c.)*\x22)",
                           re.MULTILINE)

# constexpr uint32_t SPI_SPEED_MIN_HZ = 1000000UL; - a band edge named in a header.
NUM_CONST_RE = re.compile(
    r"^(?:constexpr|const)\s+\w+[^(]*?(\w+)\s*=\s*"r"(-?0x[0-9a-fA-F]+\d*|-?\d[\d.']*(?:[eE][-+]?\d+)?)[uUlLfF]*;",
    re.MULTILINE,
)
DEFINE_RE = re.compile(r"^#define\s+(\w+)\s+(-?0x[0-9a-fA-F]+\d*|-?\d[\d.']*(?:[eE][-+]?\d+)?)[uUlLfF]*\s*$",
                       re.MULTILINE)

HEADER_FILES = ["src/config.h", "src/dashboard.h", "src/config.cpp"]


def number_constants(root=ROOT):
    out = {}
    for rel in HEADER_FILES:
        path = os.path.join(root, rel)
        if not os.path.isfile(path):
            continue
        src = open(path, encoding="utf-8", errors="replace").read()
        for m in NUM_CONST_RE.finditer(src):
            out[m.group(1)] = float(m.group(2))
        for m in DEFINE_RE.finditer(src):
            out[m.group(1)] = float(m.group(2))
    return out


def _num(token, consts, key, field):
    t = token.strip()
    if re.fullmatch(r"-?0x[0-9a-fA-F]+", t):
        return float(int(t, 16))
    if re.fullmatch(r"-?\d[\d.']*(?:[eE][-+]?\d+)?[fF]?", t):
        return float(t.rstrip("fF").replace("'", ""))
    if t in consts:
        return consts[t]
    raise SystemExit(f"cannot resolve {field}={t!r} for {key}; add it to the "
                     f"generator's constant scan")


def firmware_version(path=CONFIG_CPP):
    """The compiled-in FW_VERSION this asset describes, or "" if the define moved."""
    src = open(path, encoding="utf-8").read()
    m = re.search(r'#define\s+FW_VERSION_STR\s+"([^"]+)"', src)
    return m.group(1) if m else ""


def parse_cfg_table(path=CONFIG_CPP):
    src = open(path, encoding="utf-8").read()
    sizes = {n: int(s) for n, s in ARRAY_RE.findall(src)}
    consts = number_constants()
    strs = {n: json.loads(v) for n, v in CONST_CHAR_RE.findall(src)}
    table = src[src.index("static const CfgParam CFG_TABLE[] = {"):]
    rows = []
    for m in ROW_RE.finditer(table):
        kind = KIND_BY_TAG.get(m.group("kind"))
        if kind is None:
            raise SystemExit(f"unknown CfgKind {m.group('kind')} for {m.group('name')}")
        key = m.group("name")
        raw_size = m.group("size")
        if raw_size.isdigit():
            size = int(raw_size)
        else:
            arg = re.search(r"sizeof\s*\(\s*(\w+)\s*\)", raw_size).group(1)
            if arg not in sizes:
                raise SystemExit(f"cannot resolve sizeof({arg}) for {key}")
            size = sizes[arg]
        dstr = m.group("dstr")
        row = {
            "key": key,
            "nvsKey": m.group("nvs"),
            "kind": kind,
            "default": None,
            "size": size,
        }
        if kind in ("int", "bool"):
            row["lo"] = int(_num(m.group("lo"), consts, key, "lo"))
            row["hi"] = int(_num(m.group("hi"), consts, key, "hi"))
            row["default"] = int(_num(m.group("di"), consts, key, "di"))
            if kind == "bool":
                row["default"] = 1 if row["default"] else 0
        elif kind == "float":
            row["lo"] = _num(m.group("flo"), consts, key, "flo")
            row["hi"] = _num(m.group("fhi"), consts, key, "fhi")
            # 0.30000000000000004 in a JSON asset reads as a bug; the firmware
            # prints these with cfgFormatFloat's six significant digits.
            row["default"] = float(f"{_num(m.group('df'), consts, key, 'df'):.6g}")
        else:
            d = dstr
            if d != "nullptr" and not d.startswith('"'):
                if d not in strs:
                    raise SystemExit(f"cannot resolve default constant {d} for {key}")
                d = json.dumps(strs[d])
            row["default"] = "" if d == "nullptr" else json.loads(d)
            row["maxLength"] = size
        rows.append(row)
    if not rows:
        raise SystemExit(f"no CFG_TABLE rows parsed from {path}")
    return rows


# ---------------------------------------------------------------------------
# 2. The presentation half: scripts/config_schema_presentation.json
# ---------------------------------------------------------------------------
# Human labels, grouping into groups -> cards -> sections, units, dropdown
# options, which rows are advanced, which cards a custom widget drives, the unit
# scales and the group icon names. All of this used to be scraped out of the unit's
# web page; the page is gone (BLE-ONLY-ARCHITECTURE-PLAN.md SS6), so the tree lives
# here as JSON and is edited by hand.
#
# CFG_TABLE stays the authority on which keys exist at all, what kind they are and
# what band they may hold. A key named in the presentation that the table has lost
# is an error (_base raises). A table row named nowhere still gets a control with a
# generated label - see build()'s leftovers pass - so a new parameter can never go
# silently unreachable, which is the failure this generator exists to prevent.

def load_presentation(path=PRESENTATION_JSON):
    if not os.path.isfile(path):
        raise SystemExit(
            f"{path} is missing. It carries the settings labels, grouping, unit "
            "scales and icon names that used to live in src/webui.html.")
    doc = json.load(open(path, encoding="utf-8"))
    for want in ("configMap", "unitScale", "unitFieldTypes", "iconSVG"):
        if not isinstance(doc.get(want), (list, dict)):
            raise SystemExit(f"{path} has no usable {want!r}")
    if not doc["configMap"]:
        raise SystemExit(f"{path}: configMap is empty")
    return doc


# ---------------------------------------------------------------------------
# 3. The keys V2 has no feature for
# ---------------------------------------------------------------------------
# The V2 unit has no radio to join a network with, no hotspot to join it back
# from, and no SNTP client (BLE-ONLY-ARCHITECTURE-PLAN.md SS2.1). Their rows stay
# in CFG_TABLE for now - config.get still streams them, backups still carry them -
# but a settings screen that offers a field for a feature the unit cannot do is a
# lie, so the schema lists them under "hidden" and the app renders nothing for
# them. The rows themselves are deleted in config.cpp; when a key on this list is
# gone from the table the entry stops matching and drops out on its own.

DEAD_KEYS = [
    # Station Wi-Fi: five stored networks, credentials and the retry timeout.
    "WIFI_SSID", "WIFI_SSID_1", "WIFI_SSID_2", "WIFI_SSID_3", "WIFI_SSID_4",
    "WIFI_PASSWORD", "WIFI_PASSWORD_1", "WIFI_PASSWORD_2", "WIFI_PASSWORD_3",
    "WIFI_PASSWORD_4",
    "WIFI_ATTEMPT_SECONDS",
    # The Dashboard_Config hotspot, which V2 does not open.
    "AP_PASSWORD",
    # SNTP: there is no client on the unit; the clock comes from the phone.
    "NTP_ENABLED", "NTP_SERVER",
]

# Keys that are NOT dead: the firmware still applies them, they keep their NVS
# name, and every config backup still carries them - but the app must not offer
# a field for them. A number a phone can move is a number someone will move
# while the engine is running, so it stays off the settings page; setting one is
# a bench job over the RPC, not a rider's slider. It is listed here, and not
# simply left out of the presentation tree, so the drift check keeps accounting
# for the row: verify() wants every CFG_TABLE row to land in exactly one of
# three places - a control, the advanced switch, or the hidden list.
APP_HIDDEN_KEYS: list[str] = [
    # Fuel-trim reference voltage (README issue #55): the divider hangs off the
    # 3.3 V rail, and typing a different number only corrupted the suggested
    # excitation resistor.
    "FUEL_ADC_VREF",
]

# ---------------------------------------------------------------------------
# 4. Build the schema
# ---------------------------------------------------------------------------

# Cards the WebUI drives with hand-written widget markup instead of plain rows.
# The native app needs a hand-written screen for each one, and it is the
# `widget` tag in the JSON, not a key name, that tells it which.
# The layout this loader understands. The app refuses an asset that does not
# match rather than rendering a shape it has to guess at.
SCHEMA_VERSION = 1

# The master switch for the tab's own "Show advanced" - the row the WebUI carries
# inside General, and the one row that is not a row here.
ADVANCE_KEY = "ADV_MODE"

# Cards that work without a single config row behind them. (The firmware push used to
# be one of these - `"firmwareUpdate"` - until the card moved to App Settings on
# 2026-11-09, where it fetches its own file from the GitHub releases; the settings
# page is now only settings.)
LIVE_WIDGETS = {"noiseBench", "tripReset", "ambientCalibrate",
                "tempCalibrate", "batteryCalibrate", "odometerSet"}

# The display factors behind the app's displayFromMetric / unitLabel.
IMPERIAL = {
    "speed": 0.621371,      # km/h -> mph
    "length": 1 / 25.4,     # mm -> in
    "volume": 0.264172,     # L -> gal
    "temp": "c_to_f",       # C -> F: v * 1.8 + 32
    "labelSwap": {"°C": "°F", "km/h": "mph", "mm": "in",
                  "L": "G", "km": "mi"},
}

WIDGET_BY_CARD = {
    "Trip Reset": "tripReset",
    "Wifi": "wifi",
    "Auto Brightness": "ambientCalibrate",
    "Engine Temperature": "tempCalibrate",
    "Battery Voltage": "batteryCalibrate",
    "Odometer": "odometerSet",
    "Noise Bench": "noiseBench",
}

REBOOT_KEYS = ["DISPLAY_ROTATION", "DISPLAY_WIDTH", "DISPLAY_HEIGHT", "GPS_BAUD",
               "NTP_ENABLED", "NTP_SERVER"]

UNIT_SCALE = {}
UNIT_TYPES = {}

EXTRAS = {
    "fuelCalOhms": {"kind": "floatArray", "label": "Fuel sender calibration (ohm)",
                    "countKey": "FUEL_TOUCH_POINTS"},
    "ambientLightValue": {"kind": "int", "label": "Ambient light reading (ADC)",
                          "live": True},
    "core_version": {"kind": "text", "label": "ESP core version", "readOnly": True},
    "build_version": {"kind": "text", "label": "Firmware build (compiled in)",
                      "readOnly": True},
}


def slug(title):
    return re.sub(r"[^a-z0-9]+", "_", title.lower()).strip("_")


def icon_name(ref):
    """`{"__ref": "iconSVG.sensors"}` -> `sensors`, the name of the group's icon.

    The unit's page draws the group header's badge from an inline lucide path, and the
    app draws the same shape from `res/drawable/ic_grp_<name>.xml`. Carrying the SVG into
    the asset would put a second copy of the drawing in the repo and make the asset a
    graphics file; carrying the *name* is enough, because the app resolves it in its own
    UI layer and an unknown name simply draws no badge.
    """
    if isinstance(ref, dict):
        ref = ref.get("__ref")
    if isinstance(ref, str) and ref.startswith("iconSVG."):
        return ref.split(".", 1)[1]
    # A group the page has no icon for (the "More settings" bucket this generator
    # invents from unlabelled rows) still gets a name, so the app never has to handle a
    # missing field.
    return "more"


def generated_label(key):
    """Label for a row the presentation tree never names: the key, split on
    underscores."""
    words = key.split("_")
    out = []
    for w in words:
        if w.isupper() and len(w) > 1:
            out.append(w.capitalize())
        else:
            out.append(w)
    return " ".join(out)


def options_of(item):
    opts = item.get("options")
    if not isinstance(opts, list):
        return None
    out = []
    for o in opts:
        if isinstance(o, dict) and "value" in o:
            out.append({"value": o["value"], "label": o.get("label", str(o["value"]))})
    return out or None


def build(rows, groups_js):
    table = {r["key"]: r for r in rows}
    hidden = {k for k in DEAD_KEYS + APP_HIDDEN_KEYS if k in table}
    out_groups = []
    labelled = set()

    for g in groups_js:
        if not isinstance(g, dict) or "title" not in g:
            continue
        group = {
            "id": slug(g["title"]),
            "title": g["title"],
            "icon": icon_name(g.get("icon")),
            "cards": [],
        }
        card = None
        section = None
        for it in g.get("items", []):
            if not isinstance(it, dict):
                continue
            typ = it.get("type")

            if typ == "card_header":
                title = it.get("label", "")
                card = {"title": title, "items": []}
                if it.get("advanced"):
                    card["advanced"] = True
                if title in WIDGET_BY_CARD:
                    card["widget"] = WIDGET_BY_CARD[title]
                group["cards"].append(card)
                section = None
                continue
            if typ in ("subtitle", "section_header"):
                if typ == "subtitle" and card is None:
                    card = {"title": it.get("label", group["title"]), "items": []}
                    group["cards"].append(card)
                else:
                    section = it.get("label")
                continue
            if typ in ("note", "status", "card_note"):
                continue
            if card is None:
                card = {"title": group["title"], "items": []}
                group["cards"].append(card)

            if typ == "xy":
                pair = _mk_pair(table, it, section, labelled)
                if pair:
                    card["items"].append(pair)
                continue
            if typ == "threshold-row":
                _emit_threshold(table, it, card, section, labelled)
                continue
            if typ == "touch-table":
                _emit_touch_table(table, it, card, section, labelled)
                continue

            key = it.get("id")
            if not key or key in hidden or key == ADVANCE_KEY:
                continue
            item = _mk_item(table, it, key, section, labelled)
            if item:
                card["items"].append(item)

        # Drop cards left with nothing in them by the pass above. A card whose
        # widget needs no config keys at all (a firmware push, a noise bench)
        # still earns its place; one whose only purpose was a dead key does not.
        group["cards"] = [c for c in group["cards"]
                          if c["items"] or c.get("widget") in LIVE_WIDGETS]
        out_groups.append(group)

    # Rows the configMap never named. They still get a control - a generated
    # label in a card at the end of the tab - because "unreachable" is the
    # failure this generator exists to prevent, not a label to accept.
    leftovers = sorted(k for k in table
                       if k not in labelled and k not in hidden and k != ADVANCE_KEY)
    by_state = {"hidden": sorted(hidden), "unlabelled": leftovers}
    if leftovers:
        card = {"title": "More settings", "items": []}
        for k in leftovers:
            item = _mk_item(table, {}, k, None, labelled, generated=True)
            if item:
                card["items"].append(item)
        out_groups.append(
            {"id": "more", "title": "More settings", "icon": "more", "cards": [card]}
        )

    schema = {
        "_generated": "scripts/gen_config_schema.py - do not edit. Sources: "
                      "src/config.cpp (CFG_TABLE) and "
                      "scripts/config_schema_presentation.json (labels, grouping).",
        # The app shows the firmware version beside the unit's own, so a schema that
        # was regenerated against the wrong tree is visible instead of silently wrong.
        "schemaVersion": SCHEMA_VERSION,
        "firmware": firmware_version(),
        "rows": len(rows),
        "groups": out_groups,
        "hidden": by_state["hidden"],
        "advancedKey": ADVANCE_KEY,
        "extras": EXTRAS,
        "imperial": IMPERIAL,
        "rebootKeys": [k for k in REBOOT_KEYS if k in table],
    }
    return schema, by_state


def _to_device(value, scale):
    """A number the WebUI shows in box units, back to the device unit."""
    if value is None or not scale or scale == 1:
        return value
    return float(value) * scale


def _base(table, key, labelled, generated=False):
    row = table.get(key)
    if row is None:
        raise SystemExit(
            f"the presentation tree names a key that CFG_TABLE does not have: {key}. "
            "Either scripts/config_schema_presentation.json is stale or config.cpp "
            "lost a row.")
    labelled.add(key)
    item = {"key": key, "kind": row["kind"], "default": row["default"], "label": ""}
    if generated:
        item["label"] = generated_label(key)
        item["generated"] = True
    if row["kind"] in ("int", "float"):
        item["min"], item["max"] = row["lo"], row["hi"]
    if row["kind"] in ("text", "secret"):
        item["maxLength"] = row["size"]
    return item, row


def _mk_item(table, ui, key, section, labelled, generated=False):
    item, row = _base(table, key, labelled, generated)
    item["label"] = ui.get("label") or item["label"] or generated_label(key)
    if not ui.get("label"):
        item["generated"] = True
    if ui.get("unit"):
        item["unit"] = ui["unit"]
    if ui.get("advanced"):
        item["advanced"] = True
    if section:
        item["section"] = section
    if key in REBOOT_KEYS:
        item["reboot"] = True

    typ = ui.get("type")
    scale = UNIT_SCALE.get(key)
    if scale:
        item["unitScale"] = scale
    utype = UNIT_TYPES.get(key)
    if utype:
        item["unitType"] = utype
    if typ == "select":
        opts = options_of(ui)
        if opts:
            item["options"] = opts
    if typ in ("range", "slider"):
        item["slider"] = True
        # The page's window, in the device unit: the page's slider is narrower
        # than the firmware band on purpose (FADE_DURATION_MS slides 100..5000
        # while the band runs to 10000), and a slider must not be able to ask
        # for a value the firmware will silently clamp.
        item["uiMin"] = _to_device(ui.get("min", item.get("min")), scale)
        item["uiMax"] = _to_device(ui.get("max", item.get("max")), scale)
        item["step"] = _to_device(ui.get("step", 1), scale)
    if typ == "color":
        item["kind"] = "color"
    # A `min`/`max` on a plain row is a narrower working window than the firmware
    # band (SPI_BUS_SPEED says 1..80 in the page, 1000..80000000 in the table);
    # keep both, and let the widget use the page's, the clamp use the table's.
    if typ not in ("range", "slider"):
        for src_key, dst in (("min", "uiMin"), ("max", "uiMax"), ("step", "uiStep")):
            if src_key in ui:
                item[dst] = _to_device(ui[src_key], scale)
    return item


def _mk_pair(table, ui, section, labelled):
    ix, iy = ui.get("idX"), ui.get("idY")
    if not ix or not iy:
        return None
    x = table.get(ix)
    y = table.get(iy)
    if not x or not y:
        raise SystemExit(f"xy pair {ix}/{iy} is not in CFG_TABLE")
    labelled.add(ix)
    labelled.add(iy)
    item = {
        "key": ix, "keyY": iy, "kind": "xy",
        "label": ui.get("label", "Position"),
        "min": x["lo"], "max": x["hi"], "minY": y["lo"], "maxY": y["hi"],
        "default": [x["default"], y["default"]],
    }
    if section:
        item["section"] = section
    return item


def _emit_threshold(table, ui, card, section, labelled):
    color, value = ui.get("colorId"), ui.get("valueId")
    label = ui.get("label", "Threshold")
    if color:
        item, _ = _base(table, color, labelled)
        item["label"] = label
        item["kind"] = "color"
        if section:
            item["section"] = section
        card["items"].append(item)
    if value:
        item, _ = _base(table, value, labelled)
        item["label"] = f"{label} (value)"
        item["unit"] = ui.get("unit", item.get("unit"))
        if section:
            item["section"] = section
        card["items"].append(item)


def _emit_touch_table(table, ui, card, section, labelled):
    count = ui.get("pointCount")
    array = ui.get("arrayId")
    if count:
        item, _ = _base(table, count, labelled)
        item["label"] = ui.get("pointLabel") or generated_label(count)
        item["widget"] = "pointCount"
        if section:
            item["section"] = section
        card["items"].append(item)
    card["items"].append({
        "key": array or "fuelCalOhms", "kind": "fuelTable",
        "label": ui.get("label", "Calibration table"),
        "countKey": count, "liveId": ui.get("liveId"),
    })
    for sub in ui.get("rows", []):
        if isinstance(sub, dict) and sub.get("id"):
            item = _mk_item(table, sub, sub["id"], section, labelled)
            if item:
                card["items"].append(item)


# ---------------------------------------------------------------------------
# 5. The drift alarm
# ---------------------------------------------------------------------------

def verify(schema, rows, by_state):
    keys = {r["key"] for r in rows}
    in_schema = set()
    for g in schema["groups"]:
        for c in g["cards"]:
            for it in c["items"]:
                in_schema.add(it["key"])
                if it.get("keyY"):
                    in_schema.add(it["keyY"])
    extras = set(schema["extras"]) | {v.get("countKey") for v in schema["extras"].values()
                                       if isinstance(v, dict)}
    missing = sorted(keys - in_schema - set(schema["hidden"]) - {ADVANCE_KEY})
    extra = sorted(in_schema - keys - extras)
    problems = []
    if missing:
        problems.append(f"{len(missing)} CFG_TABLE rows are unreachable in the app: {missing}")
    if extra:
        problems.append(f"{len(extra)} keys in the schema are not in CFG_TABLE: {extra}")
    if schema.get("schemaVersion") != SCHEMA_VERSION:
        problems.append(f"asset schemaVersion {schema.get('schemaVersion')!r} "
                        f"!= {SCHEMA_VERSION}")
    fw = firmware_version()
    if fw and schema.get("firmware") != fw:
        problems.append(f"asset describes firmware {schema.get('firmware')!r}, "
                        f"config.cpp says {fw!r}")
    if schema["rows"] != len(keys):
        problems.append(f"row count {schema['rows']} != {len(keys)} table rows")
    # Every table row lands in exactly one of three places: a control on the tab,
    # the hidden list, or the advanced switch. fuelCalOhms is a fourth kind - it
    # is an extra, not a row - so only rows are counted here.
    accounted = len(in_schema & keys) + len(schema["hidden"]) + 1  # +1: ADV_MODE
    if accounted != len(keys):
        problems.append(f"{accounted} of {len(keys)} keys accounted for: "
                        f"{len(keys) - accounted} fall through neither the tab nor the "
                        "hidden list nor the advanced switch")
    for k in schema["hidden"]:
        if k not in keys:
            problems.append(f"hidden key {k} is no longer in CFG_TABLE")
    return problems, len(in_schema)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true",
                    help="compare the existing asset with a fresh build instead of writing")
    ap.add_argument("--quiet", action="store_true")
    args = ap.parse_args()

    rows = parse_cfg_table()
    pres = load_presentation()
    groups_js = pres["configMap"]
    global UNIT_SCALE, UNIT_TYPES
    UNIT_SCALE = {k: float(v) for k, v in pres["unitScale"].items()}
    UNIT_TYPES = pres["unitFieldTypes"]
    schema, by_state = build(rows, groups_js)

    text = json.dumps(schema, indent=1, ensure_ascii=False, sort_keys=False) + "\n"

    problems, rendered = verify(schema, rows, by_state)
    written = False

    if args.check:
        if not os.path.isfile(OUT_JSON):
            print(f"MISSING {os.path.relpath(OUT_JSON, ROOT)}")
            return 1
        if open(OUT_JSON, encoding="utf-8").read() != text:
            print(f"STALE {os.path.relpath(OUT_JSON, ROOT)} - run "
                  "`python scripts/gen_config_schema.py` and commit the result")
            return 1
    else:
        os.makedirs(os.path.dirname(OUT_JSON), exist_ok=True)
        open(OUT_JSON, "w", encoding="utf-8", newline="\n").write(text)
        written = True

    if not args.quiet:
        print(f"CFG_TABLE rows      : {len(rows)}")
        print(f"keys the app renders: {rendered}")
        print(f"  labelled by presentation : {rendered - len(by_state['unlabelled'])} - see the _about note in scripts/config_schema_presentation.json")
        print(f"  generated labels      : {len(by_state['unlabelled'])}")
        print(f"advanced switch row   : 1 ({ADVANCE_KEY})")
        print(f"hidden (off the tab): {len(by_state['hidden'])}")
        print(f"extras (not in the table): {len(EXTRAS)}")
        print(f"reboot-required    : {len(schema['rebootKeys'])}")
        print(("wrote  " if written else "up to date  ") +
              f"{os.path.relpath(OUT_JSON, ROOT)} ({len(text)} bytes)")
        if by_state["unlabelled"]:
            print("generated-label rows (add a label in the presentation tree): "
                  + ", ".join(by_state["unlabelled"]))
        print("hidden keys        : " + ", ".join(schema["hidden"]))
        if problems:
            print("\nDRIFT:")
            for p in problems:
                print("  " + p)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
