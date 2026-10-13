#!/usr/bin/env python3
"""Private-dev adapter for the existing harness. JSON RPC over owner SSH; no listener."""
import argparse
import fcntl
import hashlib
import importlib.machinery
import importlib.util
import json
import os
from pathlib import Path
import secrets
import select
import subprocess
import sys
import threading
import time
import uuid
import urllib.parse

ROOT = Path(__file__).resolve().parents[2]
TOOL = Path(__file__).resolve().parent
COMPOSE = '/opt/videogame-platform/deploy/private-dev/compose.yaml'
ENV = '/etc/videogame-platform/dev/runtime.env'
PROJECT = 'videogame-platform-dev'
DATABASE = 'videogame_performance'
PURPOSE = 'disposable-performance-only'
REQUIRED = ['application', 'postgres', 'keycloak', 'catalogue-localizer', 'telemetry', 'prometheus', 'grafana', 'alloy', 'loki']


def require(ok, message):
    if not ok:
        raise RuntimeError(message)


def command(*args, input=None, timeout=300):
    result = subprocess.run(args, input=input, capture_output=True, text=True, timeout=timeout)
    require(result.returncode == 0, 'Host command failed (private command output withheld)')
    return result.stdout.strip()


def atomic(path, value):
    temporary = path.with_suffix('.tmp')
    temporary.write_text(json.dumps(value, indent=2)+'\n')
    os.chmod(temporary, 0o600)
    temporary.replace(path)


def service(name, include_stopped=False):
    ids = command('docker', 'ps', *(['--all'] if include_stopped else []), '--no-trunc', '--filter', 'label=com.docker.compose.project='+PROJECT,
                  '--filter', 'label=com.docker.compose.service='+name, '--format', '{{.ID}}').splitlines()
    require(len(ids) == 1, 'Expected one running private-dev service')
    return json.loads(command('docker', 'inspect', ids[0]))[0]


def settings(container):
    return dict(row.split('=', 1) for row in container['Config']['Env'] if '=' in row)


def configuration():
    """Allowlisted non-secret configuration; executable sources own absent defaults."""
    app=service('application'); env=settings(app)
    names=['APPLICATION_DB_MAXIMUM_POOL_SIZE','APPLICATION_DB_CONNECTION_TIMEOUT',
           'APPLICATION_DB_VALIDATION_TIMEOUT','CATALOGUE_SYNC_SCHEDULING_ENABLED',
           'CATALOGUE_SYNC_NEAR_TERM_CRON','CATALOGUE_SYNC_UPCOMING_CRON']
    pg=service('postgres'); localizer=service('catalogue-localizer')
    pgsettings=json.loads(command('docker','exec','--user','postgres',pg['Id'],'psql','-X','-qAt','-U','postgres',
        '-d','postgres','-c',"SELECT json_object_agg(name,json_build_object('setting',setting,'unit',unit,'source',source)) FROM pg_settings WHERE name IN ('server_version','max_connections','shared_buffers','work_mem','maintenance_work_mem','effective_cache_size','max_wal_size','checkpoint_timeout','track_io_timing');"))
    return {'applicationEnvironment':{name:env.get(name) for name in names},
        'jvmOverridePresent':{name:bool(env.get(name)) for name in ['JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','JAVA_OPTS']},
        'javaVersion':command('docker','exec',app['Id'],'java','--version'),
        'postgresql':pgsettings,
        'localizer':{'imageId':localizer['Image'],'command':localizer['Config']['Cmd'],
                     'modelRevision':command('docker','exec',localizer['Id'],'cat','/model/revision.txt')},
        'services':{name:{'running':(row:=service(name))['State']['Running'],
                         'health':row['State'].get('Health',{}).get('Status','no-healthcheck'),
                         'memoryBytes':row['HostConfig']['Memory'],'nanoCpus':row['HostConfig']['NanoCpus'],
                         'pidsLimit':row['HostConfig']['PidsLimit']} for name in REQUIRED}}


def schema_hash():
    return hashlib.sha256(b''.join(p.read_bytes() for p in sorted((ROOT/'backend/src/main/resources/db/migration').glob('V*.sql')))).hexdigest()


def admin():
    path = ROOT/'deploy/private-dev/bin/provision-oidc-smoke-user'
    loader = importlib.machinery.SourceFileLoader('remote_identity', str(path))
    spec = importlib.util.spec_from_loader(loader.name, loader)
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    values = module.parse_runtime_environment(Path(ENV))
    client = module.KeycloakAdmin('http://127.0.0.1:8180')
    # The approved management-only seam reads protected inputs internally. None leave the host.
    client.authenticate(values['KEYCLOAK_ADMIN_USERNAME'], module.read_secret(Path(values['PRIVATE_DEV_SECRETS_DIR']), 'keycloak-admin-password'))
    profile = client.user_profile()
    require([a for a in profile['attributes'] if a.get('name') == module.MARKER_ATTRIBUTE] == [module.MARKER_PROFILE_ATTRIBUTE], 'Existing admin-only synthetic ownership marker is required')
    return client


