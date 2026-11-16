"""Check or restore one explicitly pinned local Station recovery profile. No network setup or unlock."""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import secrets
import shlex
import subprocess
import sys
from types import SimpleNamespace
import xml.etree.ElementTree as ET

import install
from private_backup import restore_device_files

ROOT = Path(__file__).resolve().parents[1]
FINGERPRINT = 'Yandex/yandexstation_2/yandexstation_2:9/PPR1.180610.011/20221212:user/release-keys'
VOICE = install.PACKAGES['voicebridge']
PRIVATE_TARGETS = {'files/config.json', 'files/native-sync.json', 'files/music-config.json',
    'files/display-settings.json', 'files/native-tls.p12', 'files/native-tls-password.txt',
    'shared_prefs/voice_runtime.xml'}
REQUIRED_PRIVATE = PRIVATE_TARGETS - {'files/display-settings.json', 'shared_prefs/voice_runtime.xml'}
SHA = re.compile(r'[0-9a-f]{64}\Z')
PACKAGE = re.compile(r'[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+\Z')
LAUNCHER = 'com.gathin.tvhome'
LAUNCHER_ROOT = '/sdcard/Android/data/' + LAUNCHER + '/files'
LAUNCHER_ROOT_FILES = {'config.json', 'CREDITS.md', 'LICENSE', 'theme-package-manifest.json'}


def fail(code): raise ValueError(code)


def exact(value, required, optional=()):
    if not isinstance(value, dict) or set(value) - set(required) - set(optional) or set(required) - set(value):
        fail('INVALID_PROFILE_SCHEMA')


def no_duplicates(pairs):
    result = {}
    for key, value in pairs:
        if key in result: fail('DUPLICATE_JSON_KEY')
        result[key] = value
    return result


def json_object(data):
    try: value = json.loads(data.decode('utf-8-sig'), object_pairs_hook=no_duplicates,
                            parse_constant=lambda _: fail('INVALID_LOCAL_JSON'))
    except Exception: fail('INVALID_LOCAL_JSON')
    if not isinstance(value, dict): fail('INVALID_LOCAL_JSON')
    return value


def local_path(value, *, private=False, directory=False):
    if not isinstance(value, str) or not value or '://' in value or value.startswith(('\\\\', '//')):
        fail('LOCAL_FILE_REQUIRED')
    path = Path(value).expanduser()
    if not path.is_absolute(): fail('ABSOLUTE_LOCAL_PATH_REQUIRED')
    path = path.resolve()
    if private and (path == ROOT or ROOT in path.parents): fail('PRIVATE_INPUT_INSIDE_CHECKOUT')
    if not (path.is_dir() if directory else path.is_file()): fail('LOCAL_INPUT_MISSING')
    return path


def digest(path):
    value = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''): value.update(block)
    return value.hexdigest()


def pinned_file(path, pin):
    if not isinstance(pin, str) or not SHA.fullmatch(pin): fail('SHA256_PIN_REQUIRED')
    if digest(path) != pin: fail('LOCAL_FILE_HASH_MISMATCH')


def bounded(path, limit=16 * 1024 * 1024):
    with path.open('rb') as stream: data = stream.read(limit + 1)
    if len(data) > limit: fail('LOCAL_INPUT_TOO_LARGE')
    return data


