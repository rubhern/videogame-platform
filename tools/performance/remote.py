#!/usr/bin/env python3
"""Windows-native generator / Lenovo adapter for tools/performance; opt-in only."""
import argparse
import concurrent.futures
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import tarfile
import tempfile
import time
import urllib.parse

import authenticated as a
import baseline as b
import remote_interference as interference

ALIAS='videogame-platform-lenovo'
REVISION='8cb6071127b65c78fc74609502df4b4189b055d2'


def run(*args, **kwargs):
    return subprocess.check_output(args, **kwargs)


def paired_audit(points):
    differences=[{'sample':i,'wallDeltaSeconds':c['wallSeconds']-p['wallSeconds'],
                  'monotonicDeltaSeconds':c['monotonicSeconds']-p['monotonicSeconds']}
                 for i,(p,c) in enumerate(zip(points,points[1:]))]
    return {'samples':len(points),'backsteps':[x for x in differences if x['wallDeltaSeconds']<0],
            'discontinuities':[x for x in differences if abs(x['wallDeltaSeconds']-x['monotonicDeltaSeconds'])>.1],
            'maxAbsoluteDifferenceSeconds':max([abs(x['wallDeltaSeconds']-x['monotonicDeltaSeconds']) for x in differences],default=0)}


def native(phase, directory, workload='baseline', users=None, timeout=240):
    local=run('powershell.exe','-NoProfile','-NonInteractive','-Command',
              '[Environment]::GetFolderPath("LocalApplicationData")',text=True).strip()
    folder=Path(run('wslpath','-u',local,text=True).strip())/'GameometroPerformance'/'launcher'
    folder.mkdir(parents=True,exist_ok=True)
    for name in ['windows-k6.ps1','baseline.js','authenticated.js','mixed.js']:
        shutil.copy2(b.TOOL/name,folder/name)
    win=lambda path:run('wslpath','-w',str(path),text=True).strip()
    args=['powershell.exe','-NoProfile','-NonInteractive','-ExecutionPolicy','RemoteSigned',
          '-File',win(folder/'windows-k6.ps1'),'-Phase',phase]
    if phase!='Install':
        args+=['-Workload',workload,'-ConfigFile',win(directory/'workload.json'),
               '-OutputDirectory',win(directory),'-TimeoutSeconds',str(timeout)]
        if users: args+=['-UsersFile',win(users)]
    result=subprocess.run(args,text=True,capture_output=True,timeout=timeout+120)
    if phase=='Install' and result.returncode==0: return json.loads(result.stdout.strip())
    if result.returncode: raise RuntimeError('Native Windows k6 failed; private command output withheld; inspect result files')


class Remote:
    def __init__(self,directory,recovery=False):
        self.directory=directory
        self.remote=(json.loads((directory/'host-staging.json').read_text())['directory'] if recovery else
                     run('ssh','-o','BatchMode=yes',ALIAS,'umask 077; mktemp -d /tmp/vgp-performance-85-XXXXXXXXXX',text=True).strip())
        if not re.fullmatch('/tmp/vgp-performance-85-[A-Za-z0-9]+',self.remote): raise RuntimeError('Unexpected host staging path')
        package=io.BytesIO()
        paths=[b.TOOL/'remote_worker.py',b.TOOL/'generate.sql',b.TOOL/'profile.sql',
               b.TOOL/'provider_fixture.py',b.TOOL/'interference-state.sql',
               b.ROOT/'deploy/private-dev/bin/provision-oidc-smoke-user',
               *(b.ROOT/'backend/src/main/resources/db/migration').glob('V*.sql'),
               *(b.ROOT/'backend/src/test/resources/provider/igdb').glob('*response.json')]
        with tarfile.open(fileobj=package,mode='w') as archive:
            for path in sorted(paths): archive.add(path,arcname=str(path.relative_to(b.ROOT)),recursive=False)
        if not recovery:
            subprocess.run(['ssh','-o','BatchMode=yes',ALIAS,'tar -xf - -C '+shlex.quote(self.remote)],input=package.getvalue(),check=True)
        self.process=subprocess.Popen(['ssh','-o','BatchMode=yes','-o','ServerAliveInterval=15',ALIAS,
             'python3 '+shlex.quote(self.remote+'/tools/performance/remote_worker.py')+' '+shlex.quote(self.remote)],
             stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,text=True)
        if not recovery: b.write(directory/'host-staging.json',{'directory':self.remote,'ownerSshAlias':ALIAS})

    def call(self,action,**arguments):
        self.process.stdin.write(json.dumps({'action':action,'arguments':arguments})+'\n'); self.process.stdin.flush()
        line=self.process.stdout.readline()
        if not line: raise RuntimeError('Host RPC ended; inspect owned host receipt before retrying')
        result=json.loads(line)
        if not result['ok']: raise RuntimeError(result['error'])
        return result['value']

    def close(self):
        self.process.stdin.close()
        self.process.wait(timeout=300)
        # Preserve receipts/staging for verified recovery; never blindly rm a remote path.


