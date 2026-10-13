#!/usr/bin/env python3
"""Bounded #85 completion matrix over the existing adapter; owner window required."""
import argparse
import concurrent.futures
import hashlib
import json
import os
import re
from pathlib import Path
import tempfile
import time
import remote as r

POLICY = {'publicRates':[10,15,20,25], 'secondsPerLevel':60, 'maximumVus':30,
          'recoveryRate':5, 'recoverySeconds':60, 'saturationErrorFraction':.01,
          'saturationDropFraction':.01, 'minimumDeliveryFraction':.95, 'maximumP95Ms':1000,
          'criticalStop':['oom','readiness-down','observation-unavailable','observation-stale'],
          'mixedUsers':2,'mixedIterationsPerUser':5,'mixedPublicReads':120,
          'interpretation':'Experimental controls, not product SLOs; stop at first degraded level.'}


def degraded(http, scheduled):
    count=http['http_reqs']['count']
    return (http['http_req_failed']['rate']>=.01 or
            http['dropped_iterations']['count']>=.01*scheduled or
            count<.95*scheduled or http['http_req_duration']['p(95)']>1000)


def mixed_configuration(config, users):
    config.update(userPool=[{k:v for k,v in user.items() if k!='password'} for user in users],
        iterationsPerUser=5,publicIterations=120,requestPauseSeconds=.5,maxDuration='120s',
        authStartSpacingSeconds=0,publicStartSeconds=0,workloadProfile='normal-concurrent-mixed')
    config.pop('requestDistribution',None)
    return config


def manifest(directory, metadata):
    files=['closure.py','remote.py','remote_worker.py','windows-k6.ps1','baseline.js',
           'authenticated.js','mixed.js','provider_fixture.py','interference-state.sql']
    r.b.write(directory/'manifest.json',{'sourceRevision':r.b.command('git','-C',str(r.b.ROOT),'rev-parse','HEAD'),
        'purpose':'owner-approved-issue-85-completion','sutRevision':metadata['sourceRevision'],
        'scriptsSha256':{name:hashlib.sha256((r.b.TOOL/name).read_bytes()).hexdigest() for name in files}})


def stage(bridge, directory, config, mode='baseline', users=None, background=False):
    directory.mkdir(exist_ok=True)
    r.b.write(directory/'workload.json',config)
    r.native('Inspect',directory,mode,users)
    before=bridge.call('start_observation')
    r.b.write(directory/'before.json',before)
    if background:
        start=time.time(); clock=bridge.call('clock'); end=time.time()
        r.b.write(directory/'clock-bridge.json',{'controllerStarted':start,'controllerFinished':end,
            'host':clock,'hostMinusControllerSeconds':[clock['wallSeconds']-end,clock['wallSeconds']-start]})
    failure=None; dispatched=False; background_value=None; abort=None
    timeout=150 if mode=='mixed' else config['durationSeconds']+45
    try:
        with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
            future=pool.submit(r.native,'Run',directory,mode,users,timeout)
            deadline=time.monotonic()+30
            while not (directory/'generator-started.json').exists():
                if future.done(): future.result()
                if time.monotonic()>deadline: raise RuntimeError('Native start coordination deadline')
                time.sleep(.25)
            dispatch_at=time.monotonic()+5
            while not future.done():
                status=bridge.call('observation_status')
                if status['abort']:
                    abort=status
                    r.b.write(directory/'generator-stop-requested.json',status)
                if background and not dispatched and not abort and time.monotonic()>=dispatch_at:
                    bridge.call('start_background'); dispatched=True
                time.sleep(3)
            try: future.result()
            except RuntimeError as error: failure=type(error).__name__
            if dispatched:
                deadline=time.monotonic()+150
                while True:
                    status=bridge.call('background_status')
                    if status['finished']: background_value=status['value']; break
                    if time.monotonic()>deadline: raise RuntimeError('Background completion deadline')
                    time.sleep(3)
                r.b.write(directory/'background.json',background_value)
    finally:
        observation=bridge.call('stop_observation')
        r.b.write(directory/'observation.json',observation)
        r.b.write(directory/'after.json',observation['after'])
        r.b.write(directory/'application-errors.json',observation.get('applicationLog'))
    result=r.report(directory,observation)
    result['nativeFailure']=failure
    result['safetyAbort']=abort
    r.b.write(directory/'evidence.json',result)
    return result


