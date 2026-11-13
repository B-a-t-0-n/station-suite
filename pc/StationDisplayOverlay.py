"""Strict, optional LED-only overlay for the locally supplied sync response.

The supervisor-owned sync file is never modified. A missing, malformed, expired,
or incompatible overlay leaves the caller's already loaded payload untouched.
"""
from __future__ import annotations

import copy
import json
import math
from pathlib import Path
import time


MAX_OVERLAY_BYTES = 1024
CLOCK_SIZES = frozenset(("small", "middle", "large"))
MUSIC_STYLES = frozenset(("clock", "wave", "pads", "bricks", "fluid", "random"))
# Exact 20221212 factory constructors have their own safe defaults. No visual tuning.
MUSIC_PRESETS = {name: {"type": name} for name in ("wave", "pads", "bricks", "fluid")}
# RandomSpectrogram parses string IDs and converts interval seconds to ns.
MUSIC_PRESETS["random"] = {"type": "random", "presets": ["wave", "pads", "bricks", "fluid"], "interval": 30}
MAX_EXPIRES_AT_MS = (1 << 63) - 1


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate member")
        result[key] = value
    return result


def _reject_constant(value):
    raise ValueError("nonfinite number")


def validate_document(value):
    """Return a detached, validated v1 document; reject all other settings."""
    if type(value) is not dict or set(value) != {"version", "expiresAtMs", "settings"}:
        raise ValueError("invalid overlay envelope")
    if type(value["version"]) is not int or value["version"] != 1:
        raise ValueError("invalid overlay version")
    expiry = value["expiresAtMs"]
    if type(expiry) is not int or not 0 <= expiry <= MAX_EXPIRES_AT_MS:
        raise ValueError("invalid overlay expiry")
    settings = value["settings"]
    if type(settings) is not dict or not settings or not set(settings) <= {"clockSize", "brightness", "musicStyle"}:
        raise ValueError("invalid overlay settings")
    if "musicStyle" in settings:
        style = settings["musicStyle"]
        if type(style) is not str or style not in MUSIC_STYLES:
            raise ValueError("invalid music style")
    if "clockSize" in settings:
        size = settings["clockSize"]
        if type(size) is not str or size not in CLOCK_SIZES:
            raise ValueError("invalid clock size")
    if "brightness" in settings:
        brightness = settings["brightness"]
        if type(brightness) is not dict or set(brightness) != {"auto", "value"}:
            raise ValueError("invalid brightness")
        level = brightness["value"]
        if type(brightness["auto"]) is not bool or type(level) not in (int, float):
            raise ValueError("invalid brightness types")
        if not math.isfinite(level) or not 0 <= level <= 1:
            raise ValueError("invalid brightness range")
    return copy.deepcopy(value)


def decode_document(data):
    """Decode bounded UTF-8 JSON without duplicate or nonfinite values."""
    if len(data) > MAX_OVERLAY_BYTES:
        raise ValueError("overlay too large")
    value = json.loads(data.decode("utf-8-sig"), object_pairs_hook=_unique_object,
                       parse_constant=_reject_constant)
    return validate_document(value)


def load_overlay(path: Path | None, now_ms=None):
    """Return (validated document, fixed result), without logging file contents."""
    if path is None:
        return None, "disabled"
    try:
        with path.open("rb") as source:
            document = decode_document(source.read(MAX_OVERLAY_BYTES + 1))
        current = time.time_ns() // 1_000_000 if now_ms is None else now_ms
        if document["expiresAtMs"] and document["expiresAtMs"] <= current:
            return None, "expired"
        return document, "loaded"
    except FileNotFoundError:
        return None, "missing"
    except (OSError, ValueError, UnicodeError, RecursionError, OverflowError):
        return None, "invalid"


def apply_overlay(payload, path: Path | None, now_ms=None):
    """Merge only LED leaves, returning (payload, fixed result).

    On every rejected overlay the exact original payload object is returned.
    Existing siblings, including voice endpoints and OTA fields, are preserved.
    """
    document, result = load_overlay(path, now_ms)
    if document is None:
        return payload, result
    if type(payload) is not dict or type(payload.get("config")) is not dict:
        return payload, "invalid"
    config = payload["config"]
    device = config.get("device_config", {})
    if type(device) is not dict:
        return payload, "invalid"
    led = device.get("led", {})
    if type(led) is not dict:
        return payload, "invalid"
    settings = document["settings"]
    if "clockSize" in settings and type(led.get("time_visualization", {})) is not dict:
        return payload, "invalid"
    if "brightness" in settings and type(led.get("brightness", {})) is not dict:
        return payload, "invalid"
    if "musicStyle" in settings:
        system = config.get("system_config", {})
        if type(system) is not dict or type(system.get("spectrogramAnimation", {})) is not dict:
            return payload, "invalid"
        animation = system.get("spectrogramAnimation", {})
        if type(animation.get("presets", {})) is not dict:
            return payload, "invalid"
        if type(led.get("music_equalizer_visualization", {})) is not dict:
            return payload, "invalid"
    updated = copy.deepcopy(payload)
    target = updated["config"].setdefault("device_config", {}).setdefault("led", {})
    if "clockSize" in settings:
        target.setdefault("time_visualization", {})["size"] = settings["clockSize"]
    if "brightness" in settings:
        target.setdefault("brightness", {}).update(settings["brightness"])
    if "musicStyle" in settings:
        style = settings["musicStyle"]
        target.setdefault("music_equalizer_visualization", {}).update({"auto": style == "random", "style": style})
        animation = updated["config"].setdefault("system_config", {}).setdefault("spectrogramAnimation", {})
        animation["enabled"] = style != "clock"
        # Preserve unrelated catalog IDs and all unrelated system/device settings.
        animation.setdefault("presets", {}).update(copy.deepcopy(MUSIC_PRESETS))
    return updated, "applied"