def plan(metadata):
    return {'revision':metadata['sourceRevision'],'image':metadata['image'],'profile':'growth',
        'fixtureCounts':{'games':50000,'releases':200000,'ratings':500000},'identityPool':2,
        'sharedImpact':['Fixture generation shares PostgreSQL CPU/RAM/WAL/I/O with private-dev.',
                        'Application recreation invalidates existing BFF sessions.',
                        'The existing HTTPS private-dev URL temporarily serves synthetic data; no normal users may use it during the window.'],
        'mutations':['Create only a previously absent videogame_performance database in the verified existing PostgreSQL container.',
                     'Replay existing immutable SQL and generator only in that owned database; no roles, passwords or volumes replaced.',
                     'Create two new independently owned synthetic Keycloak users through the existing management-only seam.',
                     'Recreate application only, with the same digest/limits/origins/secrets, disposable database, disabled scheduling and disabled real-provider endpoints.',
                     'Run native Windows k6 with existing workloads; delete owned synthetic accounts, restore application from the original base Compose/runtime, verify private aggregates and remove the OID-bound experiment database.'],
        'rollback':['Hold the existing deployment lock throughout the experiment.',
                    'Persist phase/container/database OID/ownership receipts before the next mutation.',
                    'Restore in finally and on SSH EOF; provide receipt-based recovery after forced interruption.',
                    'Never delete a replaced database/container/account; never DROP DATABASE FORCE.',
                    'If an unexpected principal writes to the synthetic DB, restore private-dev but retain that DB for owner review.'],
        'approvalRequired':True,'capacityExecuted':False}


def public_configuration(metadata,directory,rate,duration,growth,profile=None,vus=None):
    base=metadata['origin']+'/api/v1'
    date=b.get(base+'/releases?view=recent&weeks=1&page=1&pageSize=20')['evaluatedOn']
    if growth: requests=b.growth_requests(profile or 'adversarial'); game=a.game(4)
    else:
        search=b.get(base+'/games?q=adventure&page=1&pageSize=20')
        if not search['items']: raise RuntimeError('Read-only search cohort is empty; pick an explicit existing cohort')
        game=search['items'][0]['gameId']
        requests=[{'name':'releases_recent','path':'/releases?view=recent&weeks=1&page=1&pageSize=20'},
                  {'name':'releases_upcoming','path':'/releases?view=upcoming&weeks=1&page=1&pageSize=20'},
                  {'name':'releases_featured','path':'/featured-releases'},
                  {'name':'catalogue_search','path':'/games?q=adventure&page=1&pageSize=20'},
                  {'name':'game_detail','path':'/games/'+game}]
    return {'runId':directory.name,'dataset':'growth' if growth else 'current','evaluatedOn':date,
            'baseUrl':base,'origin':metadata['origin'],'keycloakOrigin':metadata['keycloakOrigin'],
            'cookieName':metadata['cookieName'],'hosts':{},'requests':requests,'gameId':game,
            'rate':rate,'durationSeconds':duration,'expectedRequests':rate*duration,
            'workloadProfile':(profile or 'adversarial') if growth else 'current',
            'requestDistribution':b.request_distribution(requests,rate*duration),
            # Equal pre-allocated/max VUs: no mid-run VU allocation inside the measured window.
            'preAllocatedVUs':vus or default_vus(rate),'maxVUs':vus or default_vus(rate)}


def default_vus(rate):
    return 1 if rate==1 else 4


def validate_vus(rate,vus):
    """Explicit bound only: Little's law needs rate x worst latency, never above the 10 s k6 timeout."""
    if vus is not None and not 1<=vus<=min(60,rate*12):
        raise RuntimeError('VUs must be explicit and within 1..min(60, rate*12)')


