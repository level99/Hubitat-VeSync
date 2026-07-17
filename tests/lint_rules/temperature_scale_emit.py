"""
temperature_scale_emit.py — RULE56: every `temperature` attribute emit must go through the
shared emitTemperature() helper in LevoitChildBaseLib, never an inline hardcoded-unit
sendEvent.

THE BUG CLASS — a driver emits the `temperature` attribute inline, hardcoding the unit as
Fahrenheit (`unit:"°F"`) without converting the VeSync "F × 10" reading to the hub's
configured scale. On a °C hub the tile then shows a Fahrenheit number labelled °C (the C5
bug). Four drivers carried the identical inline block; each was an independent chance to get
the scale wrong.

THE FIX — one sanctioned emitter, `emitTemperature(rawTempTimesTen)` in LevoitChildBaseLib,
does the F×10 → hub-scale conversion and sendEvent's with the matching unit. Every
TemperatureMeasurement driver calls it; no driver emits `temperature` inline. This rule is
the closed mechanism that keeps the class from re-appearing: any inline temperature emit
with a hardcoded °F unit under Drivers/Levoit/ fails ``lint --strict``.

Scope:
  - Only ``.groovy`` files under ``Drivers/Levoit/``.
  - LevoitChildBaseLib.groovy is exempt by construction — it DEFINES emitTemperature(), the
    single sanctioned temperature sendEvent. It is the one file allowed to carry the emit.

Detection — a single (comment-stripped) line that BOTH:
  (i)  emits the temperature attribute: ``sendEvent(name:"temperature"`` (any quote/space), and
  (ii) hardcodes a Fahrenheit unit literal: ``unit:"°F"`` / ``unit: "°F"`` (the degree sign as
       a literal glyph OR a ``\\u00B0`` escape).
Keying on the °F branch flags an inline block exactly once (an inline block always has both a
°C and a °F branch), and an ``emitTemperature(raw)`` call carries no ``sendEvent(name:
"temperature"`` so it never matches.
"""

import re
from lint_rules._helpers import make_finding

DRIVER_DIR_FRAGMENT = "Drivers/Levoit/"

# The file that DEFINES the sanctioned emitTemperature() helper — the only place a
# temperature sendEvent may live. Exempt by construction (not a stale config exemption).
SANCTIONED_EMITTER_FILE = "LevoitChildBaseLib.groovy"

# A temperature attribute emit: sendEvent(name:"temperature" ... (allow name/value spacing
# and either quote style).
TEMP_EMIT_RE = re.compile(r'sendEvent\s*\(\s*name\s*:\s*["\']temperature["\']')

# A hardcoded Fahrenheit unit literal: unit:"°F" / unit: "°F" / unit:'°F', with the degree
# sign as a literal glyph OR a ° escape (so a future author who writes the escape form
# is still caught).
FAHRENHEIT_UNIT_RE = re.compile(r'unit\s*:\s*["\']\s*(?:°|\\u00[bB]0)\s*F\s*["\']')


def check_rule56_temperature_scale_emit(
    path, raw_lines, cleaned_lines, raw_text, config, rel_base
):
    """
    RULE56: flag an inline `temperature` sendEvent with a hardcoded °F unit in
    Drivers/Levoit/*.groovy — direct the author to the shared emitTemperature() helper.
    """
    findings = []

    if path.suffix != '.groovy':
        return findings

    path_str = str(path).replace('\\', '/')
    if DRIVER_DIR_FRAGMENT not in path_str:
        return findings

    # The helper's home file is the one sanctioned temperature emitter — exempt.
    if path.name == SANCTIONED_EMITTER_FILE:
        return findings

    file_rel = str(path.relative_to(rel_base)).replace('\\', '/')

    for idx, line_clean in enumerate(cleaned_lines):
        if TEMP_EMIT_RE.search(line_clean) and FAHRENHEIT_UNIT_RE.search(line_clean):
            findings.append(make_finding(
                severity='FAIL',
                rule_id='RULE56_temperature_scale_emit',
                title=(
                    'Inline temperature sendEvent with hardcoded °F unit — use the shared '
                    'emitTemperature() helper instead'
                ),
                file_rel=file_rel,
                lineno=idx + 1,
                raw_lines=raw_lines,
                why=(
                    'The VeSync API reports temperature as "F × 10"; it must be converted to '
                    'the hub\'s configured scale (location.temperatureScale) before emission. '
                    'An inline sendEvent that hardcodes unit:"°F" is the C5 hardcoded-scale bug '
                    'class — on a °C hub it shows a Fahrenheit number labelled °C. Four drivers '
                    'carried the identical inline block; the single emitTemperature() helper is '
                    'now the only sanctioned temperature emitter.'
                ),
                fix=(
                    'Replace the inline `double tempF = raw/10.0; if (temperatureScale=="C") '
                    '{...°C...} else {...°F...}` block with `emitTemperature(<raw F×10>)` from '
                    'LevoitChildBaseLib, keeping your existing null/zero presence guard around '
                    'the call.'
                ),
            ))

    return findings


ALL_RULES = [check_rule56_temperature_scale_emit]
