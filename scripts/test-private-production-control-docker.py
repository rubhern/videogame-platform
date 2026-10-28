#!/usr/bin/env python3
"""Disposable local Docker proofs, not a Gameómetro deployment or VPS acceptance."""
import argparse
import contextlib
import errno
import hashlib
import json
import os
from pathlib import Path
import signal
import subprocess
import tempfile
import time
from types import SimpleNamespace
from unittest.mock import patch
import uuid
import pty

import install_private_production as installer
import private_production_control as control
import public_production_integration as integration
import validate_private_production_truststore as stores


def raw(args, *, data=None, timeout=90, output=True, **_):
    p = subprocess.run([str(a) for a in args], input=data, timeout=timeout,
                       stdout=subprocess.PIPE if output else subprocess.DEVNULL, stderr=subprocess.PIPE)
    if p.returncode: raise RuntimeError('Disposable proof command failed; raw diagnostics withheld.')
    return p.stdout or b''


def reset():
    control.CANCEL.clear(); control.CAUSE = None; control.UNCERTAIN_COMMAND = False


class Fixture:
    def __init__(self):
        self.token = uuid.uuid4().hex
        self.prefix = 'vgp-276-proof-' + self.token[:12]
        self.run = str(uuid.uuid4()); self.configuration = 'c' * 64
        self.containers = []; self.networks = []; self.volumes = []

    def network(self, suffix, option='nat'):
        name = self.prefix + '-' + suffix
        raw(['docker', 'network', 'create', '--internal', '--ipv6=false', '--label', 'vgp.proof=' + self.token,
             '--opt', 'com.docker.network.bridge.gateway_mode_ipv4=' + option, name])
        self.networks.append(name); return name

    def volume(self):
        name = self.prefix + '-gate'
        raw(['docker', 'volume', 'create', '--label', 'vgp.proof=' + self.token, name]); self.volumes.append(name); return name

    def sleeper(self, network, *, run_id=None, server=False):
        name = self.prefix + '-c-' + uuid.uuid4().hex[:8]
        code = ('from http.server import HTTPServer,BaseHTTPRequestHandler\n'
                'class H(BaseHTTPRequestHandler):\n def do_GET(self): self.send_response(200);self.end_headers();self.wfile.write(b"proof")\n'
                'HTTPServer(("0.0.0.0",8080),H).serve_forever()') if server else 'import time;time.sleep(180)'
        cid = raw(['docker', 'create', '--name', name, '--network', network, '--read-only', '--restart', 'no',
                   '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges:true', '--memory', '64m', '--cpus', '0.1',
                   '--pids-limit', '32', '--log-driver', 'none', '--label', 'vgp.proof=' + self.token,
                   '--label', f'{control.RUN_LABEL}={run_id or self.run}',
                   '--label', f'{control.CONFIG_LABEL}={self.configuration}',
                   '--label', 'com.docker.compose.project=' + self.prefix,
                   '--label', 'com.docker.compose.service=dummy', '--entrypoint', 'python3', installer.HELPER, '-c', code]).decode().strip()
        self.containers.append(cid); raw(['docker', 'start', cid]); return cid, code

    def gate_ready(self, volume):
        raw(['docker', 'run', '--rm', '--network', 'none', '--log-driver', 'none', '-v', volume + ':/gate',
             '--cap-drop', 'ALL', '--read-only', '--security-opt', 'no-new-privileges:true',
             installer.HELPER, 'python3', '-c', 'from pathlib import Path;Path("/gate/READY").touch()'])

    def gate(self, volume):
        return json.loads(raw(['docker', 'run', '--rm', '--network', 'none', '--log-driver', 'none',
                              '-v', volume + ':/gate:ro', '--read-only', '--cap-drop', 'ALL',
                              installer.HELPER, 'python3', '-c',
                              'import pathlib,json;p=pathlib.Path("/gate");print(json.dumps({"ready":(p/"READY").exists(),"pending":(p/"PENDING.json").exists()}))']))

    def runtime(self, volume):
        runtime = SimpleNamespace(gate_volume=volume, run_id=self.run, run_configuration=self.configuration)
        def check():
            installer.check_kernel_lease(runtime)
        runtime.check_lease = check; return runtime

    def fence_stop(self, runtime):
        runtime.check_lease()
        raw(['docker', 'run', '--rm', '--network', 'none', '--log-driver', 'none', '--read-only', '--cap-drop', 'ALL',
             '-v', runtime.gate_volume + ':/gate', installer.HELPER, 'python3', '-c', control.FENCE,
             self.run, self.configuration])
        return control.stop_owned(self.run, self.configuration, self.prefix, {'dummy'}, runtime.check_lease, runner=raw)

    def running(self, cid):
        return json.loads(raw(['docker', 'inspect', cid]))[0]['State']['Running']

    def close(self):
        # Only exact fixture IDs/names, with an independently checked random label.
        for cid in reversed(self.containers):
            info = json.loads(raw(['docker', 'inspect', cid]))[0]
            assert info['Config']['Labels']['vgp.proof'] == self.token
            raw(['docker', 'rm', '-f', cid])
        for name in reversed(self.networks):
            assert json.loads(raw(['docker', 'network', 'inspect', name]))[0]['Labels']['vgp.proof'] == self.token
            raw(['docker', 'network', 'rm', name])
        for name in reversed(self.volumes):
            assert json.loads(raw(['docker', 'volume', 'inspect', name]))[0]['Labels']['vgp.proof'] == self.token
            raw(['docker', 'volume', 'rm', name])


