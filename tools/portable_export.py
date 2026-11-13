"""Deterministic adaptations applied only to exported PC modules."""
from pathlib import Path
import re


def adapt_pc(root):
    pc = root / 'pc'
    path = pc / 'runtime_supervisor.py'
    text = path.read_text(encoding='utf-8')
    start = text.index("BASELINE_SHA = ")
    end = text.index("MAX_LEASE, ", start)
    text = text[:start] + '''from suite_paths import private_root, required_env, default_adb
BASELINE_SHA = os.environ.get('STATION_BASELINE_SHA256', '0' * 64)
POLICY_SHA = os.environ.get('STATION_POLICY_SHA256', '0' * 64)
POLICY = private_root() / 'pc/config-policy.json'
ADB = default_adb()
SERIAL, PACKAGE, PORT, INTERFACE = os.environ.get('STATION_SERIAL', '192.168.77.100:5555'), 'local.station.voicebridge', 18443, int(os.environ.get('STATION_WAN_INTERFACE', '0'))
PC_SCRIPT = str(Path(__file__).with_name('retired_pc_voice_server.py'))
''' + text[end:]
    text = text.replace('WindowsPhysicalSource(interface_index=11)', 'WindowsPhysicalSource(interface_index=INTERFACE)')
    text = re.sub(r"^PC_SCRIPT = ['\"]C:[^\n]+\n", '', text, flags=re.M)
    text = text.replace("return Path(os.environ['USERPROFILE']) / '.stationlab/runtime-supervisor'", "return private_root() / 'runtime-supervisor'")
    # This exported module is imported for shared primitives only; no legacy run path.
    text = text.replace("if __name__ == '__main__':\n    main()", "if __name__ == '__main__':\n    if len(sys.argv) > 1 and sys.argv[1] in {'guard', 'status', 'stop'}: main()\n    else: raise SystemExit('Use pc/tunnel_only_supervisor.py; legacy run is not supported.')")
    path.write_text(text, encoding='utf-8')
    path = pc / 'windows_physical_source.py'
    text = path.read_text(encoding='utf-8')
    text = text.replace('INTERFACE = 10', "INTERFACE = int(os.environ.get('STATION_WAN_INTERFACE', '0'))")
    text = text.replace("NETWORK = ipaddress.IPv4Network('10.131.146.0/24')", "NETWORK = ipaddress.IPv4Network(os.environ.get('STATION_WAN_NETWORK', '192.0.2.0/24'))")
    path.write_text(text, encoding='utf-8')
    path = pc / 'tunnel_only_supervisor.py'
    text = path.read_text(encoding='utf-8').replace('from panel_relay import FixedPanelRelay', 'from panel_relay import FixedPanelRelay\nfrom suite_paths import private_root')
    text = text.replace("return Path(os.environ['USERPROFILE']) / '.stationlab/tunnel-only-supervisor'", "return private_root() / 'tunnel-only-supervisor'")
    text = text.replace("return Path(os.environ['USERPROFILE']) / 'AppData/Local/StationLab/stand/local-config-relay.json'", "return private_root() / 'state/local-config-relay.json'")
    text = text.replace("return Path(os.environ['USERPROFILE']) / 'AppData/Local/Temp/opencode/station-adb-sync/02-sync-glagol-plus-voice.json'", "return private_root() / 'pc/native-baseline.json'")
    path.write_text(text, encoding='utf-8')
    path = pc / 'native_config_window.py'
    text = path.read_text(encoding='utf-8').replace('from setup_speechify import _current_user_sid, _restrict_file', 'from setup_speechify import _current_user_sid, _restrict_file\nfrom suite_paths import private_root')
    old = "local = Path(os.environ['LOCALAPPDATA'])\n    return (local / 'Temp/opencode/station-adb-sync/02-sync-glagol-plus-voice.json',\n            local / 'StationLab/native-config-windows')"
    new = "return (private_root() / 'pc/native-baseline.json', private_root() / 'native-config-windows')"
    if old not in text:
        raise RuntimeError('Unexpected native path block')
    path.write_text(text.replace(old, new), encoding='utf-8')
    path = pc / 'probe_local_ota.py'
    text = path.read_text(encoding='utf-8').replace('import json\n', 'import json\nimport os\n')
    start = text.index("CERT_SHA256 = ")
    end = text.index('\n', start)
    text = text[:start] + "CERT_SHA256 = os.environ.get('STATION_TLS_LEAF_SHA256', '0' * 64)" + text[end:]
    path.write_text(text, encoding='utf-8')
    path = pc / 'LocalConfigServer.py'
    text = path.read_text(encoding='utf-8')
    for key, default, variable in [('BIND_IP', '192.168.77.1', 'STATION_LAB_IP'), ('VOICE_IP', '192.168.77.2', 'STATION_VOICE_IP'), ('CLIENT_IP', '192.168.77.100', 'STATION_IP')]:
        text = text.replace(f'{key} = "{default}"', f'{key} = os.environ.get("{variable}", "{default}")')
    text = text.replace('    if name in ("quasar.yandex.net", "startup.mobile.yandex.net", "clck.yandex.net"):\n        metadata["path_raw"] = path.split("?", 1)[0]\n', '')
    path.write_text(text, encoding='utf-8')
    # Production tests only; retired PC voice endpoints/trial controllers are not
    # part of this package. Keep Java fakes, not captured device/API traffic.
    omitted = {'test_input_stop_transport.py', 'test_legacy_pipeline_handler.py',
        'test_legacy_transport_integration.py', 'test_native_trial_cleanup.py',
        'test_runtime_transport.py', 'test_server_endpoint_smoke.py',
        'test_server_endpoint_transport.py', 'test_native_ogg_input.py'}
    for path in (pc / 'tests').rglob('*.py'):
        if path.name in omitted:
            path.unlink(); continue
        text = path.read_text(encoding='utf-8')
        if '\nimport os\n' not in text:
            marker = 'from pathlib import Path\n'
            text = text.replace(marker, marker + 'import os\n')
        text = text.replace('ROOT = Path(__file__).resolve().parents[2]', "ROOT = Path(__file__).resolve().parents[2] / 'apps/voicebridge'")
        text = text.replace("PROJECT = HERE.parents[2]", "PROJECT = HERE.parents[3] / 'apps/voicebridge'")
        for name in ['JAVAC', 'JAVA', 'JDK', 'SDK', 'ANDROID', 'PC_CONTRACT']:
            if name == 'JAVAC': value = "Path(os.environ.get('STATION_JAVAC_HOME', os.environ.get('STATION_JAVA_HOME', ''))) / 'bin/javac.exe'"
            elif name == 'JAVA':
                value = "Path(os.environ.get('STATION_JAVA_HOME', '')) / 'bin'"
                if re.search(r'^JAVA = .*java.exe', text, re.M): value += " / 'java.exe'"
            elif name == 'JDK': value = "Path(os.environ.get('STATION_JAVAC_HOME', os.environ.get('STATION_JAVA_HOME', ''))) / 'bin'"
            elif name == 'SDK': value = "Path(os.environ.get('STATION_ANDROID_PARTS', str(PROJECT.parents[1] / '.deps/android')))"
            elif name == 'ANDROID': value = "Path(os.environ.get('STATION_ANDROID_PARTS', str(ROOT.parents[1] / '.deps/android'))) / 'platform/android.jar'"
            else: value = "ROOT.parents[1] / 'pc/LocalConfigServer.py'"
            text = re.sub('^' + name + r' = .*C:[^\n]*', name + ' = ' + value, text, flags=re.M)
        text = text.replace("ROOT / 'tools/tests/", "Path(__file__).resolve().parent / '")
        text = text.replace('ROOT / "tools/tests/', 'Path(__file__).resolve().parent / "')
        text = text.replace("PROJECT / 'tools/tests/", "HERE.parent / '")
        text = text.replace("PROJECT.parent / 'station-asr-deps'", "Path(os.environ.get('STATION_ASR_DEPS', str(PROJECT.parents[1] / '.deps/asr')))")
        text = text.replace("PROJECT.parent / 'station-sherpa-deps/downloads/sherpa-onnx-jvm-2.0.1.jar'", "Path(os.environ.get('STATION_SHERPA_DEPS', str(PROJECT.parents[1] / '.deps/sherpa'))) / 'downloads/sherpa-onnx-jvm-2.0.1.jar'")
        path.write_text(text, encoding='utf-8')