def execute(args):
    if not args.approved_maintenance_window:
        raise RuntimeError('Explicit owner maintenance-window approval is required')
    if not re.fullmatch('[0-9a-f]{40}',args.revision): raise RuntimeError('Full deployed source SHA required')
    r.b.TOOL.joinpath('results').mkdir(exist_ok=True)
    root=Path(tempfile.mkdtemp(prefix='remote-closure-'+args.scenario+'-',dir=r.b.TOOL/'results'))
    print('Evidence: '+str(root),flush=True)
    r.b.write(root/'policy.json',POLICY)
    bridge=r.Remote(root)
    stage_directories=[]
    try:
        metadata=bridge.call('preflight',revision=args.revision)
        r.b.write(root/'target.json',metadata); r.b.write(root/'plan.json',r.plan(metadata)); manifest(root,metadata)
        if args.scenario=='provider':
            r.b.write(root/'provider-calibration.json',bridge.call('provider_calibration'))
            return root
        r.native('Install',root)
        initial=r.public_configuration(metadata,root,10,60,False)
        r.b.write(root/'profile.json',bridge.call('prepare',date=initial['evaluatedOn']))
        private=bridge.call('users',specs=r.a.user_specs()[:2]) if args.scenario=='mixed' else []
        r.b.write(root/'activation.json',bridge.call('activate'))
        if args.scenario in ['inference','mixed']:
            preparation=bridge.call('background_prepare',scenario='serving-sync-inference' if args.scenario=='inference' else 'serving-sync-reuse')
            r.b.write(root/'background-preparation.json',preparation)
        r.b.write(root/'settle.json',bridge.call('settle'))
        with tempfile.TemporaryDirectory(prefix='vgp-closure-private-') as protected:
            users=Path(protected)/'users.json'
            if private: r.b.write(users,private); os.chmod(users,0o600)
            if args.scenario in ['inference','mixed']:
                output=root/'measured';stage_directories.append(output);output.mkdir()
                config=r.public_configuration(metadata,output,10,60,True,'normal',30)
                if args.scenario in ['inference','mixed']:
                    r.b.write(output/'background-preparation.json',preparation)
                if private:
                    config=mixed_configuration(config,private)
                    r.b.write(root/'rating-integrity-before.json',bridge.call('rating_integrity'))
                result=stage(bridge,output,config,'mixed' if private else 'baseline',users if private else None,
                             background=True)
                if private:
                    integrity=bridge.call('rating_integrity')
                    r.b.write(root/'rating-integrity-after.json',integrity)
                    require_integrity=json.loads((root/'rating-integrity-before.json').read_text())==integrity
                    r.b.write(root/'mixed-integrity.json',{'unchangedExperimentalRatings':require_integrity})
                    if not require_integrity: raise RuntimeError('Experimental ratings not restored by workload')
                r.b.write(root/'outcome.json',{'nativeFailure':result['nativeFailure'],'safetyAbort':result['safetyAbort'],
                    'clockStable':result['clockAudit']['stableDuringObservedWindow'],
                    'checksFailed':result['http']['checks']['fails']})
            else:
                baseline=None; levels=[]
                for rate in POLICY['publicRates']:
                    output=root/('rate-'+str(rate));stage_directories.append(output)
                    config=r.public_configuration(metadata,output,rate,60,True,'normal',30)
                    result=stage(bridge,output,config)
                    bad=degraded(result['http'],rate*60)
                    levels.append({'rate':rate,'degraded':bad,'clockStable':result['clockAudit']['stableDuringObservedWindow'],
                                   'safetyAbort':result['safetyAbort']})
                    if baseline is None: baseline=result['http']['http_req_duration']['p(95)']
                    if bad or result['nativeFailure'] or result['safetyAbort'] or not result['clockAudit']['stableDuringObservedWindow']: break
                if levels[-1]['safetyAbort']:
                    r.b.write(root/'stress-outcome.json',{'levels':levels,'recoveryPassed':False,
                        'recoveryBlocked':'critical safety stop; no further load','theoreticalMaximumSought':False})
                    return root
                output=root/'recovery';stage_directories.append(output)
                config=r.public_configuration(metadata,output,5,60,True,'normal',30)
                result=stage(bridge,output,config)
                recovered=(not result['safetyAbort'] and result['clockAudit']['stableDuringObservedWindow']
                    and result['http']['checks']['fails']==0 and result['http']['http_req_failed']['rate']==0
                    and result['http']['dropped_iterations']['count']==0
                    and result['http']['http_req_duration']['p(95)']<=max(500,2*baseline))
                r.b.write(root/'stress-outcome.json',{'levels':levels,'recoveryPassed':recovered,
                    'recoveryP95LimitMs':max(500,2*baseline),'theoreticalMaximumSought':False})
        return root
    finally:
        try:
            receipt=bridge.call('restore');r.b.write(root/'restoration.json',receipt)
            for directory in stage_directories:
                if (directory/'evidence.json').exists():
                    result=json.loads((directory/'evidence.json').read_text());result['restoration']=receipt
                    r.b.write(directory/'evidence.json',result)
                    if result.get('interference'):
                        result['interference']['restoration']=receipt
                        (directory/'interference-report.md').write_text(r.interference.markdown(result['interference']))
        except Exception as error:
            r.b.write(root/'recovery-required.json',{'errorType':type(error).__name__,'status':'restoration-failed'})
            raise
        finally: bridge.close()


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('scenario',choices=['inference','mixed','stress','provider'])
    parser.add_argument('--revision',required=True)
    parser.add_argument('--approved-maintenance-window',action='store_true')
    args=parser.parse_args()
    try: execute(args)
    except Exception as error:
        print(type(error).__name__+': '+(str(error) if isinstance(error,RuntimeError) else 'Private output withheld'))
        raise SystemExit(1)