def report(directory,observation):
    evidence=a.raw_evidence(directory)
    generator=paired_audit(json.loads((directory/'generator-clock.json').read_text()))
    host=paired_audit(observation['clocks'])
    http=b.clock_audit(directory)
    stable=all(not side['backsteps'] and not side['discontinuities'] and side['samples']>=20 for side in [generator,host]) and not http['backsteps']
    result={**evidence,'generator':json.loads((directory/'generator.json').read_text()),
            'generatorExit':json.loads((directory/'generator-outcome.json').read_text()),
            'clockAudit':{'generator':generator,'lenovo':host,'http':http,'stableDuringObservedWindow':stable},
            'observationFailures':observation['failures'],
            'applicationLog':{k:v for k,v in observation.get('applicationLog',{}).items() if k!='events'},
            'postgresLog':observation.get('postgresLog'),'capacityInterpretationAllowed':False}
    if (directory/'background.json').exists():
        result['interference']=interference.correlate(directory,observation,stable)
        (directory/'interference-report.md').write_text(interference.markdown(result['interference']))
    b.write(directory/'evidence.json',result)
    return result


def execute(args):
    if args.command in ['smoke','capacity'] and not args.approved_maintenance_window:
        raise RuntimeError('Shared private-dev interruption needs explicit owner approval; run plan first')
    b.TOOL.joinpath('results').mkdir(exist_ok=True)
    directory=Path(tempfile.mkdtemp(prefix='remote-'+args.command+'-',dir=b.TOOL/'results'))
    print('Evidence: '+str(directory),flush=True)
    remote=Remote(directory)
    observing=False
    try:
        metadata=remote.call('preflight',revision=args.revision)
        b.write(directory/'target.json',metadata)
        b.write(directory/'plan.json',plan(metadata))
        if args.command=='plan': return directory
        native('Install',directory)
        growth=args.command in ['smoke','capacity']
        workload=public_configuration(metadata,directory,args.rate,args.duration_seconds,False)
        if growth:
            date=workload['evaluatedOn']
            b.write(directory/'profile.json',remote.call('prepare',date=date))
            private=remote.call('users',specs=a.user_specs()[:2]) if args.command=='smoke' else []
            b.write(directory/'activation.json',remote.call('activate'))
            workload=public_configuration(metadata,directory,args.rate,args.duration_seconds,True,
                                          args.workload_profile,args.vus)
        else: private=[]
        if args.command=='capacity' and args.scenario!='serving-only':
            b.write(directory/'background-preparation.json',remote.call('background_prepare',scenario=args.scenario))
        # After every experiment write, so autovacuum debt does not overlap the measured window.
        if args.command=='capacity': b.write(directory/'settle.json',remote.call('settle'))
        mode='mixed' if args.command=='smoke' else 'baseline'
        if mode=='mixed':
            workload.update(userPool=[{k:v for k,v in u.items() if k!='password'} for u in private],
                iterationsPerUser=2,publicIterations=10,requestPauseSeconds=1,
                authStartSpacingSeconds=55,publicStartSeconds=110,maxDuration='55s')
            workload['requests']=workload['requests'][:3]+[{'name':'catalogue_search','path':'/games?q=adventure&page=1&pageSize=20'},
                {'name':'game_detail','path':'/games/'+a.game(4)}]
            workload.update(workloadProfile='smoke'); workload.pop('requestDistribution',None)
        b.write(directory/'workload.json',workload)
        b.write(directory/'manifest.json',{'sourceRevision':b.command('git','-C',str(b.ROOT),'rev-parse','HEAD'),
            'purpose':'functional-only' if args.command!='capacity' else 'owner-selected-capacity-pilot',
            'scriptsSha256':{p:hashlib.sha256((b.TOOL/p).read_bytes()).hexdigest() for p in ['baseline.js','authenticated.js','mixed.js','windows-k6.ps1','remote_worker.py','provider_fixture.py','interference-state.sql','remote_interference.py']}})
        with tempfile.TemporaryDirectory(prefix='vgp-remote-private-') as protected:
            users=Path(protected)/'users.json'
            if private: b.write(users,private); os.chmod(users,0o600)
            native('Inspect',directory,mode,users if private else None)
            b.write(directory/'before.json',remote.call('start_observation')); observing=True
            if args.command=='capacity' and args.scenario!='serving-only':
                before_clock=time.time()
                host_clock=remote.call('clock')
                after_clock=time.time()
                b.write(directory/'clock-bridge.json',{'controllerStarted':before_clock,
                    'controllerFinished':after_clock,'host':host_clock,
                    'hostMinusControllerSeconds':[host_clock['wallSeconds']-after_clock,
                                                  host_clock['wallSeconds']-before_clock]})
            native_failure=None
            try:
                with concurrent.futures.ThreadPoolExecutor(max_workers=1) as executor:
                    future=executor.submit(native,'Run',directory,mode,users if private else None,
                                           timeout=180 if mode=='mixed' else args.duration_seconds+45)
                    if args.command=='capacity' and args.scenario!='serving-only':
                        deadline=time.monotonic()+30
                        while not (directory/'generator-started.json').exists():
                            if future.done(): future.result()
                            if time.monotonic()>deadline: raise RuntimeError('Native generator start coordination failed')
                            time.sleep(.25)
                        time.sleep(5)
                        b.write(directory/'background.json',remote.call('background_sync'))
                    future.result()
            except RuntimeError as error: native_failure=error
            finally:
                observation=remote.call('stop_observation'); observing=False
                b.write(directory/'observation.json',observation)
                b.write(directory/'after.json',observation['after'])
                b.write(directory/'application-errors.json',observation.get('applicationLog'))
            result=report(directory,observation)
            if native_failure: raise native_failure
            if result.get('interference') and not result['interference'].get('successfulWorkVerified'):
                raise RuntimeError('Background synchronization failed; raw lifecycle and restoration retained')
            if result['http']['checks'].get('fails') or result['generatorExit']['exitCode'] or not result['clockAudit']['stableDuringObservedWindow']:
                raise RuntimeError('Functional or clock audit failed; raw evidence retained')
        return directory
    finally:
        if observing:
            try: b.write(directory/'observation.json',remote.call('stop_observation'))
            except Exception: pass
        try:
            receipt=remote.call('restore')
            b.write(directory/'restoration.json',receipt)
            if (directory/'evidence.json').exists():
                result=json.loads((directory/'evidence.json').read_text())
                result['restoration']=receipt
                if result.get('interference'):
                    result['interference']['restoration']=receipt
                    (directory/'interference-report.md').write_text(interference.markdown(result['interference']))
                b.write(directory/'evidence.json',result)
        except Exception as error:
            b.write(directory/'recovery-required.json',{'status':'restoration-failed','errorType':type(error).__name__,
                    'procedure':'Use recover with this exact evidence directory; inspect the owned host receipt.'})
            raise
        finally: remote.close()


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    sub=parser.add_subparsers(dest='command',required=True)
    sub.add_parser('install')
    recovery=sub.add_parser('recover')
    recovery.add_argument('directory')
    for name in ['plan','probe','smoke','capacity']:
        p=sub.add_parser(name)
        p.add_argument('--revision',default=REVISION)
        p.add_argument('--approved-maintenance-window',action='store_true')
        p.add_argument('--rate',type=int,default=5 if name=='capacity' else 1)
        p.add_argument('--duration-seconds',type=int,default=60 if name=='capacity' else 15)
        p.add_argument('--scenario',choices=['serving-only','serving-sync-reuse','serving-sync-inference'],default='serving-only')
        p.add_argument('--workload-profile',choices=b.WORKLOAD_PROFILES,required=name=='capacity')
        p.add_argument('--vus',type=int)
    args=parser.parse_args()
    try:
        if args.command=='install': print(json.dumps(native('Install',b.TOOL)))
        elif args.command=='recover':
            directory=Path(args.directory).resolve()
            if directory.parent!=(b.TOOL/'results').resolve() or not directory.name.startswith('remote-'):
                raise RuntimeError('Recovery accepts only this harness result directory')
            remote=Remote(directory,recovery=True)
            try: b.write(directory/'restoration.json',remote.call('restore'))
            finally: remote.close()
        else:
            if not re.fullmatch('[0-9a-f]{40}',args.revision): raise RuntimeError('Full source SHA required')
            if not 1<=args.rate<=25 or not 10<=args.duration_seconds<=300: raise RuntimeError('Explicit bounded rate/duration required')
            if args.command in ['probe','smoke'] and (args.rate!=1 or args.duration_seconds!=15): raise RuntimeError('Smoke/probe budgets are fixed')
            if args.command!='capacity' and args.scenario!='serving-only': raise RuntimeError('This phase has no background interference execution')
            if args.command!='capacity' and (args.workload_profile or args.vus): raise RuntimeError('Workload profile and VUs apply only to capacity')
            validate_vus(args.rate,args.vus)
            execute(args)
    except Exception as error:
        print(type(error).__name__+': '+str(error),file=__import__('sys').stderr)
        raise SystemExit(1)
