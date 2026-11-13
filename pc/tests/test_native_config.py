"""Host-only tests of the production local config responder; no ADB or live services."""
from pathlib import Path
import os
import hashlib
import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2] / 'apps/voicebridge'
HOST = Path(__file__).resolve().parent / "native-config-host"
JDK = Path(os.environ.get('STATION_JAVAC_HOME', os.environ.get('STATION_JAVA_HOME', ''))) / 'bin'
JAVA = Path(os.environ.get('STATION_JAVA_HOME', '')) / 'bin' / 'java.exe'
ANDROID = Path(os.environ.get('STATION_ANDROID_PARTS', str(ROOT.parents[1] / '.deps/android'))) / 'platform/android.jar'
JSON_JAR = HOST.parent / "speechify-host/deps/json-20240303.jar"
PC_CONTRACT = ROOT.parents[1] / 'pc/LocalConfigServer.py'


def contract_fixtures():
    spec = importlib.util.spec_from_file_location("station_config_host_contract", PC_CONTRACT)
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    template = {"status": "ok", "config": {"account_config": {"spotter": "alisa", "keep": 7},
                "device_config": {"name": "fixture", "led": {"keep": True}},
                "system_config": {"uniProxyUrl": "ws://127.0.0.1:17682/uni.ws", "keep": "unchanged",
                                  "pushd": {"keep": True, "xivaServices": "quasar-realtime,messenger-prod"}}},
                "glagol": {"security": "synthetic-test-only"}}
    policy = module.Policy({"status": "ok", "hasUpdate": False, "hasCriticalUpdate": False},
                           extra_hosts=module.OPTIONAL_HTTPS_HOSTS,
                           http_connectivity_hosts=module.OPTIONAL_HTTP_HOSTS,
                           local_registration_stub=True, local_oauth_stub=True, local_sync_payload=True)
    cases = []
    rows = [(method, path, "quasar.yandex.net", 443) for path in
            ["/check_updates", "/get_sync_info", "/push_subscribe", "/get_timezone", "/analytics/startup",
             "/get_user_details", "/glagol/device_list", "/glagol/device_list?device_id=fixture-id",
             "/register", "/generate_204", "/unknown"] for method in ["GET", "POST", "HEAD", "DELETE"]]
    rows += [("POST", "/token", "oauth.yandex.ru", 443), ("GET", "/token", "oauth.yandex.ru", 443),
             ("GET", "/gen_204", "www.google.com", 80), ("GET", "/generate_204", "connectivitycheck.gstatic.com", 80),
             ("GET", "/check_updates", "unknown.invalid", 443)]
    for method, target, host, port in rows:
        raw, _ = module.build_http_response(method, target, host, port, sni=host if port == 443 else None,
                                             policy=policy, sync_payload=template)
        header, body = raw.split(b"\r\n\r\n", 1)
        expected = json.loads(body) if body else None
        if expected is not None and target == "/get_sync_info":
            expected["config"]["system_config"]["xivaSubscribeUrl"] = "wss://station-config.quasar.yandex.net/station-config-sync"
            expected["config"]["system_config"]["pushd"]["xivaServices"] = "quasar-realtime"
        if expected is not None and target == "/push_subscribe":
            expected.update(sign="station-local-config", ts="1700003600")
        cases.append(dict(method=method, target=target, host=host, port=port,
                          status=int(header.split(b" ")[1]), body=expected))
    return dict(template=template, cases=cases)


class NativeConfigTests(unittest.TestCase):
    def test_actual_store_protocol_and_loopback_tls(self):
        self.assertEqual(hashlib.sha256(JSON_JAR.read_bytes()).hexdigest(),
                         "3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed")
        sources = [ROOT / f"src/local/station/voicebridge/{name}.java" for name in
                   ["NativeConfigJson", "NativeConfigStore", "NativeConfigProtocol", "NativeConfigServer"]]
        with tempfile.TemporaryDirectory(prefix="native-config-host-") as temporary:
            temporary = Path(temporary)
            android_classes, host_classes = temporary / "android", temporary / "host"
            android_classes.mkdir(); host_classes.mkdir()
            fixtures, keys = temporary / "fixtures.json", temporary / "test.p12"
            fixtures.write_text(json.dumps(contract_fixtures(), ensure_ascii=False), encoding="utf-8")
            commands = [
                [JDK / "javac.exe", "-encoding", "UTF-8", "-source", "8", "-target", "8", "-Xlint:all",
                 "-classpath", ANDROID, "-d", android_classes, *sources],
                [JDK / "javac.exe", "-encoding", "UTF-8", "-source", "8", "-target", "8", "-Xlint:all",
                 "-classpath", JSON_JAR, "-d", host_classes, *sources, *HOST.rglob("*.java")],
                [JDK / "keytool.exe", "-genkeypair", "-alias", "test-only", "-keyalg", "RSA", "-keysize", "2048",
                 "-validity", "1", "-dname", "CN=quasar.yandex.net", "-storetype", "PKCS12", "-keystore", keys,
                 "-storepass", "test-only-password", "-keypass", "test-only-password", "-noprompt"],
                [JAVA, "-cp", str(host_classes) + ";" + str(JSON_JAR), "local.station.voicebridge.NativeConfigHostTest",
                 fixtures, keys],
            ]
            for command in commands:
                result = subprocess.run([str(part) for part in command], capture_output=True, text=True,
                                        encoding="utf-8", errors="replace", timeout=45)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn("assertions passed; isolated fixture and loopback only", result.stdout)
            print(result.stdout.strip())


if __name__ == "__main__":
    unittest.main()
