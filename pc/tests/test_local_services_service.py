"""Actual local-services Android lifecycle with fake network/material dependencies."""
from pathlib import Path
import os
import subprocess
import tempfile
import unittest
from test_probe_service_lifecycle import STUBS, ROOT, JAVAC, JAVA, JSON_JAR


class LocalServicesServiceTests(unittest.TestCase):
    def test_bootstrap_failure_cleanup_sticky_status_and_commit_notification(self):
        stubs = dict(STUBS)
        stubs['android/app/Service.java'] = stubs['android/app/Service.java'].replace(
            'START_NOT_STICKY=2', 'START_NOT_STICKY=2,START_STICKY=1')
        stubs['android/os/Process.java'] = 'package android.os; public final class Process {public static int myPid(){return 42;}}'
        stubs['android/os/SystemClock.java'] = 'package android.os; public final class SystemClock {public static long now=1000;public static long elapsedRealtime(){return now;}}'
        stubs['android/os/HandlerThread.java'] = '''package android.os; public class HandlerThread extends Thread {
            public HandlerThread(String name){super(name);} public void start(){} public Looper getLooper(){return Looper.getMainLooper();}
            public boolean quitSafely(){return true;}
        }'''
        stubs['android/content/Context.java'] = '''package android.content;public class Context {
            public Context getApplicationContext(){return this;}
            public java.io.File getFilesDir(){return new java.io.File(System.getProperty("station.fixture"));}
            public <T>T getSystemService(Class<T> type){try{return type.newInstance();}catch(Exception e){throw new AssertionError(e);}}
        }'''
        with tempfile.TemporaryDirectory(prefix='local-services-host-') as temporary:
            target = Path(temporary)
            sources = []
            for name, source in stubs.items():
                path = target / 'stubs' / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(source, encoding='utf8'); sources.append(path)
            sources += [ROOT / 'src/local/station/voicebridge/LocalServicesService.java',
                        Path(__file__).resolve().parent / 'local-services-host/LocalServicesServiceHostTest.java']
            classes = target / 'classes'; classes.mkdir()
            fixture = target / 'fixture'; fixture.mkdir()
            commands = [
                [JAVAC, '-encoding', 'UTF-8', '-source', '8', '-target', '8', '-Xlint:all,-auxiliaryclass',
                 '-classpath', JSON_JAR, '-d', classes, *sources],
                [JAVA, '-Dstation.fixture=' + str(fixture), '-cp', str(classes) + os.pathsep + str(JSON_JAR),
                 'local.station.voicebridge.LocalServicesServiceHostTest']]
            for command in commands:
                result = subprocess.run([str(x) for x in command], capture_output=True, text=True,
                                        encoding='utf8', errors='replace', timeout=30)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn('local services lifecycle assertions passed', result.stdout)
            print(result.stdout.strip())


if __name__ == '__main__':
    unittest.main()