def prove_control(results):
    f = Fixture(); reset()
    try:
        network = f.network('one'); volume = f.volume(); runtime = f.runtime(volume)
        owned, _ = f.sleeper(network); other, _ = f.sleeper(network, run_id=str(uuid.uuid4()))
        try:
            with installer.bootstrap_lease(runtime):
                f.gate_ready(volume)
                control.signal_handlers(); os.kill(os.getpid(), signal.SIGHUP)
                assert control.CANCEL.wait(1)
                outcome = f.fence_stop(runtime)
                assert outcome['status'] == 'confirmed' and not f.running(owned) and f.running(other)
                raise RuntimeError('simulated SSH cancellation')
        except RuntimeError:
            pass
        assert f.gate(volume) == {'ready': False, 'pending': True}
        results['realDocker']['sighupStopsOwnedBeforeLeaseRelease'] = True
        results['realDocker']['otherAttemptRemainsRunning'] = True
        reset()
        # The controller retains the same kernel flock after actor death.
        try:
            with installer.bootstrap_lease(runtime, mode='reconcile'):
                raw(['docker', 'start', owned])
                helpers = raw(['docker', 'ps', '-q', '--filter', 'volume=' + volume,
                               '--filter', 'label=gameometro.recovery-helper=true']).decode().split()
                assert len(helpers) == 1
                raw(['docker', 'kill', '--signal', 'KILL', helpers[0]])
                assert control.CANCEL.wait(3)
                runtime.check_lease()
                refused = False
                try:
                    raw(['docker', 'run', '--rm', '--network', 'none', '--log-driver', 'none',
                         '-v', volume + ':/gate', installer.HELPER, 'python3', '-c',
                         'import fcntl;f=open("/gate/ACTOR.lock","a");fcntl.flock(f,fcntl.LOCK_EX|fcntl.LOCK_NB)'])
                except RuntimeError: refused = True
                assert refused
                results['realDocker']['actorDeathRetainsActualKernelLease'] = True
                outcome = f.fence_stop(runtime)
                assert outcome['status'] == 'confirmed'
                assert not f.running(owned) and f.running(other)
                results['realDocker']['actorDeathStopsOnlyOwnedUnderRetainedLease'] = True
        except Exception:
            if not results['realDocker'].get('actorDeathStopsOnlyOwnedUnderRetainedLease'): raise
        reset()
        raw(['docker', 'start', owned])
        contender_code = 'import fcntl,sys;f=open("/gate/ACTOR.lock","a");fcntl.flock(f,fcntl.LOCK_EX|fcntl.LOCK_NB);print("locked",flush=True);sys.stdin.buffer.read(1)'
        contender = subprocess.Popen(['docker', 'run', '--rm', '-i', '--network', 'none', '--log-driver', 'none',
                                      '-v', volume + ':/gate', installer.HELPER, 'python3', '-c', contender_code],
                                     stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        try:
            assert contender.stdout.readline() == b'locked\n'
            refused = False
            try:
                with installer.bootstrap_lease(runtime, mode='reconcile'): f.fence_stop(runtime)
            except Exception: refused = True
            assert refused and f.running(owned) and f.running(other)
            results['realDocker']['competingLeasePreventsAnyStop'] = True
        finally:
            contender.stdin.close(); contender.wait(timeout=10); contender.stdout.close()
        with installer.bootstrap_lease(runtime, mode='reconcile'):
            assert f.fence_stop(runtime)['status'] == 'confirmed'
        assert not f.running(owned) and f.running(other)
        results['realDocker']['reacquisitionStopsOnlyOwned'] = True
    finally:
        reset(); f.close()


def prove_host_close(results):
    """Real Host.close/gate/lease/stops; journal and stop failures explicitly injected."""
    results['simulated']['hostCloseJournalIoAndStopErrors'] = 'injected; lease, gate, container inventory and successful stops use real local Docker'
    f = Fixture(); reset()
    try:
        network = f.network('journal'); volume = f.volume(); runtime = f.runtime(volume)
        owned, _ = f.sleeper(network); other, _ = f.sleeper(network, run_id=str(uuid.uuid4()))
        host = installer.Host(f.configuration, 'a'*64, 'b'*40)
        host.run_id = f.run; host.config = {'services': {'dummy': {}}}; host.recovery = runtime
        with patch.object(installer.prepare, 'PROJECT', f.prefix), patch.object(installer, 'run', side_effect=raw):
            with installer.bootstrap_lease(runtime):
                for number in (errno.ENOSPC, errno.EIO):
                    raw(['docker', 'start', owned]); f.gate_ready(volume)
                    failure = OSError(number, 'injected local journal I/O failure')
                    def journal_failure(*_): raise failure
                    runtime.closed = journal_failure
                    try: host.close('injected-journal-failure')
                    except OSError as caught: assert caught is failure
                    else: raise AssertionError('Persistence error was hidden')
                    assert not f.running(owned) and f.running(other)
                    assert f.gate(volume) == {'ready': False, 'pending': True}
                results['realDocker']['hostCloseStopsOwnedDespiteInjectedJournalIoError'] = True
                raw(['docker', 'start', owned])
                stop_error = control.trust.Refused('injected stop request failure')
                def refuse_stop(args, **kwargs):
                    if args[:2] == ['docker', 'stop']: raise stop_error
                    return raw(args, **kwargs)
                with patch.object(installer, 'run', side_effect=refuse_stop):
                    try: host.close('injected-stop-failure')
                    except ExceptionGroup as caught: assert caught.exceptions == (failure, stop_error)
                    else: raise AssertionError('Dual failure was not preserved')
                assert f.running(owned) and f.running(other)
                results['realDocker']['hostClosePreservesInjectedJournalAndStopFailures'] = True
                host.run_id = str(uuid.uuid4())
                try: host.close('wrong-owner')
                except RuntimeError: pass
                else: raise AssertionError('Wrong gate owner was accepted')
                assert f.running(owned) and f.running(other)
                results['realDocker']['hostCloseWrongGateOwnerNeverStopsContainers'] = True
                host.run_id = f.run; runtime.closed = lambda *_: None
                assert host.close('positive-control')['status'] == 'confirmed'
                assert not f.running(owned) and f.running(other)
                results['realDocker']['hostClosePositiveObservedStop'] = True
    finally:
        reset(); f.close()


def prove_networks(results):
    f = Fixture()
    try:
        one=f.network('a');two=f.network('b')
        server,_=f.sleeper(one,server=True)
        info=json.loads(raw(['docker','inspect',server]))[0]
        assert not info['HostConfig']['PortBindings'] and not info['NetworkSettings']['Networks'][one]['GlobalIPv6Address']
        network=json.loads(raw(['docker','network','inspect',one]))[0]
        control.validate_network_options(network['Options']);assert not network['EnableIPv6']
        ip=info['NetworkSettings']['Networks'][one]['IPAddress']
        for attempt in range(20):
            try:
                raw(['docker','run','--rm','--network',one,'--log-driver','none',installer.HELPER,'python3','-c',
                     f'import urllib.request;assert urllib.request.urlopen("http://{ip}:8080",timeout=2).status==200'])
                break
            except RuntimeError:
                if attempt==19:raise
                time.sleep(0.1)  # Readiness, never retry a failed isolation assertion.
        raw(['docker','run','--rm','--network',two,'--log-driver','none',installer.HELPER,'python3','-c',
             f'import socket;s=socket.socket();s.settimeout(2);assert s.connect_ex(("{ip}",8080))!=0'])
        unsafe=f.network('unsafe','nat-unprotected')
        rejected=False
        try:control.validate_network_options(json.loads(raw(['docker','network','inspect',unsafe]))[0]['Options'])
        except Exception:rejected=True
        assert rejected
        results['realDocker'].update(unpublishedPorts=True,ipv6Disabled=True,sameNetworkPositive=True,
                                    differentInternalNetworkDenied=True,liveUnprotectedNatRefused=True)
        # Actual inspect semantics of the post-start validator, with only Compose
        # service discovery supplied by this fixture (no product stack is started).
        sleeper,code=f.sleeper(one)
        host=installer.Host(f.configuration,'a'*64,'b'*40);host.run_id=f.run
        host.config={'services':{'dummy':{'image':installer.HELPER,'restart':'no','read_only':True,
                     'entrypoint':['python3'],'command':['-c',code],'mem_limit':64*1024*1024,'cpus':.1,
                     'pids_limit':32,'cap_drop':['ALL'],'security_opt':['no-new-privileges:true'],'networks':{'one':{}}}},
                     'networks':{'one':{'name':one,'internal':True}},'volumes':{}}
        host.compose=lambda *a,**k:sleeper.encode()
        host.running(['dummy'])
        raw(['docker','network','connect',two,sleeper])
        refused=False
        try:host.running(['dummy'])
        except Exception:refused=True
        assert refused
        results['realDocker']['effectiveContainerInspectionPassed']=True
        results['realDocker']['unexpectedEffectiveNetworkMembershipRefused']=True
    finally:f.close()


def prove_hard_interrupt(results):
    f=Fixture();reset()
    try:
        network=f.network('hard');volume=f.volume();owned,_=f.sleeper(network)
        code='''import sys,time,subprocess
sys.path.insert(0,sys.argv[1])
from types import SimpleNamespace
import install_private_production as i
r=SimpleNamespace(gate_volume=sys.argv[2],run_id=sys.argv[3],run_configuration=sys.argv[4])
r.check_lease=lambda:None
with i.bootstrap_lease(r):
 subprocess.run(['docker','run','--rm','--network','none','--log-driver','none','-v',r.gate_volume+':/gate',i.HELPER,'python3','-c','from pathlib import Path;Path("/gate/READY").touch()'],stdout=subprocess.DEVNULL,check=True)
 print('ready',flush=True)
 while True:time.sleep(1)
'''
        worker=subprocess.Popen(['python3','-c',code,str(Path(installer.__file__).parent),volume,f.run,f.configuration],
                                stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,start_new_session=True)
        try:
            assert worker.stdout.readline()==b'ready\n'
            worker.kill();worker.wait(timeout=5)
            deadline=time.monotonic()+10
            while not f.gate(volume)['pending'] and time.monotonic()<deadline:time.sleep(.1)
            assert f.gate(volume)=={'ready':False,'pending':True} and f.running(owned)
            results['realDocker']['hardKillLeavesDetectableFenceButDoesNotFalselyClaimStop']=True
            runtime=f.runtime(volume)
            with installer.bootstrap_lease(runtime,mode='reconcile'):
                f.fence_stop(runtime)
            assert not f.running(owned)
            results['realDocker']['hardKillStopOnlyReconciliation']=True
        finally:worker.stdout.close()
    finally:reset();f.close()


def prove_session_processes(results):
    script='''import sys,time
sys.path.insert(0,sys.argv[1]);import private_production_control as c
c.signal_handlers();w=c.watch_session();print('ready',flush=True)
assert c.CANCEL.wait(5);print(c.CAUSE,flush=True);w.set()
'''
    for kind in ('pipe-eof','pty-hangup'):
        if kind=='pty-hangup':
            master,slave=pty.openpty();stdin=slave
        else:stdin=subprocess.PIPE
        worker=subprocess.Popen(['python3','-c',script,str(Path(installer.__file__).parent)],stdin=stdin,
                                stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,start_new_session=True)
        try:
            if kind=='pty-hangup':os.close(slave)
            assert worker.stdout.readline()==b'ready\n'
            if kind=='pty-hangup':os.close(master)
            else:worker.stdin.close()
            assert worker.wait(timeout=7)==0
            results['realProcess'][kind]=True
        finally:
            if worker.poll() is None:worker.kill();worker.wait(timeout=3)
            worker.stdout.close()


def prove_truststores(results):
    with tempfile.TemporaryDirectory(prefix='vgp-276-public-pki-') as tmp:
        root=Path(tmp);public=root/'public';public.mkdir()
        raw(['openssl','req','-x509','-newkey','rsa:2048','-nodes','-days','1','-subj','/CN=Disposable-276-Test-CA',
             '-keyout',root/'synthetic-only.key','-out',public/'ca.crt'])
        common=['docker','run','--rm','--network','none','--log-driver','none','--read-only','--cap-drop','ALL',
                '--security-opt','no-new-privileges:true','--user',f'{os.getuid()}:{os.getgid()}',
                '-v',str(public)+':/out','--entrypoint','keytool',stores.IMAGE]
        raw([*common,'-importkeystore','-srckeystore','/opt/java/openjdk/lib/security/cacerts','-srcstorepass','changeit',
             '-destkeystore','/out/truststore.p12','-deststoretype','PKCS12','-deststorepass','changeit','-noprompt'])
        raw([*common,'-importcert','-noprompt','-alias','private-proof','-file','/out/ca.crt',
             '-keystore','/out/truststore.p12','-storepass','changeit'])
        (public/'truststore.p12').chmod(0o644)
        receipt=root/'valid.json';value=stores.validate(public,receipt)
        stores.verify_receipt(value,public/'truststore.p12',public/'ca.crt',stores.IMAGE)
        results['realDocker']['jvmDefaultRootsPlusPrivateCaAccepted']=True
        (public/'truststore.p12').rename(root/'good.p12')
        raw([*common,'-importcert','-noprompt','-alias','only-private','-file','/out/ca.crt',
             '-keystore','/out/truststore.p12','-storetype','PKCS12','-storepass','changeit'])
        (public/'truststore.p12').chmod(0o644)
        refused=False
        try:stores.validate(public,root/'missing.json')
        except Exception:refused=True
        assert refused
        results['realDocker']['missingDefaultRootsRefused']=True
        (public/'truststore.p12').unlink()
        raw(['openssl','pkcs12','-export','-nokeys','-in',public/'ca.crt','-out',public/'truststore.p12','-passout','pass:changeit'])
        (public/'truststore.p12').chmod(0o644)
        refused=False
        try:stores.validate(public,root/'ignored.json')
        except Exception:refused=True
        assert refused
        results['realDocker']['opensslBagIgnoredByJvmRefused']=True


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evidence',type=Path,required=True)
    parser.add_argument('--close-only',action='store_true',help='Lease, Host.close and interruption proofs only')
    args=parser.parse_args()
    os.umask(0o077);assert args.evidence.is_absolute() and not args.evidence.exists()
    integration.verify_local_docker()
    info=json.loads(raw(['docker','info','--format','{{json .}}']))
    compose=raw(['docker','compose','version','--short']).decode().strip()
    results={'scope':'local-disposable-docker-mechanisms-only','productionReady':False,'vpsChanged':False,
             'selection':'closure-only' if args.close_only else 'all-mechanisms',
             'applicationDeployed':False,'versions':control.versions(info['ServerVersion'],compose),
             'realDocker':{},'realProcess':{},'simulated':{'rootHostPreflight':'covered separately by unit tests'},'outcome':'failure'}
    results['source']={'operatorFingerprint':installer.operational_fingerprint(),
                      'proofScriptSha256':hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                      'acceptedApplicationImage':stores.IMAGE,'acceptedHelperImage':installer.HELPER}
    try:
        raw(['docker','image','inspect',installer.HELPER]);raw(['docker','image','inspect',stores.IMAGE])
        prove_control(results);prove_host_close(results);prove_hard_interrupt(results);prove_session_processes(results)
        if not args.close_only: prove_networks(results);prove_truststores(results)
        results['outcome']='success'
        return 0
    finally:
        control.durable(args.evidence,results)
        print(json.dumps(results,indent=2))


if __name__=='__main__':raise SystemExit(main())
