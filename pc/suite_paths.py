"""Paths for the portable export. Import performs no filesystem/network actions."""
import os
from pathlib import Path


def private_root():
    value = os.environ.get('STATION_PRIVATE_DIR')
    if value:
        return Path(value).expanduser().absolute()
    local = os.environ.get('LOCALAPPDATA')
    return (Path(local) / 'StationSuite') if local else (Path.home() / '.station-suite')


def required_env(name):
    value = os.environ.get(name)
    if not value:
        raise RuntimeError('Missing required deployment setting: ' + name)
    return value


def default_adb():
    return Path(os.environ.get('STATION_ADB', 'adb.exe'))
