#!/usr/bin/env python3
"""Offline allowlisted completion evidence; no URLs, identities, text or credentials."""
import argparse
import hashlib
import json
from pathlib import Path

def read(path): return json.loads(path.read_text())
def counter(text): return {key:int(value) for key,value in (line.split() for line in text.splitlines())}
def meter(sample,name,statistic=None):
    return next((row['value'] for row in sample['metrics'].get(name,{}).get('measurements',[])
                 if statistic is None or row['statistic']==statistic),None)
def signals(directory):
    before,after=read(directory/'before.json'),read(directory/'after.json')
    observation=read(directory/'observation.json');samples=[before,*observation['samples'],after]
    result={'periodicSamples':len(observation['samples']),'snapshotBracketSeconds':after['monotonicSeconds']-before['monotonicSeconds'],
        'observationFailures':observation['failures'],'readinessAfter':after.get('readiness'),
        'containers':{},'jvm':{},'hikari':{},'postgresql':{},'physicalHost':{}}
    for name in ['application','postgres','catalogue-localizer']:
        b,a=before['cgroups'][name],after['cgroups'][name]
        cpu_b,cpu_a=counter(b['cpu.stat']),counter(a['cpu.stat'])
        mem_b,mem_a=counter(b['memory.events']),counter(a['memory.events'])
        def io(text):
            result={}
            for line in text.splitlines():
                for part in line.split()[1:]:
                    key,value=part.split('=');result[key]=result.get(key,0)+int(value)
            return result
        io_b,io_a=io(b['io.stat']),io(a['io.stat'])
        result['containers'][name]={'cpuSeconds':(cpu_a['usage_usec']-cpu_b['usage_usec'])/1e6,
            'cpuStatDelta':{k:cpu_a[k]-cpu_b.get(k,0) for k in cpu_a},
            'memoryEventsDelta':{k:mem_a[k]-mem_b.get(k,0) for k in mem_a},
            'sampledMemoryMaximumBytes':max(int(s['cgroups'][name]['memory.current']) for s in samples),
            'ioDelta':{k:io_a[k]-io_b.get(k,0) for k in io_a}}
    for key,name in [('heapUsed','jvm.memory.used'),('heapMax','jvm.memory.max'),('threads','jvm.threads.live')]:
        values=[v for s in samples if (v:=meter(s,name)) is not None]
        result['jvm'][key]={'maximumObserved':max(values),'after':meter(after,name)} if values else {'unavailable':True}
    result['jvm']['gcPauseTimeUnit']=after['metrics'].get('jvm.gc.pause',{}).get('baseUnit')
    for statistic in ['COUNT','TOTAL_TIME']:
        b,a=meter(before,'jvm.gc.pause',statistic),meter(after,'jvm.gc.pause',statistic)
        result['jvm']['gcPauseDelta'+statistic]=a-b if a is not None and b is not None else None
    for name in ['active','pending','max','timeout']:
        values=[v for s in samples if (v:=meter(s,'hikaricp.connections.'+name)) is not None]
        result['hikari'][name]={'maximumObserved':max(values),'after':meter(after,'hikaricp.connections.'+name),
            'delta':meter(after,'hikaricp.connections.'+name)-meter(before,'hikaricp.connections.'+name) if meter(after,'hikaricp.connections.'+name) is not None else None} if values and meter(before,'hikaricp.connections.'+name) is not None else {'unavailable':True}
    db_b,db_a=before['database'],after['database']
    for key in ['xact_commit','xact_rollback','blks_read','blks_hit','temp_files','temp_bytes','deadlocks']:
        result['postgresql'][key+'Delta']=db_a[key]-db_b[key]
    result['postgresql']['maximumUngrantedLocksObserved']=max(s['database']['ungranted_locks'] for s in samples)
    activity={}
    for sample in samples:
        for row in sample['database'].get('activity') or []:
            key=(row['state'],row['wait_event_type'],row['wait_event'])
            activity[key]=max(activity.get(key,0),row['count'])
    result['postgresql']['activityMaximumCounts']=[{'state':k[0],'waitEventType':k[1],'waitEvent':k[2],'maximumObserved':value} for k,value in activity.items()]
    for name in ['cpu','memory','io']:
        def pressure(sample):
            return {line.split()[0]:dict(part.split('=') for part in line.split()[1:])
                for line in sample['physicalHost']['pressure/'+name].splitlines()}
        b,a=pressure(before),pressure(after)
        result['physicalHost']['pressure_'+name]={key:{'totalDeltaMicroseconds':int(a[key]['total'])-int(b[key]['total']),
            'maximumSampledAvg10':max(float(pressure(s)[key]['avg10']) for s in samples)} for key in a}
    return result