class Experiment:
    def __init__(self, directory):
        self.directory = Path(directory).resolve()
        require(self.directory.parent == Path('/tmp') and self.directory.name.startswith('vgp-performance-85-'), 'Invalid owned experiment directory')
        require(self.directory.stat().st_uid == os.getuid() and not self.directory.is_symlink(), 'Experiment ownership mismatch')
        self.receipt = self.directory/'receipt.json'
        self.state = json.loads(self.receipt.read_text()) if self.receipt.exists() else {'phase':'new', 'users':[], 'databaseOid':None, 'candidateId':None}
        self.stop = threading.Event()
        self.samples, self.clocks, self.failures, self.threads = [], [], [], []

    def save(self):
        atomic(self.receipt, self.state)

    def sql(self, sql, database='videogame_platform', *variables, timeout=300):
        pg = service('postgres')
        require(not self.state.get('postgresId') or pg['Id'] == self.state['postgresId'], 'PostgreSQL identity changed')
        return command('docker','exec','-i','--user','postgres',pg['Id'],'psql','-X','-qAt','-v','ON_ERROR_STOP=1',
                       '-U','postgres','-d',database,*variables,input=sql,timeout=timeout)

    def private_counts(self):
        return json.loads(self.sql("""BEGIN READ ONLY; SET LOCAL statement_timeout='5s';
SELECT json_build_object('games',(SELECT count(*) FROM catalogue.game_snapshot),
'releases',(SELECT count(*) FROM catalogue.release_snapshot),'ratings',(SELECT count(*) FROM ratings.rating),
'syncRuns',(SELECT count(*) FROM catalogue.synchronization_run),'runningSyncs',(SELECT count(*) FROM catalogue.synchronization_run WHERE run_status='running'),
'ratingsFingerprint',(SELECT md5(count(*)::text||':'||coalesce(sum(('x'||substr(md5(r::text),1,16))::bit(64)::bigint::numeric),0)::text||':'||coalesce(sum(('x'||substr(md5(r::text),17,16))::bit(64)::bigint::numeric),0)::text) FROM ratings.rating r));
COMMIT;"""))

    def preflight(self, revision):
        require(command('hostname','--short') == 'vgpdev', 'Wrong target host')
        rows = {name:service(name) for name in REQUIRED}
        app, pg = rows['application'], rows['postgres']
        s = settings(app)
        require(app['Config']['Labels'].get('org.opencontainers.image.revision') == revision, 'Deployed revision mismatch')
        require(self.actuator('info')['build']['sourceRevision']==revision,'Runtime build identity differs from OCI label')
        require(s.get('APPLICATION_DB_URL') == 'jdbc:postgresql://postgres:5432/videogame_platform' and s.get('APPLICATION_FLYWAY_ENABLED') == 'false', 'Unexpected persistent database or Flyway boundary')
        require(app['Config']['Labels']['com.docker.compose.project.config_files'] == COMPOSE, 'Unexpected active application overlay')
        require(all(not r['State'].get('Health') or r['State']['Health']['Status']=='healthy' for r in rows.values()), 'A required service is unhealthy')
        require(rows['catalogue-localizer']['State'].get('Health',{}).get('Status')=='healthy', 'Real localizer must be healthy')
        origin=urllib.parse.urlsplit(s['APPLICATION_PUBLIC_ORIGIN'])
        issuer=urllib.parse.urlsplit(s['OIDC_ISSUER_URI'])
        require(origin.scheme=='https' and issuer.scheme=='https' and origin.hostname==issuer.hostname
                and origin.hostname.startswith('vgpdev.') and origin.hostname.endswith('.ts.net')
                and origin.port is None and issuer.port==8443 and not origin.path and not origin.query
                and not origin.username and not issuer.username and issuer.path=='/realms/videogame-platform',
                'Real registered private TLS origins/issuer are required')
        require(s['APPLICATION_SESSION_COOKIE_NAME']=='__Host-vgp_session' and s['APPLICATION_SESSION_COOKIE_SECURE']=='true', 'Secure host session cookie must remain configured')
        require(self.sql("SELECT count(*) FROM pg_database WHERE datname='videogame_performance';", 'postgres') == '0', 'Existing experiment database requires separate recovery')
        installed = Path('/opt/videogame-platform/backend/src/main/resources/db/migration')
        require(schema_hash() == hashlib.sha256(b''.join(p.read_bytes() for p in sorted(installed.glob('V*.sql')))).hexdigest(), 'Installed and experiment migration SQL differ')
        version = app['Config']['Labels']['org.opencontainers.image.version']
        image = app['Config']['Image']
        require(image.startswith('ghcr.io/rubhern/videogame-platform@sha256:'), 'Immutable application digest required')
        self.state.update(phase='inspected', sourceRevision=revision, image=image, applicationVersion=version,
            postgresId=pg['Id'], originalApplicationId=app['Id'], schemaChecksum=schema_hash(),
            runtimeSha256=hashlib.sha256(Path(ENV).read_bytes()).hexdigest(),
            origin=s['APPLICATION_PUBLIC_ORIGIN'], keycloakOrigin=s['OIDC_ISSUER_URI'].split('/realms/')[0],
            cookieName=s['APPLICATION_SESSION_COOKIE_NAME'], originalScheduling=s['CATALOGUE_SYNC_SCHEDULING_ENABLED'],
            dependencies={name:{'id':r['Id'],'imageId':r['Image'],'memory':r['HostConfig']['Memory'],'cpu':r['HostConfig']['NanoCpus'],'configHash':r['Config']['Labels'].get('com.docker.compose.config-hash')} for name,r in rows.items() if name!='application'},
            applicationLimits={'memory':app['HostConfig']['Memory'],'cpu':app['HostConfig']['NanoCpus']})
        self.state['physicalHost']={'kernel':command('uname','-r'),'os':Path('/etc/os-release').read_text(),
            'nproc':command('nproc'),'cpu':command('lscpu'),'memory':command('free','-b'),
            'filesystem':command('df','-B1','-T','/'),
            'docker':command('docker','--version'),'compose':command('docker','compose','version'),
            'timeSync':command('timedatectl','show','-p','NTPSynchronized','-p','NTP','-p','Timezone')}
        self.state['configuration'] = configuration()
        self.state['privateBefore'] = self.private_counts()
        require(self.state['privateBefore']['runningSyncs']==0, 'An existing synchronization is active')
        self.save()
        return {k:self.state[k] for k in ['sourceRevision','image','applicationVersion','origin','keycloakOrigin','cookieName','originalScheduling','dependencies','applicationLimits','privateBefore','schemaChecksum','physicalHost','configuration']}

    def environment(self):
        return {**os.environ,'APPLICATION_IMAGE':self.state['image'],'APPLICATION_VERSION':self.state['applicationVersion'],
                'SOURCE_REVISION':self.state['sourceRevision']}

    def compose(self, active):
        require(hashlib.sha256(Path(ENV).read_bytes()).hexdigest()==self.state['runtimeSha256'], 'Protected runtime changed; refusing recreation')
        args=['docker','compose','--env-file',ENV,'--file',COMPOSE,'--profile','application']
        if active: args+=['--file',str(self.directory/'override.json')]
        result=subprocess.run(args+['up','--detach','--no-deps','--force-recreate','--wait','--wait-timeout','180','application'],
                              env=self.environment(),text=True,capture_output=True,timeout=210)
        require(result.returncode==0,'Application recreation/readiness failed (private output withheld)')

    def boundary(self):
        require(self.state.get('databaseOid') is not None, 'Experiment has no owned database')
        oid=self.sql("SELECT oid FROM pg_database WHERE datname='videogame_performance';",'postgres')
        require(oid==str(self.state['databaseOid']), 'Experiment database OID differs; refusing mutation')
        marker=json.loads(self.sql('SELECT row_to_json(b) FROM performance_data.boundary b;',DATABASE))
        require(marker['purpose']==PURPOSE and marker['schema_checksum']==self.state['schemaChecksum'], 'Disposable marker/schema mismatch')

    def prepare(self, date):
        require(self.state['phase']=='inspected' and service('application')['Id']==self.state['originalApplicationId'], 'Stale preflight')
        require(self.private_counts()==self.state['privateBefore'], 'Private data/activity changed since preflight')
        # Validate the approved management seam before spending shared database resources.
        admin()
        data=self.sql('SHOW data_directory;','postgres')
        available=int(command('docker','exec',service('postgres')['Id'],'df','-Pk',data).splitlines()[-1].split()[3])*1024
        require(available>=4*1024**3,'Insufficient verified PostgreSQL filesystem headroom')
        # No volumes or roles are created/replaced; no schema is copied from personal data.
        self.sql('CREATE DATABASE videogame_performance OWNER postgres;', 'postgres')
        self.state['databaseOid']=int(self.sql("SELECT oid FROM pg_database WHERE datname='videogame_performance';",'postgres'))
        self.state['phase']='database-created'; self.save()
        migrations='\n'.join(p.read_text() for p in sorted((ROOT/'backend/src/main/resources/db/migration').glob('V*.sql')))
        self.sql("BEGIN; REVOKE ALL ON DATABASE videogame_performance FROM PUBLIC; GRANT CONNECT ON DATABASE videogame_performance TO videogame_app; REVOKE ALL ON SCHEMA public FROM PUBLIC; GRANT USAGE ON SCHEMA public TO videogame_app;\n"+migrations+"\nCREATE SCHEMA performance_data; REVOKE ALL ON SCHEMA performance_data FROM PUBLIC; CREATE TABLE performance_data.boundary(purpose text PRIMARY KEY, schema_checksum text NOT NULL, profile text, reference_date date); INSERT INTO performance_data.boundary VALUES ('disposable-performance-only', :'schema_checksum',NULL,NULL); COMMIT;",DATABASE,'-v','schema_checksum='+self.state['schemaChecksum'])
        self.boundary()
        self.sql((TOOL/'generate.sql').read_text(),DATABASE,'-v','profile=growth','-v','as_of='+date,'-v','schema_checksum='+self.state['schemaChecksum'])
        self.sql('CREATE TABLE performance_data.synthetic_owner(user_id uuid PRIMARY KEY); INSERT INTO performance_data.synthetic_owner SELECT DISTINCT user_id FROM ratings.rating;',DATABASE)
        profile=json.loads(self.sql((TOOL/'profile.sql').read_text(),DATABASE,'-v','profile=growth'))
        require(profile['counts']['games']==50000 and profile['counts']['releases']==200000 and profile['counts']['ratings']==500000,'Growth cardinality mismatch')
        self.state.update(phase='prepared',referenceDate=date,profile=profile); self.save()
        return profile

    def users(self, specs):
        require(self.state['phase']=='prepared' and len(specs)==2,'The smoke owns exactly two independent users')
        client=admin(); private=[]
        for i,user in enumerate(specs):
            username='performance-85-player-'+str(i+1)
            require(not client.find_users(username),'Existing synthetic username is never adopted or overwritten')
            email=(username+'-'+self.directory.name.removeprefix('vgp-performance-85-')+'@example.test').lower()
            password=secrets.token_urlsafe(24)
            _,headers=client._request('POST','/admin/realms/videogame-platform/users',body={
                'username':username,'email':email,'firstName':'Synthetic','lastName':'Performance85','enabled':True,
                'requiredActions':[],'attributes':{'vgpDeploymentSmoke':['true']},
                'credentials':[{'type':'password','value':password,'temporary':False}]},expected={201})
            # Persist the returned immutable identity before any follow-up verification.
            location=urllib.parse.urlsplit(headers.get('Location','')).path
            prefix='/admin/realms/videogame-platform/users/'
            require(location.startswith(prefix),'Synthetic creation omitted its management identity')
            identity=location.removeprefix(prefix)
            require(str(uuid.UUID(identity))==identity,'Synthetic creation returned an invalid identity')
            record={'id':identity,'username':username,'email':email}
            self.state['users'].append(record); self.save()
            found=client.find_users(username)
            require(len(found)==1 and found[0]['id']==identity and found[0].get('email')==email,'New synthetic identity is ambiguous')
            require(self.sql("SELECT count(*) FROM ratings.rating WHERE user_id=:'id'::uuid;",DATABASE,'-v','id='+record['id'])=='0','Synthetic principal unexpectedly owns initial ratings')
            self.sql("INSERT INTO performance_data.synthetic_owner VALUES (:'id'::uuid);",DATABASE,'-v','id='+record['id'])
            private.append({**user,'username':username,'password':password})
        return private  # Transient SSH handoff only; never written to receipt/evidence.

    def activate(self):
        self.boundary()
        require(self.state['phase']=='prepared' and service('application')['Id']==self.state['originalApplicationId'],'Activation boundary changed')
        require(self.private_counts()==self.state['privateBefore'],'Private activity/synchronization changed during preparation; refusing activation')
        override={'services':{'application':{'environment':{
            'APPLICATION_DB_URL':'jdbc:postgresql://postgres:5432/videogame_performance',
            'APPLICATION_FLYWAY_ENABLED':'false','CATALOGUE_SYNC_SCHEDULING_ENABLED':'false',
            'IGDB_CLIENT_ID_FILE':'/dev/null','IGDB_CLIENT_SECRET_FILE':'/dev/null',
            'IGDB_TOKEN_URI':'http://127.0.0.1:9/disabled','IGDB_API_BASE_URI':'http://127.0.0.1:9/disabled/'}}}}
        atomic(self.directory/'override.json',override)
        self.state['phase']='activating'; self.save()
        try: self.compose(True)
        finally:
            app=service('application',include_stopped=True)
            if settings(app).get('APPLICATION_DB_URL')=='jdbc:postgresql://postgres:5432/videogame_performance':
                self.state['candidateId']=app['Id']; self.save()
        app=service('application')
        require(settings(app).get('CATALOGUE_SYNC_SCHEDULING_ENABLED')=='false','Scheduling was not disabled')
        require({'memory':app['HostConfig']['Memory'],'cpu':app['HostConfig']['NanoCpus']}==self.state['applicationLimits'],'Limits drifted')
        require(app['Image']==json.loads(command('docker','image','inspect',self.state['image']))[0]['Id'],'Image drifted')
        self.state['phase']='active'; self.save()
        return {'readiness':self.actuator('health/readiness'),'candidateId':app['Id'],'scheduling':False}

    def background_prepare(self, scenario):
        """Prepare the existing two-game fixture/checkpoint; invoked only by a later explicit scenario."""
        require(scenario in ['serving-sync-reuse','serving-sync-inference'] and self.state['phase']=='active','Unsupported background scenario')
        self.boundary()
        from provider_fixture import build_input
        fixture=build_input(ROOT,2,self.state['referenceDate'])
        atomic(self.directory/'provider-input.json',fixture)
        os.chmod(self.directory/'provider-input.json',0o444)
        provider='vgp-performance-85-provider-'+self.directory.name.removeprefix('vgp-performance-85-')
        require(not command('docker','ps','-a','--filter','name=^/'+provider+'$','--format','{{.ID}}'),'Existing fixture actor is not adopted')
        image=self.state['dependencies']['catalogue-localizer']['imageId']
        identity=command('docker','run','--detach','--rm','--name',provider,'--network','videogame-platform-dev_data',
            '--label','videogame-platform.performance-owner='+self.directory.name,'--read-only','--cap-drop','ALL',
            '--security-opt','no-new-privileges:true','--memory','128m','--cpus','.25',
            '--mount',f'type=bind,source={TOOL / "provider_fixture.py"},target=/fixture.py,readonly',
            '--mount',f'type=bind,source={self.directory / "provider-input.json"},target=/input.json,readonly',
            '--entrypoint','python',image,'/fixture.py','/input.json')
        self.state['providerId']=identity; self.state['providerName']=provider; self.save()
        override=json.loads((self.directory/'override.json').read_text())
        app=override['services']['application']; env=app['environment']
        app['volumes']=[]
        for key,file in [('IGDB_CLIENT_ID_FILE','fixture-client-id'),('IGDB_CLIENT_SECRET_FILE','fixture-client-secret')]:
            path=self.directory/file; path.write_text('fixture-only'); os.chmod(path,0o444)
            destination='/performance/'+file
            app['volumes'].append({'type':'bind','source':str(path),'target':destination,'read_only':True})
            env[key]=destination
        env['IGDB_TOKEN_URI']='http://'+provider+':8093/oauth2/token'
        env['IGDB_API_BASE_URI']='http://'+provider+':8093/v4/'
        atomic(self.directory/'override.json',override)
        self.recreate_experiment()
        calibration=self.background_sync()
        require(calibration['report']['outcome']=='SUCCEEDED','Bounded fixture warm-up failed')
        ids=[str(game['id']) for game in fixture['games']]
        self.sql("CREATE TABLE performance_data.interference_game(provider_id text PRIMARY KEY); INSERT INTO performance_data.interference_game SELECT unnest(:'ids'::text[]); CREATE TABLE performance_data.interference_fingerprint(fingerprint varchar(64) PRIMARY KEY); INSERT INTO performance_data.interference_fingerprint SELECT catalogue.spanish_source_fingerprint(g.summary_text) FROM catalogue.game_snapshot g JOIN catalogue.game_external_reference r USING(game_id) JOIN performance_data.interference_game i ON i.provider_id=r.provider_id WHERE r.provider='IGDB' UNION SELECT catalogue.spanish_source_fingerprint('Tactical exploration') UNION SELECT catalogue.spanish_source_fingerprint('Cooperative missions');",DATABASE,'-v','ids='+('{'+','.join(ids)+'}'))
        # Reuse the existing guarded state reset, preserving the real helper process/model.
        self.sql((TOOL/'interference-state.sql').read_text(),DATABASE,'-v','schema_checksum='+self.state['schemaChecksum'],'-v','scenario='+scenario)
        self.state['background']={'scenario':scenario,'games':2,'releases':4,'inputSha256':fixture['payloadSha256'],'externalProviderTimingIncluded':False,'calibration':calibration}
        self.save()
        return self.state['background']

    def maintenance(self):
        """Autovacuum/vacuum activity and the vacuum/analyze debt of the experiment's largest tables."""
        return json.loads(self.sql("""BEGIN READ ONLY; SET LOCAL statement_timeout='5s';
SELECT json_build_object('at',clock_timestamp(),
'autovacuumWorkers',(SELECT count(*) FROM pg_stat_activity WHERE backend_type='autovacuum worker' AND datname=current_database()),
'autovacuumWorkersOtherDatabases',(SELECT count(*) FROM pg_stat_activity WHERE backend_type='autovacuum worker' AND datname IS DISTINCT FROM current_database()),
'vacuumProgress',(SELECT count(*) FROM pg_stat_progress_vacuum WHERE datname=current_database()),
'tables',(SELECT json_agg(t ORDER BY t.relation) FROM (SELECT schemaname||'.'||relname AS relation,n_live_tup,n_dead_tup,
  n_ins_since_vacuum,n_mod_since_analyze,last_vacuum IS NOT NULL OR last_autovacuum IS NOT NULL AS vacuumed,
  last_analyze IS NOT NULL OR last_autoanalyze IS NOT NULL AS analyzed,autovacuum_count,autoanalyze_count
  FROM pg_stat_user_tables ORDER BY pg_total_relation_size(relid) DESC LIMIT 8) t));
COMMIT;""",DATABASE))

    def settle(self, quiet_seconds=30, deadline_seconds=300):
        """Reproducible post-generation settle: explicit VACUUM (ANALYZE), then a bounded quiet window."""
        require(self.state['phase']=='active','Settling requires the active experiment')
        self.boundary()
        before=self.maintenance()
        started=time.monotonic()
        # Only the owned disposable database; replaces the insert-triggered autovacuum backlog of fresh fixtures.
        self.sql("SET statement_timeout='600s'; VACUUM (ANALYZE);",DATABASE,timeout=660)
        vacuum_seconds=time.monotonic()-started
        polls=[]; quiet_since=None; deadline=time.monotonic()+deadline_seconds
        while True:
            state=self.maintenance(); polls.append({k:state[k] for k in ['at','autovacuumWorkers','autovacuumWorkersOtherDatabases','vacuumProgress']})
            busy=state['autovacuumWorkers'] or state['vacuumProgress']
            quiet_since=None if busy else (quiet_since or time.monotonic())
            if quiet_since and time.monotonic()-quiet_since>=quiet_seconds: break
            require(time.monotonic()<deadline,'Experiment database did not settle; refusing to measure')
            time.sleep(2)
        self.boundary()
        return {'method':'VACUUM (ANALYZE) of the experiment database, then no autovacuum/vacuum progress there for the quiet window',
                'quietSeconds':quiet_seconds,'deadlineSeconds':deadline_seconds,'vacuumSeconds':round(vacuum_seconds,3),
                'settleSeconds':round(time.monotonic()-started,3),'before':before,'after':self.maintenance(),'polls':polls}

    def recreate_experiment(self):
        require(service('application')['Id']==self.state['candidateId'],'Experiment application changed')
        try: self.compose(True)
        finally:
            app=service('application',include_stopped=True)
            if settings(app).get('APPLICATION_DB_URL')=='jdbc:postgresql://postgres:5432/videogame_performance':
                self.state['candidateId']=app['Id']; self.save()

    def background_sync(self):
        self.boundary()
        require(self.state.get('providerId') and settings(service('application'))['IGDB_API_BASE_URI']=='http://'+self.state['providerName']+':8093/v4/','No verified fixture-only provider is configured')
        body=json.dumps({'from':self.state['referenceDate'],'to':self.state['referenceDate']})
        start={'wallSeconds':time.time(),'monotonicSeconds':time.monotonic()}
        result=json.loads(command('docker','exec',service('application')['Id'],'wget','-q','-O','-',
            '--header','Content-Type: application/json','--post-data',body,'http://127.0.0.1:8081/actuator/cataloguesync',timeout=120))
        return {'started':start,'finished':{'wallSeconds':time.time(),'monotonicSeconds':time.monotonic()},'report':result}

    def actuator(self,path):
        return json.loads(command('docker','exec',service('application')['Id'],'wget','-q','-O','-', 'http://127.0.0.1:8081/actuator/'+path,timeout=15))

    def snapshot(self):
        result={'wallSeconds':time.time(),'monotonicSeconds':time.monotonic(),'metrics':{}}
        for meter in ['http.server.requests','jvm.memory.used','jvm.memory.max','jvm.gc.pause','jvm.threads.live','process.cpu.usage','hikaricp.connections.active','hikaricp.connections.pending','hikaricp.connections.max','hikaricp.connections.timeout','catalogue.localization.content']:
            try: result['metrics'][meter]=self.actuator('metrics/'+meter+('?tag=area:heap' if meter.startswith('jvm.memory.') else ''))
            except Exception: result['metrics'][meter]={'unavailable':True}
        db=DATABASE if self.state['phase']=='active' else 'videogame_platform'
        result['database']=json.loads(self.sql("""BEGIN READ ONLY; SET LOCAL statement_timeout='3s';
SELECT row_to_json(d) FROM (SELECT clock_timestamp() AS at,numbackends,xact_commit,xact_rollback,blks_read,blks_hit,temp_files,temp_bytes,deadlocks,stats_reset,
(SELECT json_agg(a) FROM (SELECT state,wait_event_type,wait_event,count(*) FROM pg_stat_activity WHERE datname=current_database() GROUP BY state,wait_event_type,wait_event) a) AS activity,
(SELECT count(*) FROM pg_locks WHERE NOT granted) AS ungranted_locks FROM pg_stat_database WHERE datname=current_database()) d; COMMIT;""",db))
        result['containers']=command('docker','stats','--no-stream','--format','{{json .}}',*[service(n)['Id'] for n in ['application','postgres','keycloak','catalogue-localizer']],timeout=15).splitlines()
        result['physicalHost']={p:Path('/proc/'+p).read_text() for p in ['stat','meminfo','diskstats','pressure/cpu','pressure/memory','pressure/io']}
        result['cgroups']={}
        for name in ['application','postgres','catalogue-localizer']:
            result['cgroups'][name]={p:command('docker','exec',service(name)['Id'],'cat','/sys/fs/cgroup/'+p,timeout=10) for p in ['cpu.stat','memory.current','memory.events','io.stat']}
        return result

    def logs(self, name, since):
        """Raw container output stays on the host; only filtered fields are returned."""
        result=subprocess.run(['docker','logs','--since','%.3f'%since,'--until','%.3f'%time.time(),service(name)['Id']],
                              capture_output=True,text=True,timeout=60)
        require(result.returncode==0,'Container log capture failed (private output withheld)')
        return (result.stdout+result.stderr).splitlines()

    def application_log(self, since):
        return filter_application_log(self.logs('application',since))

    def postgres_log(self, since):
        # The server is shared with private-dev: count patterns only, never retain statement text.
        lines=self.logs('postgres',since)
        return {k:sum(pattern in line for line in lines) for k,pattern in
                [('statementCancellations','canceling statement due to'),('temporaryFileLines','temporary file:'),
                 ('errorLines','ERROR:'),('fatalLines','FATAL:')]}

    def start_observation(self):
        require(not self.threads,'Observation already started')
        self.observation_since=time.time()
        before=self.snapshot()
        def clock():
            while not self.stop.is_set():
                self.clocks.append({'wallSeconds':time.time(),'monotonicSeconds':time.monotonic()})
                self.stop.wait(.25)
        def samples():
            while not self.stop.is_set():
                try: self.samples.append(self.snapshot())
                except Exception: self.failures.append({'at':time.time(),'unavailable':True})
                self.stop.wait(5)
        self.threads=[threading.Thread(target=clock),threading.Thread(target=samples)]
        for thread in self.threads: thread.start()
        return before

    def stop_observation(self):
        self.stop.set()
        for thread in self.threads: thread.join(timeout=90)
        require(all(not thread.is_alive() for thread in self.threads),'Observation did not stop')
        result={'samples':self.samples,'clocks':self.clocks,'failures':self.failures,'after':self.snapshot()}
        # Before restore recreates the application container and discards its log.
        for key,capture in [('applicationLog',self.application_log),('postgresLog',self.postgres_log)]:
            try: result[key]=capture(self.observation_since)
            except Exception: result[key]={'unavailable':True}
        return result

    def restore(self):
        if self.state['phase']=='restored':
            require(not self.state['restoration']['databaseRetainedForReview'],'Experiment database retained for owner review')
            require(self.state['restoration']['privateDataUnchanged'],'Private data changed; owner review is required')
            require(self.state['restoration'].get('dependenciesUnchanged',True),'Dependency configuration changed; owner review is required')
            return self.state['restoration']
        if self.threads and not self.stop.is_set(): self.stop_observation()
        unexpected=self.state.get('unexpectedExperimentalRatings',0)
        if self.state.get('databaseOid'):
            oid=self.sql("SELECT oid FROM pg_database WHERE datname='videogame_performance';",'postgres')
            require(not oid or oid==str(self.state['databaseOid']),'Database belongs to another run; refusing cleanup')
            if not oid:
                self.state['databaseOid']=None; self.save()
            # Preserve the database if another real principal wrote during the window.
            if self.state.get('databaseOid') and self.state['phase'] in ['prepared','active','activating']:
                unexpected=int(self.sql('SELECT count(*) FROM ratings.rating r WHERE NOT EXISTS (SELECT FROM performance_data.synthetic_owner s WHERE s.user_id=r.user_id);',DATABASE))
                self.state['unexpectedExperimentalRatings']=unexpected; self.save()
        # Restore product availability before attempting management-only account cleanup.
        if self.state.get('candidateId'):
            require(service('application',include_stopped=True)['Id']==self.state['candidateId'],'Another application replaced the experiment; refusing recreation')
            self.compose(False)
            require(settings(service('application')).get('APPLICATION_DB_URL')=='jdbc:postgresql://postgres:5432/videogame_platform','Private database connection was not restored')
            require(settings(service('application')).get('CATALOGUE_SYNC_SCHEDULING_ENABLED')==self.state['originalScheduling'],'Original scheduling was not restored')
            self.state['candidateId']=None; self.state['phase']='private-restored'; self.save()
        if self.state['users']:
            client=admin()
            for user in self.state['users']:
                found=client.find_users(user['username'])
                if not found: continue
                require(len(found)==1 and found[0]['id']==user['id'] and found[0].get('email')==user['email'] and found[0].get('attributes',{}).get('vgpDeploymentSmoke')==['true'],'Synthetic identity ownership mismatch')
                client._request('DELETE','/admin/realms/videogame-platform/users/'+user['id'],expected={204})
                require(not client.find_users(user['username']),'Synthetic identity remains')
        if self.state.get('databaseOid') and not unexpected:
            # No FORCE: connections owned by anyone else block deletion.
            self.sql('DROP DATABASE videogame_performance;', 'postgres')
            self.state['databaseOid']=None; self.save()
        if self.state.get('providerId'):
            present=command('docker','ps','-a','--no-trunc','--filter','id='+self.state['providerId'],'--format','{{.ID}}')
            if present:
                provider=json.loads(command('docker','inspect',self.state['providerId']))[0]
                require(provider['Config']['Labels'].get('videogame-platform.performance-owner')==self.directory.name and provider['HostConfig']['AutoRemove'],'Fixture actor ownership mismatch')
                command('docker','rm','--force',self.state['providerId'])
            self.state['providerId']=None; self.save()
        after=self.private_counts() if self.state.get('privateBefore') else None
        retained=bool(unexpected)
        receipt={'privateDataUnchanged':after==self.state.get('privateBefore'),'privateAfter':after,'unexpectedExperimentalRatings':unexpected,'databaseRetainedForReview':retained,
                 'syntheticUsersRemoved':len(self.state['users']),'readiness':self.actuator('health/readiness')}
        if self.state.get('dependencies'):
            receipt['dependenciesUnchanged']=all((row:=service(n))['Id']==r['id'] and row['Image']==r['imageId']
                and row['HostConfig']['Memory']==r['memory'] and row['HostConfig']['NanoCpus']==r['cpu']
                and row['Config']['Labels'].get('com.docker.compose.config-hash')==r['configHash']
                for n,r in self.state['dependencies'].items())
        self.state['phase']='restored'; self.state['restoration']=receipt; self.save()
        require(not retained,'Unexpected principal activity: experiment database retained for owner review')
        require(receipt['privateDataUnchanged'],'Private data changed; inspect the retained aggregate receipt')
        require(receipt.get('dependenciesUnchanged',True),'Dependency configuration changed; owner review is required')
        return receipt


