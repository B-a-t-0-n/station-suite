"""Offline packet fixtures and explicit diagnostic deployment-boundary contracts."""
from pathlib import Path
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET
from test_proc_socket_owner import java_tool

ROOT = Path(__file__).resolve().parents[1]


class SynOwnerProbeTests(unittest.TestCase):
    def test_fixed_syn_tuple_checksums_bounds_and_nonce(self):
        with tempfile.TemporaryDirectory(prefix='station-syn-owner-host-') as temporary:
            sources = [ROOT / 'src/local/station/wifi/ProcSocketOwner.java',
                       ROOT / 'src/local/station/wifi/SynOwnerProbeSpec.java',
                       ROOT / 'tests/SynOwnerProbeSpecHostTest.java']
            for command in [
                [java_tool('javac'), '-encoding', 'UTF-8', '-source', '8', '-target', '8', '-Xlint:all', '-d', temporary, *map(str, sources)],
                [java_tool('java'), '-cp', temporary, 'local.station.wifi.SynOwnerProbeSpecHostTest'],
            ]:
                result = subprocess.run(command, capture_output=True, text=True, encoding='utf8', errors='replace', timeout=35)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn('assertions passed; bounded SYN wire fixtures only; no device/network', result.stdout)
            print(result.stdout.strip())

    def test_service_is_private_disposable_and_not_a_forwarder(self):
        android = '{http://schemas.android.com/apk/res/android}'
        manifest = ET.parse(ROOT / 'AndroidManifest.xml').getroot()
        service = next(item for item in manifest.find('application').findall('service')
                       if item.get(android + 'name') == '.SynOwnerProbeService')
        self.assertEqual(service.get(android + 'exported'), 'false')
        self.assertEqual(service.get(android + 'process'), ':synprobe')
        self.assertEqual(service.get(android + 'permission'), 'android.permission.BIND_VPN_SERVICE')
        self.assertEqual(service.find('meta-data').get(android + 'value'), 'false')
        source = (ROOT / 'src/local/station/wifi/SynOwnerProbeService.java').read_text(encoding='utf8')
        self.assertEqual(source.count('.addRoute('), 1)
        self.assertIn('.addRoute(SynOwnerProbeSpec.DESTINATION,32)', source)
        self.assertEqual(source.count('.addAddress('), 1)
        self.assertIn('WifiManager.WIFI_STATE_DISABLED', source)
        self.assertIn('NetworkCapabilities.TRANSPORT_VPN', source)
        self.assertIn('if(VpnService.prepare(activity)!=null)', source)
        self.assertIn('if(VpnService.prepare(this)!=null)', source)
        self.assertIn('START_NOT_STICKY', source)
        self.assertIn('ProcSocketOwner.TcpState.SYN_SENT', source)
        for excluded in ('getPackageName()', '"com.android.shell"', '"local.station.agent"', '"local.station.voicebridge"'):
            self.assertIn(excluded, source)
        for forbidden in ('.addDnsServer(', '.addAllowedApplication(', '"0.0.0.0"', '"::"', '.setWifiEnabled(',
                          'Os.write(', '.bindSocket(', '.connect(', 'new Socket(', 'new DatagramSocket(',
                          'getSharedPreferences(', 'startActivityForResult(', 'TProxyService'):
            self.assertNotIn(forbidden, source)
        receiver = (ROOT / 'src/local/station/wifi/BootReceiver.java').read_text(encoding='utf8')
        self.assertNotIn('SynOwnerProbe', receiver)


if __name__ == '__main__':
    unittest.main()
