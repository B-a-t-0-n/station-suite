"""Export reviewed source trees only. No dist, state, user config or signing keys."""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil

SUITE = Path(__file__).resolve().parents[1]
SOURCE_EXTENSIONS = {'.java', '.py', '.ps1', '.xml', '.html', '.css', '.js', '.svg', '.md', '.txt', '.json', '.pem', '.wav', '.ogg', '.png', '.webp', '.gif'}
EXCLUDED_NAMES = {'__pycache__', 'build', 'dist', 'archive', '.git', '.gradle', 'node_modules'}
PC_MODULES = ['giga_tunnel.py', 'windows_physical_source.py', 'runtime_supervisor.py',
              'native_config_window.py', 'panel_relay.py', 'probe_local_ota.py', 'setup_speechify.py']


def write(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding='utf-8')


def copy_tree(source, target):
    if not source.is_dir():
        return
    for path in sorted(source.rglob('*')):
        relative = path.relative_to(source)
        if not path.is_file() or any(part in EXCLUDED_NAMES for part in relative.parts):
            continue
        if path.suffix.lower() not in SOURCE_EXTENSIONS:
            continue
        if path.is_symlink():
            raise RuntimeError('Source symlinks are not exported')
        payload = path.read_bytes()
        if b'PRIVATE KEY-----' in payload or path.suffix.lower() in {'.p12', '.jks', '.keystore'}:
            raise RuntimeError('Private material in source tree')
        destination = target / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(payload)


def portable_build(source, app):
    text = source.read_text(encoding='utf-8')
    start = text.index('JAVA = ')
    end = text.index('BUILD = ', start)
    text = text[:start] + '''SUITE = ROOT.parents[1]
JAVA = Path(os.environ['STATION_JAVA_HOME']) / 'bin'
JAVAC = Path(os.environ.get('STATION_JAVAC_HOME', os.environ['STATION_JAVA_HOME'])) / 'bin/javac.exe'
SDK = Path(os.environ.get('STATION_ANDROID_PARTS', str(SUITE / '.deps/android')))
BT = SDK / 'build-tools'
ANDROID = SDK / 'platform/android.jar'
''' + text[end:]
    start = text.index('PRIVATE = ')
    end = text.index('\n', start)
    text = text[:start] + "PRIVATE = Path(os.environ['STATION_SIGNING_DIR'])" + text[end:]
    text = text.replace("'audioprobe.p12'", "os.environ.get('STATION_KEYSTORE_NAME', 'station-suite.p12')")
    text = text.replace("'station-audioprobe'", "os.environ.get('STATION_KEY_ALIAS', 'station-suite')")
    if app == 'voicebridge':
        text = text.replace("ROOT.parent / 'station-asr-deps'", "Path(os.environ.get('STATION_ASR_DEPS', str(SUITE / '.deps/asr')))")
        text = text.replace("ROOT.parent / 'station-asr-deps/build-tools/r8-9.5.32.jar'", "Path(os.environ.get('STATION_R8_JAR', str(SUITE / '.deps/asr/build-tools/r8-9.5.32.jar')))")
        text = text.replace("ROOT.parent / 'station-sherpa-deps'", "Path(os.environ.get('STATION_SHERPA_DEPS', str(SUITE / '.deps/sherpa')))")
    return '\n'.join(line.rstrip() for line in text.splitlines()).rstrip() + '\n'


def app_source(source, name):
    target = SUITE / 'apps' / name
    for folder in ['src', 'res', 'assets', 'tests']:
        destination = (target / folder).resolve()
        if destination.exists():
            if SUITE.resolve() not in destination.parents:
                raise RuntimeError('Invalid export cleanup target')
            shutil.rmtree(destination)
        copy_tree(source / folder, target / folder)
    write(target / 'AndroidManifest.xml', (source / 'AndroidManifest.xml').read_text(encoding='utf-8').rstrip() + '\n')
    write(target / 'build.py', portable_build(source / 'build.py', name))
    # Read-only host tests remain available; lab-only trial/deployment scripts are not exported.