LOG_FIELDS = ['@timestamp','log.level','log.logger','correlationId','trace.id','span.id',
              'error.code','error.type','error.root_cause_type','error.sql_state']


def flatten(value, prefix=''):
    if not isinstance(value, dict): return {prefix[:-1]: value}
    return {k: v for key, item in value.items() for k, v in flatten(item, prefix+key+'.').items()}


def filter_application_log(lines):
    """Keep WARN/ERROR ECS events with allowlisted fields; free text only from the redacting API handler."""
    levels, events, unparsed = {}, [], 0
    for line in lines:
        try: event = flatten(json.loads(line))
        except (ValueError, AttributeError): unparsed += 1; continue
        if not isinstance(event, dict) or 'log.level' not in event: unparsed += 1; continue
        level = str(event['log.level']).upper()
        levels[level] = levels.get(level, 0)+1
        if level in ['WARN', 'ERROR']:
            kept = {k: event[k] for k in LOG_FIELDS if k in event}
            if str(event.get('log.logger', '')).endswith('.ApiExceptionHandler'): kept['message'] = event.get('message')
            events.append(kept)
    return {'levels': levels, 'unparsedLines': unparsed, 'warnOrErrorEvents': len(events), 'events': events}


def serve(directory):
    with open('/run/lock/videogame-platform-dev-deployment.lock','a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        actor=Experiment(directory)
        try:
            while select.select([sys.stdin],[],[],600)[0]:
                line=sys.stdin.readline()
                if not line: break
                request=json.loads(line)
                action=request['action']
                require(action in ['preflight','prepare','users','activate','background_prepare','background_sync','settle','start_observation','stop_observation','restore'],'Unknown action')
                try:
                    value=getattr(actor,action)(**request.get('arguments',{}))
                    print(json.dumps({'ok':True,'value':value}),flush=True)
                except Exception as error:
                    print(json.dumps({'ok':False,'error':str(error) if isinstance(error,RuntimeError) else type(error).__name__}),flush=True)
                if action=='restore': break
        finally:
            if actor.state['phase'] not in ['new','restored']:
                try: actor.restore()
                except Exception:
                    atomic(actor.directory/'recovery-required.json',{'phase':actor.state['phase'],'receipt':str(actor.receipt)})


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory')
    args=parser.parse_args()
    try: serve(args.directory)
    except Exception:
        print(json.dumps({'ok':False,'error':'Host boundary/lock failed; no private output exported'}),flush=True)
        raise SystemExit(1)