def apk_identity(aapt2, path):
    process = subprocess.run([str(aapt2), 'dump', 'badging', str(path)], capture_output=True, timeout=30,
        creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
    if process.returncode: fail('APK_INSPECTION_FAILED')
    line = next((line for line in process.stdout.decode('utf-8', errors='replace').splitlines()
                 if line.startswith('package: ')), '')
    fields = dict(re.findall(r"([A-Za-z]+)='([^']*)'", line))
    try: return fields['name'], int(fields['versionCode']), fields.get('split', '')
    except Exception: fail('APK_IDENTITY_MISSING')


def validate_private(entries):
    bodies = {}
    for item in entries:
        exact(item, ('target', 'source', 'sha256'))
        target = item['target']
        if target not in PRIVATE_TARGETS or target in bodies: fail('PRIVATE_TARGET_NOT_ALLOWED')
        source = local_path(item['source'], private=True)
        pinned_file(source, item['sha256'])
        bodies[target] = bounded(source)
        if hashlib.sha256(bodies[target]).hexdigest() != item['sha256']: fail('LOCAL_FILE_HASH_MISMATCH')
    if REQUIRED_PRIVATE - set(bodies): fail('REQUIRED_PRIVATE_FILE_MISSING')
    for target, data in bodies.items():
        if target.endswith('.json'): json_object(data)
    config = json_object(bodies['files/config.json'])
    if type(config.get('lab_proxy_port')) is not int or config['lab_proxy_port'] != 18443:
        fail('RECOVERY_REQUIRES_PINNED_PC_TUNNEL')
    for field in ('gigachat_authorization_key', 'speechify_api_key'):
        value = config.get(field)
        if not isinstance(value, str) or not 1 <= len(value) <= 4096 or any(ord(c) < 33 or ord(c) > 126 for c in value): fail('VOICE_CREDENTIALS_INVALID')
    music = json_object(bodies['files/music-config.json'])
    if music.get('transport', 'tunnel') != 'tunnel':
        fail('DIRECT_WIFI_NOT_SUPPORTED')
    token = music.get('vk', {}).get('access_token') if isinstance(music.get('vk'), dict) else None
    hosts = music.get('audioHosts')
    if type(music.get('version')) is not int or music['version'] != 1 or not isinstance(token, str) or not 1 <= len(token) <= 4096 or not isinstance(hosts, list) or len(hosts) > 64:
        fail('MUSIC_CONFIG_INVALID')
    if any(not isinstance(host, str) or len(host) > 253 or not re.fullmatch(r'[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+', host) or not host.endswith(('.vkuseraudio.net', '.muzofond.fm')) for host in hosts):
        fail('MUSIC_CONFIG_INVALID')
    sync = json_object(bodies['files/native-sync.json'])
    system = sync.get('config', {}).get('system_config', {}) if isinstance(sync.get('config'), dict) else {}
    if sync.get('status') != 'ok' or not isinstance(system, dict): fail('NATIVE_SYNC_INVALID')
    if not all(isinstance(sync['config'].get(name), dict) for name in ('account_config', 'device_config')) or system.get('uniProxyUrl') != 'ws://127.0.0.1:17682/uni.ws':
        fail('NATIVE_SYNC_INVALID')
    if any(name in sync and sync[name] is not False for name in ('hasUpdate', 'hasCriticalUpdate')): fail('NATIVE_SYNC_INVALID')
    adbd = system.get('adbd', {})
    if not isinstance(adbd, dict) or adbd.get('remoteActivation') is not True or adbd.get('network') is not False or adbd.get('forceDisable', False) is not False:
        fail('NATIVE_SYNC_ADB_FLAGS_INVALID')
    if 'shared_prefs/voice_runtime.xml' in bodies:
        try: root = ET.fromstring(bodies['shared_prefs/voice_runtime.xml'])
        except Exception: fail('RUNTIME_PREFERENCES_INVALID')
        if root.tag != 'map': fail('RUNTIME_PREFERENCES_INVALID')
    try:
        from cryptography.hazmat.primitives.serialization import pkcs12
        password = bodies['files/native-tls-password.txt']
        if not 16 <= len(password) <= 128 or any(c < 33 or c > 126 for c in password): fail('TLS_MATERIAL_INVALID')
        key, certificate, _ = pkcs12.load_key_and_certificates(bodies['files/native-tls.p12'], bodies['files/native-tls-password.txt'])
        if key is None or certificate is None: fail('TLS_MATERIAL_INVALID')
    except Exception: fail('TLS_MATERIAL_INVALID')
    return bodies


def validate_launcher(entries, apps):
    if entries is None: return []
    if not isinstance(entries, list) or not 1 <= len(entries) <= 2048: fail('INVALID_LAUNCHER_FILE_SET')
    if not any(role == 'launcher' and package == LAUNCHER for role, package, _ in apps): fail('PINNED_LAUNCHER_REQUIRED')
    files, targets, total = [], set(), 0
    for item in entries:
        exact(item, ('target', 'source', 'sha256'))
        target = item['target']
        if not isinstance(target, str) or '\\' in target or not target or target.startswith('/'):
            fail('LAUNCHER_TARGET_NOT_ALLOWED')
        parts = target.split('/')
        if any(part in ('', '.', '..') or not re.fullmatch(r'[A-Za-z0-9_.-]+', part) for part in parts): fail('LAUNCHER_TARGET_NOT_ALLOWED')
        if target not in LAUNCHER_ROOT_FILES and (len(parts) < 2 or parts[0] != 'themes'): fail('LAUNCHER_TARGET_NOT_ALLOWED')
        if target in targets: fail('LAUNCHER_TARGET_NOT_ALLOWED')
        source = local_path(item['source']); pinned_file(source, item['sha256'])
        size = source.stat().st_size; total += size
        if size > 128 * 1024 * 1024 or total > 512 * 1024 * 1024: fail('LAUNCHER_ASSETS_TOO_LARGE')
        if target.endswith('.json'): json_object(bounded(source))
        files.append((source, target, item['sha256'])); targets.add(target)
    pointer = next((source for source, target, _ in files if target == 'themes/active'), None)
    if pointer:
        try: selected = bounded(pointer, 128).decode('ascii').splitlines()
        except Exception: fail('LAUNCHER_ACTIVE_PROFILE_INVALID')
        if len(selected) != 2 or not all(re.fullmatch(r'[A-Za-z0-9_-]+', part) for part in selected): fail('LAUNCHER_ACTIVE_PROFILE_INVALID')
        if 'themes/.profiles/' + selected[0] + '/config.json' not in targets or 'themes/' + selected[1] + '/config.json' not in targets:
            fail('LAUNCHER_ACTIVE_PROFILE_INCOMPLETE')
    return files


def load_plan(profile):
    path = local_path(str(profile), private=True)
    value = json_object(bounded(path, 1024 * 1024))
    exact(value, ('format', 'adb', 'aapt2', 'serial', 'firmware', 'apps', 'modelDir', 'privateFiles', 'startServices'), ('launcherFiles',))
    if type(value['format']) is not int or value['format'] != 1 or type(value['startServices']) is not bool:
        fail('INVALID_PROFILE_SCHEMA')
    firmware = value['firmware']; exact(firmware, ('fingerprint', 'sdk', 'abi'))
    if firmware != {'fingerprint': FINGERPRINT, 'sdk': 28, 'abi': 'arm64-v8a'}: fail('UNSUPPORTED_FIRMWARE_PIN')
    serial = value['serial']
    if not isinstance(serial, str) or not re.fullmatch(r'[A-Za-z0-9_.:\-]{1,128}', serial): fail('INVALID_SERIAL')
    tools = SimpleNamespace(adb=str(local_path(value['adb'])), station=serial)
    aapt2 = local_path(value['aapt2'])
    if not isinstance(value['apps'], list) or not 2 <= len(value['apps']) <= 32: fail('INVALID_APP_SET')
    apps, packages, roles = [], set(), []
    for item in value['apps']:
        exact(item, ('role', 'package', 'versionCode'), ('apk', 'sha256', 'apks'))
        role, package, version = item['role'], item['package'], item['versionCode']
        if role not in ('agent', 'voicebridge', 'launcher', 'tv') or not isinstance(package, str) or not PACKAGE.fullmatch(package) or package in packages:
            fail('INVALID_APP_SET')
        if package.startswith(('android.', 'com.android.', 'local.station.')) and package not in (install.PACKAGES['agent'], VOICE): fail('SYSTEM_OR_EXPERIMENTAL_PACKAGE_REJECTED')
        expected = {'agent': install.PACKAGES['agent'], 'voicebridge': VOICE, 'launcher': 'com.gathin.tvhome'}
        if role in expected and package != expected[role]: fail('APP_ROLE_PACKAGE_MISMATCH')
        if role == 'tv' and package in expected.values(): fail('APP_ROLE_PACKAGE_MISMATCH')
        if type(version) is not int or version <= 0: fail('APP_VERSION_PIN_REQUIRED')
        if 'apks' in item:
            if 'apk' in item or 'sha256' in item or not isinstance(item['apks'], list) or not 1 <= len(item['apks']) <= 16: fail('INVALID_APK_SET')
            sources = item['apks']
        else:
            if set(item) != {'role', 'package', 'versionCode', 'apk', 'sha256'}: fail('INVALID_APK_SET')
            sources = [{'path': item['apk'], 'sha256': item['sha256']}]
        files, splits = [], set()
        for entry in sources:
            exact(entry, ('path', 'sha256'))
            apk = local_path(entry['path']); pinned_file(apk, entry['sha256'])
            name, code, split = apk_identity(aapt2, apk)
            if name != package or code != version or split in splits: fail('APK_IDENTITY_PIN_MISMATCH')
            files.append(apk); splits.add(split)
        if '' not in splits or (role != 'tv' and len(files) != 1): fail('APK_BASE_REQUIRED')
        apps.append((role, package, files)); packages.add(package); roles.append(role)
    if roles.count('agent') != 1 or roles.count('voicebridge') != 1 or roles.count('launcher') > 1: fail('CORE_APPS_REQUIRED')
    directory = local_path(value['modelDir'], directory=True)
    models = []
    for entry in json_object((ROOT / 'config/sherpa-downloads.json').read_bytes())['files']:
        if not entry['path'].startswith('model/'): continue
        source = local_path(str(directory / Path(entry['path']).name))
        if source.stat().st_size != entry['bytes']: fail('MODEL_INTEGRITY_CHECK_FAILED')
        pinned_file(source, entry['sha256']); models.append((source, entry['sha256']))
    if not isinstance(value['privateFiles'], list) or not 1 <= len(value['privateFiles']) <= len(PRIVATE_TARGETS): fail('INVALID_PRIVATE_FILE_SET')
    private = validate_private(value['privateFiles'])
    launcher = validate_launcher(value.get('launcherFiles'), apps)
    return SimpleNamespace(tools=tools, firmware=firmware, apps=apps, models=models,
        private=private, launcher=launcher, start=value['startServices'])


def device_preflight(plan):
    for name, expected in [('ro.build.fingerprint', plan.firmware['fingerprint']),
                           ('ro.build.version.sdk', '28'), ('ro.product.cpu.abi', 'arm64-v8a')]:
        if install.shell(plan.tools, 'getprop', name).decode('utf-8').strip() != expected: fail('DEVICE_FIRMWARE_MISMATCH')
    if not install.shell(plan.tools, 'id').decode('utf-8').startswith('uid=2000(shell)'):
        fail('EXPECTED_ADB_SHELL_REQUIRED')
    if install.shell(plan.tools, 'getenforce').strip() != b'Enforcing': fail('SELINUX_ENFORCING_REQUIRED')
    system_packages = {line.removeprefix('package:') for line in
        install.shell(plan.tools, 'pm', 'list', 'packages', '-s').decode('utf-8').splitlines()
        if line.startswith('package:')}
    if any(package in system_packages for _, package, _ in plan.apps): fail('SYSTEM_PACKAGE_REPLACEMENT_REJECTED')


def restore_private(plan):
    """ASCII base64 stdin bypasses Windows binary EOF; secrets never touch shared storage or argv."""
    restore_device_files(SimpleNamespace(adb=plan.tools.adb, serial=plan.tools.station), plan.private)


def restore_launcher(plan):
    if not plan.launcher: return
    stage = LAUNCHER_ROOT + '/.station-recovery-' + secrets.token_hex(12)
    created = False
    install.shell(plan.tools, 'am', 'force-stop', LAUNCHER)
    try:
        install.shell(plan.tools, 'mkdir', '-p', LAUNCHER_ROOT)
        install.shell(plan.tools, 'mkdir', stage); created = True
        for index, (source, target, pin) in enumerate(plan.launcher):
            staged = stage + '/' + str(index)
            install.adb(plan.tools, plan.tools.station, 'push', str(source), staged)
            actual = install.shell(plan.tools, 'sha256sum', staged).split()
            if not actual or actual[0].decode('ascii') != pin: fail('LAUNCHER_REMOTE_HASH_MISMATCH')
        # Publish the active pointer last, after its complete selected profile.
        for index, (source, target, pin) in sorted(enumerate(plan.launcher), key=lambda row: row[1][1] == 'themes/active'):
            final = LAUNCHER_ROOT + '/' + target
            install.shell(plan.tools, 'mkdir', '-p', str(PurePosixPath(final).parent))
            install.shell(plan.tools, 'mv', stage + '/' + str(index), final)
    finally:
        if created:
            pending = sys.exc_info()[0] is not None; failed = False
            for command in [('rm', '-f', stage + '/' + str(i)) for i in range(len(plan.launcher))] + [('rmdir', stage)]:
                try: install.shell(plan.tools, *command)
                except (SystemExit, OSError, subprocess.SubprocessError): failed = True
            if failed:
                if not pending: fail('LAUNCHER_STAGE_CLEANUP_FAILED')
                print('Launcher temporary stage cleanup incomplete', file=sys.stderr)


def apply(plan):
    for index, (role, package, files) in enumerate(plan.apps, 1):
        print(json.dumps({'phase': 'install', 'package': package, 'index': index, 'total': len(plan.apps)}), flush=True)
        install.adb(plan.tools, plan.tools.station, 'install-multiple' if len(files) > 1 else 'install', '-r', *map(str, files))
        if role in ('agent', 'voicebridge'): install.shell(plan.tools, 'am', 'force-stop', package)
    install.shell(plan.tools, 'run-as', VOICE, 'pwd')
    print(json.dumps({'phase': 'model', 'files': len(plan.models)}), flush=True)
    install.deploy_model(plan.tools, plan.models)
    print(json.dumps({'phase': 'private', 'files': len(plan.private)}), flush=True)
    restore_private(plan)
    print(json.dumps({'phase': 'launcher', 'files': len(plan.launcher)}), flush=True)
    restore_launcher(plan)
    if plan.launcher:
        result = install.shell(plan.tools, 'cmd', 'package', 'set-home-activity', LAUNCHER + '/.MainActivity')
        if result.strip() != b'Success': fail('LAUNCHER_HOME_NOT_APPLIED')
    if plan.start:
        install.shell(plan.tools, 'am', 'broadcast', '-n', VOICE + '/.RuntimeReceiver', '--es', 'operation', 'local_services_start')
        install.shell(plan.tools, 'am', 'broadcast', '-n', VOICE + '/.RuntimeReceiver', '--es', 'operation', 'runtime_enable')
        install.shell(plan.tools, 'am', 'start', '-n', install.PACKAGES['agent'] + '/.MainActivity')
        if plan.launcher:
            install.shell(plan.tools, 'am', 'start', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME')


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--profile', type=Path, required=True)
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args(argv)
    try:
        plan = load_plan(args.profile)
        device_preflight(plan)
        if args.apply: apply(plan)
        print(json.dumps({'ok': True, 'mode': 'apply' if args.apply else 'preflight', 'deviceChanged': args.apply,
            'apps': len(plan.apps), 'modelFiles': len(plan.models), 'privateFiles': len(plan.private), 'launcherFiles': len(plan.launcher),
            'servicesStartRequested': args.apply and plan.start, 'pcTunnelStarted': False,
            'launcherHomeRequested': args.apply and bool(plan.launcher),
            'tlsTrustVerified': False, 'experimentalWifiIncluded': False}))
    except (Exception, SystemExit) as error:
        if isinstance(error, SystemExit) and error.code == 0: raise
        code = str(error) if isinstance(error, ValueError) and re.fullmatch(r'[A-Z0-9_]+', str(error)) else 'RECOVERY_FAILED'
        print(json.dumps({'ok': False, 'code': code})); return 1
    return 0


if __name__ == '__main__': raise SystemExit(main())
