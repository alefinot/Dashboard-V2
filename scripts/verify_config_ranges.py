#!/usr/bin/env python3
"""Issue #21 verification (host-side replica).

Checks, straight from the CFG_TABLE rows in src/config.cpp, that
  1. every numeric parameter has a band and lo <= hi,
  2. no shipped default falls outside its own band (a default that gets clamped
     would log on every boot and silently change the shipped behaviour),
  3. the factory defaults still come from the table alone - the old
     FACTORY_DEFAULT_JSON blob must not come back, because a second copy of the
     defaults is a copy that can drift,
  4. the cross-field rules that sanitizeConfigPairs() enforces hold for the
     shipped defaults (min < max, the temp fade chain, warn <= crit, satellite
     ramp, baud/CPU steps).

Check 5 used to compare the WebUI's generated FIRMWARE_LIMITS mirror against these
bands. The page and scripts/make_webui_limits.py are deleted (BLE-ONLY-
ARCHITECTURE-PLAN.md SS6); the app's copy of the bands is the generated
assets/config_schema.json, and gen_config_schema.py --check is what keeps it honest.

Run from the repository root:  python scripts/verify_config_ranges.py
"""
import re
import sys

from cfg_table import NUMERIC_KINDS, all_rows, config_text, numeric_rows

text = config_text()
params = {}
errors = []

# 1 + 2: bands exist, are ordered, and their own default sits inside them.
try:
    rows = numeric_rows(text)
except KeyError as e:
    sys.exit("a band or default references a constant that is not in "
             "config.cpp / dashboard.h: %s" % e)

for name, kind, lo, hi, dflt in rows:
    params[name] = (kind, dflt, lo, hi)
    if lo > hi:
        errors.append("%s: empty band [%g..%g]" % (name, lo, hi))
    if not (lo <= dflt <= hi):
        errors.append("%s: default %g outside its band [%g..%g]"
                      % (name, dflt, lo, hi))
print("numeric parameters with bands: %d" % len(rows))
print("table rows read: %d (of which %d carry no band: bool/string/secret)"
      % (len(all_rows(text)),
         len(all_rows(text)) - sum(1 for r in all_rows(text)
                                   if r["kind"] in NUMERIC_KINDS)))

# 3: seeding writes the table defaults straight to NVS. A JSON blob would be a
# second, hand-maintained copy of the same values.
if "FACTORY_DEFAULT_JSON" in text:
    errors.append("FACTORY_DEFAULT_JSON is back in src/config.cpp: factory "
                  "defaults must come from CFG_TABLE only")
print("factory defaults: taken from the %d table defaults (no JSON blob)"
      % len(rows))


def val(name):
    return params[name][1]


# 4: the cross-field rules, same list as sanitizeConfigPairs() and the README.
pairs = [
    ("TEMP_BAR_MIN < TEMP_BAR_MAX", val("TEMP_BAR_MIN") < val("TEMP_BAR_MAX")),
    ("TEMP_BAR_MIN <= TEMP_WARN_LOW",
     val("TEMP_BAR_MIN") <= val("TEMP_WARN_LOW")),
    ("TEMP_WARN_LOW <= TEMP_WARN_YEL",
     val("TEMP_WARN_LOW") <= val("TEMP_WARN_YEL")),
    ("TEMP_WARN_YEL <= TEMP_WARN_RED",
     val("TEMP_WARN_YEL") <= val("TEMP_WARN_RED")),
    ("TEMP_WARN_RED <= TEMP_BAR_MAX",
     val("TEMP_WARN_RED") <= val("TEMP_BAR_MAX")),
    ("FUEL_WARN_RED <= FUEL_WARN_YEL",
     val("FUEL_WARN_RED") <= val("FUEL_WARN_YEL")),
    ("LIGHT_SENSOR_DARK < BRIGHT",
     val("LIGHT_SENSOR_DARK_VAL") < val("LIGHT_SENSOR_BRIGHT_VAL")),
    ("MIN_SATELLITES <= OPTIMAL_SATELLITES",
     val("MIN_SATELLITES") <= val("OPTIMAL_SATELLITES")),
    ("CPU_THROTTLE_TEMP_WARN <= CRIT",
     val("CPU_THROTTLE_TEMP_WARN") <= val("CPU_THROTTLE_TEMP_CRIT")),
]
for label, ok in pairs:
    if not ok:
        errors.append("cross-field rule failed: " + label)

BAUDS = {1200, 2400, 4800, 9600, 19200, 38400, 57600, 74400, 115200, 230400,
         460800, 921600}
if int(val("GPS_BAUD")) not in BAUDS:
    errors.append("GPS_BAUD default %g is not a standard rate" % val("GPS_BAUD"))
if int(val("MANUAL_CPU_FREQ")) not in {80, 160, 240}:
    errors.append("MANUAL_CPU_FREQ default %g is not a supported clock"
                  % val("MANUAL_CPU_FREQ"))

if errors:
    print("\nFAIL")
    for e in errors:
        print(" -", e)
    sys.exit(1)
print("\nOK: every numeric parameter is band-checked, defaults are in band, "
      "cross-field rules hold")