def generator_resources(clocks):
    cpu=[c['cpuSeconds'] for c in clocks if c.get('cpuSeconds') is not None]
    memory=[c['workingSetBytes'] for c in clocks if c.get('workingSetBytes') is not None]
    return {'cpuSeconds':cpu[-1]-cpu[0] if cpu else None,
        'maximumWorkingSetBytes':max(memory) if memory else None,
        'resourceSamplesUnavailable':sum(c.get('cpuSeconds') is None or c.get('workingSetBytes') is None for c in clocks)}

def summarize(root):
    target=read(root/'target.json');manifest=read(root/'manifest.json');receipt=read(root/'restoration.json')
    safe_receipt={k:receipt.get(k) for k in ['originalSchedulingRestored','originalEnvironmentVerified','privateDataUnchanged',
        'dependenciesUnchanged','databaseRetainedForReview','unexpectedExperimentalRatings','syntheticUsersRemoved','readiness']}
    result={'run':root.name,'sutRevision':target['sourceRevision'],'sutImage':target['image'],
        'applicationVersion':target['applicationVersion'],'harnessManifest':{k:manifest[k] for k in ['sourceRevision','purpose','sutRevision','scriptsSha256']},'policy':read(root/'policy.json'),
        'restoration':safe_receipt,'stages':[]}
    result['modelRevision']=target['configuration']['localizer']['modelRevision']
    if (root/'profile.json').exists(): result['datasetCounts']=read(root/'profile.json')['counts']
    for path in sorted(root.glob('*/evidence.json')):
        directory=path.parent;e=read(path);config=read(directory/'workload.json')
        row={'stage':directory.name,'workloadProfile':config['workloadProfile'],'rate':None if config.get('userPool') else config['rate'],
             'durationSeconds':None if config.get('userPool') else config['durationSeconds'],
             'executorMaximumDuration':config.get('maxDuration'),
             'authenticatedUsers':len(config.get('userPool',[])),
             'iterationsPerUser':config.get('iterationsPerUser'),'publicIterations':config.get('publicIterations'),'http':e['http'],
             'cases':{name:{'requests':c['requests'],'checksFailed':c['checksFailed'],'statuses':c['statuses'],
                 'timeouts':c['timeouts'],'errors':c['errors'],
                 'p50p95p99Ms':[c['percentilesMs'].get(k) for k in ['med','p(95)','p(99)']]} for name,c in e['cases'].items()},'operations':e.get('operations'),
             'sessions':e.get('sessions'),'clockAudit':e['clockAudit'],'generatorExit':e['generatorExit'],
             'safetyAbort':e.get('safetyAbort'),'signals':signals(directory),
             'applicationLog':e.get('applicationLog'),'postgresLog':e.get('postgresLog'),
             'rawSha256':{name:hashlib.sha256((directory/name).read_bytes()).hexdigest() for name in
                ['evidence.json','before.json','after.json','observation.json','summary.json','metrics.json']}}
        clocks=read(directory/'generator-clock.json')
        row['generatorResources']=generator_resources(clocks)
        if e.get('interference'):
            i=e['interference']
            row['interference']={k:i.get(k) for k in ['syncDurationSeconds','conservativeOverlapSeconds',
                'serverSampleCountDuringSync','localizationDelta','successfulWorkVerified','reuseVerified','inferenceVerified',
                'overlapSufficient','interferenceObservationUsable','httpRecoveryAfterSyncObserved','periods','attributionAllowed','limitations']}
            row['syncCounters']=i['sync']['report']['counters']
            row['localizer']=i['sync'].get('localizer')
        result['stages'].append(row)
    for name in ['provider-calibration','mixed-integrity','stress-outcome','outcome']:
        if (root/(name+'.json')).exists(): result[name]=read(root/(name+'.json'))
    if (root/'background-preparation.json').exists():
        prep=read(root/'background-preparation.json')
        result['preparation']={k:prep[k] for k in ['scenario','games','releases','inputSha256']}
        result['calibrationSeconds']=prep['calibration']['syncDurationSeconds']
    return result

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('run',type=Path);parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args();args.output.write_text(json.dumps(summarize(args.run),indent=2)+'\n')