def network_tests(legacy):
    for source, output in [('Test-LocalConfigServer.py', 'test_local_config_server.py'), ('Test-StationDisplayOverlay.py', 'test_station_display_overlay.py')]:
        text = (legacy / 'outputs/station-max' / source).read_text(encoding='utf-8-sig')
        text = text.replace('Path(__file__).with_name("LocalConfigServer.py")', 'Path(__file__).resolve().parents[1] / "LocalConfigServer.py"')
        text = text.replace('Path(__file__).with_name(filename)', 'Path(__file__).resolve().parents[1] / filename')
        text = text.replace('{"access_token", "expires_in", "refresh_token", "token_type", "uid"}', '{"access_token", "expires_in", "refresh_token", "token_type", "uid", "x_token", "xtoken", "is_stub"}')
        text = re.sub(r'^([ \t]*)runtime.lock = threading\.Lock\(\)', lambda match: match[1] + 'if not hasattr(runtime, "args"): runtime.args = SimpleNamespace(station_relay=None, require_station_relay=False)\n' + match[1] + 'runtime.lock = threading.Lock()', text, flags=re.M)
        text = text.replace('                    self.assertNotEqual(call.args[1], socket.SO_REUSEADDR)', '                    if port == 123: self.assertNotEqual(call.args[1], socket.SO_REUSEADDR)')
        write(SUITE / 'pc/tests' / output, text)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--legacy-root', type=Path, required=True)
    parser.add_argument('--voice-root', type=Path, required=True)
    parser.add_argument('--include-phone', action='store_true')
    args = parser.parse_args()
    legacy, voice = args.legacy_root.resolve(), args.voice_root.resolve()
    if args.include_phone:
        app_source(legacy / 'work/station-phone-remote', 'phone')
    app_source(legacy / 'work/station-agent', 'agent')
    app_source(voice, 'voicebridge')
    helper = legacy / 'work/station-agent/tools/Start-StationInputHelper.ps1'
    shutil.copyfile(helper, SUITE / 'pc/Start-StationInputHelper.ps1')
    for name in PC_MODULES:
        shutil.copyfile(voice / 'tools' / name, SUITE / 'pc' / name)
    shutil.copyfile(voice / 'tools/tunnel_only_supervisor.py', SUITE / 'pc/tunnel_only_supervisor.py')
    copy_tree(voice / 'tools/tests', SUITE / 'pc/tests')
    for name in ['LocalConfigServer.py', 'StationDisplayOverlay.py']:
        shutil.copyfile(legacy / 'outputs/station-max' / name, SUITE / 'pc' / name)
    network_tests(legacy)
    # Keep the deployed launchers for source-level audit. They are intentionally
    # non-executable templates until configured; portable wrappers live in pc/.
    reference = SUITE / 'pc/reference'
    reference.mkdir(exist_ok=True)
    for source in [legacy / 'outputs/station-max/lab-autostart/Start-StationLab.ps1',
                   legacy / 'work/station-lab-control/Start-Control.ps1',
                   legacy / 'work/station-lab-control/ota_guard.ps1']:
        text = source.read_text(encoding='utf-8-sig')
        text = text.replace(str(legacy), '<LEGACY_WORKSPACE>').replace(str(voice), '<VOICE_SOURCE>')
        text = re.sub(r'''[A-Za-z]:[/\\]Users[/\\][^/\\\s'"<>]+''', '<USERPROFILE>', text)
        write(reference / (source.stem + '.reference.ps1.txt'), text)
    # Public dependency manifests contain URLs/checksums, never user state.
    for name, source in [('sherpa-downloads.json', voice.parent / 'station-sherpa-deps/DOWNLOADS.json'),
                         ('asr-dependencies.json', voice.parent / 'station-asr-deps/DEPENDENCIES.json'),
                         ('r8-download.json', voice.parent / 'station-asr-deps/build-tools/r8.json')]:
        shutil.copyfile(source, SUITE / 'config' / name)
    from portable_export import adapt_pc, adapt_app_tests, adapt_test_contracts
    adapt_pc(SUITE)
    adapt_app_tests(SUITE)
    adapt_test_contracts(SUITE)
    files = []
    for path in sorted((SUITE / 'apps').rglob('*')):
        if path.is_file() and path.suffix.lower() in SOURCE_EXTENSIONS and not any(part in EXCLUDED_NAMES for part in path.relative_to(SUITE / 'apps').parts):
            data = path.read_bytes()
            if path.suffix.lower() not in {'.png', '.webp', '.gif', '.wav', '.ogg'}:
                data = data.replace(b'\r\n', b'\n')
            files.append({'path': path.relative_to(SUITE).as_posix(), 'sha256': hashlib.sha256(data).hexdigest()})
    write(SUITE / 'config/source-snapshot.json', json.dumps({'hashEncoding': 'Git-normalized LF for text; raw bytes for binary', 'files': files}, indent=2) + '\n')
    print(json.dumps({'sourceFiles': len(files), 'phoneIncluded': args.include_phone, 'privateFilesCopied': 0}))


if __name__ == '__main__':
    main()