def adapt_app_tests(root):
    for path in (root / 'apps').rglob('*.py'):
        if 'tests' not in path.parts: continue
        text = path.read_text(encoding='utf-8')
        if '\nimport os\n' not in text:
            text = text.replace('from pathlib import Path\n', 'from pathlib import Path\nimport os\n')
        text = re.sub(r'^JAVA = .*C:[^\n]*', "JAVA = Path(os.environ['STATION_JAVA_HOME']) / 'bin'", text, flags=re.M)
        text = re.sub(r'^JSON = .*C:[^\n]*', "JSON = ROOT.parents[1] / 'pc/tests/speechify-host/deps/json-20240303.jar'", text, flags=re.M)
        text = text.replace("ROOT.parent / 'station-audioprobe/sdk-parts/platform/android.jar'", "Path(os.environ['STATION_ANDROID_PARTS']) / 'platform/android.jar'")
        path.write_text(text, encoding='utf-8')


def adapt_test_contracts(root):
    pc = root / 'pc'
    path = pc / 'tests/test_runtime_supervisor.py'
    text = path.read_text(encoding='utf-8')
    text = text.replace("with patch.dict(os.environ, USERPROFILE=str(profile), LOCALAPPDATA=str(self.root / 'overlay-a')):", "with patch.dict(os.environ, USERPROFILE=str(profile), LOCALAPPDATA=str(self.root / 'overlay-a'), STATION_PRIVATE_DIR=str(profile / '.stationlab')):")
    text = text.replace("environment = dict(os.environ, USERPROFILE=str(profile), LOCALAPPDATA=str(local))", "environment = dict(os.environ, USERPROFILE=str(profile), LOCALAPPDATA=str(local), STATION_PRIVATE_DIR=str(profile / '.stationlab'))")
    text = text.replace("self.assertIn('station_voice_daemon.py', script)", "self.assertIn('retired_pc_voice_server.py', script)")
    path.write_text(text, encoding='utf-8')
    path = pc / 'tests/test_tunnel_only_supervisor.py'
    text = path.read_text(encoding='utf-8')
    text = text.replace("USERPROFILE=str(self.root), LOCALAPPDATA=str(self.root/'wrong-overlay')", "USERPROFILE=str(self.root), LOCALAPPDATA=str(self.root/'wrong-overlay'), STATION_PRIVATE_DIR=str(self.root/'.stationlab')")
    text = text.replace("self.root/'AppData/Local/StationLab/stand/local-config-relay.json'", "self.root/'.stationlab/state/local-config-relay.json'")
    path.write_text(text, encoding='utf-8')
    path = pc / 'tests/test_native_config_window.py'
    text = path.read_text(encoding='utf-8')
    text = text.replace("local / 'Temp/opencode/station-adb-sync/02-sync-glagol-plus-voice.json'", "local / 'pc/native-baseline.json'")
    text = text.replace("environment = dict(os.environ, LOCALAPPDATA=str(local))", "environment = dict(os.environ, LOCALAPPDATA=str(local), STATION_PRIVATE_DIR=str(local))")
    path.write_text(text, encoding='utf-8')
    # Only exact public fake credentials / standards examples are suppressed.
    for filename, marker in [('cloud-host/CloudClientHostTest.java', 'private static final String KEY ='), ('local-config-push-host/LocalConfigPushHostTest.java', 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==')]:  # gitleaks:allow -- RFC 6455 public example nonce, no API credential.
        path = pc / 'tests' / filename
        lines = path.read_text(encoding='utf-8').splitlines()
        lines = [line + ' // gitleaks:allow -- public synthetic test fixture, not an API credential.' if marker in line and 'gitleaks:allow' not in line else line for line in lines]
        path.write_text('\n'.join(lines) + '\n', encoding='utf-8')
