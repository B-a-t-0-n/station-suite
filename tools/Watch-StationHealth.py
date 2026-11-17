"""Bounded passive incident recorder. Never repairs, reboots or changes the Station.

Raw logcat can contain private data. Output must stay outside this checkout.
Stop by creating output/stop; the recorder exits automatically after its deadline.
"""
from __future__ import annotations
import argparse
import json
import logging
from logging.handlers import RotatingFileHandler
import os
from pathlib import Path
import shlex
import socket
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request

REPO=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(REPO/'pc'))
import native_config_window as lifecycle
from deployment import load
HIDDEN=getattr(subprocess,'CREATE_NO_WINDOW',0)
METRICS='printf "UPTIME "; cat /proc/uptime; cat /proc/meminfo; head -1 /proc/stat; printf "WIFI_ON "; settings get global wifi_on; printf "EPOCH "; date +%s'


def logger(path,maximum,backups):
    value=logging.getLogger(str(path));value.setLevel(logging.INFO);value.propagate=False
    handler=RotatingFileHandler(path,maxBytes=maximum,backupCount=backups,encoding='utf-8')
    handler.setFormatter(logging.Formatter('%(message)s'));value.addHandler(handler)
    return value


def port_open(address,port):
    try:
        with socket.create_connection((address,port),1):return True
    except OSError:return False


def sample(value):
    now=time.time();result={'pcEpoch':now,'monotonic':time.monotonic()}
    try:
        import psutil
        addresses=psutil.net_if_addrs();stats=psutil.net_if_stats()
        names=[name for name,items in addresses.items() if any(a.address==value['labIp'] for a in items)]
        result['ethernetLinkUp']=len(names)==1 and stats[names[0]].isup
    except (ImportError,KeyError,OSError):result['ethernetLinkUp']=None
    result['adbTcpReachable']=port_open(value['stationIp'],5555)
    if result['adbTcpReachable']:
        try:
            command=shlex.join(['/system/bin/sh','-c',METRICS])
            q=subprocess.run([value['adb'],'-s',value['serial'],'shell','-T',command],
                capture_output=True,timeout=6,creationflags=HIDDEN)
            text=q.stdout.decode('ascii','replace')[:8192];result['metricsExitCode']=q.returncode
            for line in text.splitlines():
                fields=line.split()
                if len(fields)<2:continue
                if fields[0]=='UPTIME':result['stationUptime']=float(fields[1])
                elif fields[0] in ('MemTotal:','MemAvailable:','MemFree:','SwapTotal:','SwapFree:'):
                    result[fields[0][:-1]+'Kb']=int(fields[1])
                elif fields[0]=='cpu':result['cpuCounters']=[int(x) for x in fields[1:]]
                elif fields[0]=='WIFI_ON':result['wifiOn']=int(fields[1])
                elif fields[0]=='EPOCH':result['clockSkewSeconds']=round(int(fields[1])-now)
        except subprocess.TimeoutExpired:result['metricsTimeout']=True
        except (OSError,ValueError):result['metricsInvalid']=True
    try:
        opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
        with opener.open('http://'+value['stationIp']+':18080/',timeout=2) as response:result['agentHttp']=response.status
    except (OSError,urllib.error.URLError):result['agentReachable']=False
    private=Path(value['privateDir'])
    for label,relative in [('runtime','state/pc-runtime/status.json'),('tunnel','tunnel-only-supervisor/status.json')]:
        try:
            path=private/relative
            if path.stat().st_size>16384:raise ValueError()
            status=json.loads(path.read_text(encoding='utf-8'))
            result[label]={k:status.get(k) for k in ('phase','reason','updatedAtMs')}
        except (OSError,ValueError):result[label]={'unavailable':True}
    return result


def stream_logcat(value,stop,event,output):
    process=None
    try:
        while not stop.is_set():
            if not port_open(value['stationIp'],5555):stop.wait(15);continue
            process=subprocess.Popen([value['adb'],'-s',value['serial'],'logcat','-b','all','-v','threadtime','-T','1'],
                stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,creationflags=HIDDEN)
            event['process']=process
            output.info(json.dumps({'pcEpoch':time.time(),'event':'logcat_attached'}))
            while not stop.is_set():
                line=process.stdout.readline(16384)
                if not line:break
                output.info(str(round(time.time(),3))+' '+line.decode('utf-8','replace').rstrip())
            if process.poll() is None:process.terminate()
            try:process.wait(timeout=2)
            except subprocess.TimeoutExpired:process.kill();process.wait(timeout=2)
            process.stdout.close();event.pop('process',None)
            stop.wait(15)
    except Exception:
        output.info(json.dumps({'pcEpoch':time.time(),'event':'logcat_reader_stopped'}))
    finally:
        if process is not None and process.poll() is None:
            process.kill()


def main(argv=None):
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--deployment',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--seconds',type=int,default=14400)
    parser.add_argument('--interval',type=int,default=30)
    parser.add_argument('--capture-logcat',action='store_true')
    args=parser.parse_args(argv);out=args.output.resolve()
    if out==REPO or REPO in out.parents:parser.error('OUTPUT_MUST_BE_OUTSIDE_CHECKOUT')
    if not 1<=args.seconds<=86400 or not 1<=args.interval<=300:parser.error('RECORDER_LIMIT_INVALID')
    value=load(args.deployment);out.mkdir(parents=True,exist_ok=True);lifecycle.protect_directory(out)
    stop=threading.Event();event={};worker=None
    with lifecycle.mutation_lock(out,timeout=0):
        if (out/'stop').exists():raise ValueError('RECORDER_STOP_REQUESTED')
        log=logger(out/'health.jsonl',1024*1024,3)
        deadline=time.monotonic()+args.seconds;count=0
        try:
            lifecycle.atomic_write(out/'status.json',lifecycle.encoded({'running':True,'pid':os.getpid(),'deadlinePcEpoch':time.time()+args.seconds}))
            if args.capture_logcat:
                worker=threading.Thread(target=stream_logcat,args=(value,stop,event,logger(out/'logcat.private.log',4*1024*1024,2)),daemon=True)
                worker.start()
            while time.monotonic()<deadline and not (out/'stop').exists():
                log.info(json.dumps(sample(value)));count+=1
                stop.wait(min(args.interval,max(0,deadline-time.monotonic())))
        finally:
            stop.set();process=event.get('process')
            if process is not None and process.poll() is None:process.terminate()
            if worker is not None:worker.join(timeout=4)
            lifecycle.atomic_write(out/'status.json',lifecycle.encoded({'running':False,'samples':count,'stoppedPcEpoch':time.time()}))
        print(json.dumps({'ok':True,'samples':count,'readOnly':True}))
    return 0


if __name__=='__main__':raise SystemExit(main())
