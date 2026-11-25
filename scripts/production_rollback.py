#!/usr/bin/env python3
"""Owner-operated rollback of the active production release to its last-known-good predecessor (#488).

Default: a read-only assessment. It names the active release and the last-known-good one, which is
the predecessor recorded in upgrades/ACTIVE.json: a completed upgrade with its retained bundle, exact
source SHA, image digests, configuration fingerprint and reviewed plan. Its verdict is one of:

- ALLOW: the two releases differ only in the application (ADR-0043 handover scope), apart from
  dependency images that main rebuilt from unchanged sources: those keep running, so the target is
  the last-known-good configuration with the active dependency images. The live Flyway
  history is exactly the last-known-good inventory, with the same checksums. The realm import, live realm
  policy, secrets, other services and settings are unchanged, and the source range touches no persisted
  or identity contract.
- REFUSE: a precondition or the evidence is missing, ambiguous or busy. Nothing changed; correct the
  cause and assess again.
- FORWARD_FIX_OR_RECOVER: the releases are incompatible, so no rollback is ever offered. Publish a
  forward fix, or use the separately authorized paired restore.

--apply, for the reviewed assessment hash and an owner reference, runs the handover in reverse under
the backup-cycle lock and the recovery lease. A temporary candidate of the last-known-good
application serves, then the application is replaced, smoked and named in ACTIVE.json. Any failure
restores the release that was running. The database, Keycloak, volumes, journals of other upgrades and
bundles are never changed, so no rating, account, identity change or erasure is undone.
"""
import sys
sys.dont_write_bytecode = True

import argparse
import contextlib
import json
import os
from pathlib import Path
import re
import time
import uuid

import install_private_production as install
import private_dev_promotion as trust
import production_public_ingress as ingress
import private_production_control as control
import private_production_upgrade as upgrade
import production_deploy as deploy

ALLOW, REFUSE, FORWARD = 'ALLOW', 'REFUSE', 'FORWARD_FIX_OR_RECOVER'
ROLLBACK_PREFIX = 'rollback-'
ASSESSMENT = 'assessment.json'
DERIVED, DERIVED_TWIN = 'compose.rollback.json', 'compose.rollback.private.json'
# Every main build republishes these three images, and the builds are not reproducible, so a new
# digest alone says nothing. The sources each image is built from (its Dockerfile, every COPY source
# and the build script that sets its arguments); unchanged sources mean a rebuild of the same
# component, which the rollback keeps running rather than replacing with an older build.
BUILD_SCRIPT = 'scripts/validate-dependency-image.sh'
DEPENDENCY_SOURCES = {
    'keycloak': ('deploy/private-dev/keycloak/', 'docker/keycloak/', 'frontend/src/shared/brand/gameometro-logo.svg',
                 'frontend/src/shared/brand/gameometro-mark.svg', 'frontend/public/assets/fonts/mona-sans-latin.woff2',
                 'frontend/public/assets/fonts/monasans-OFL.txt', BUILD_SCRIPT),
    'caddy': ('deploy/public-production/caddy/', BUILD_SCRIPT),
    'catalogue-localizer': ('tools/catalogue-localization/', BUILD_SCRIPT),
}
# A change here alters what the database or Keycloak persist or accept, so the previous code is not
# proven to read what the newer one wrote. The schema is also checked live; these paths catch a range
# whose effect the live checks cannot see.
PERSISTED_CONTRACT_PATHS = ('backend/src/main/resources/db/migration/', 'deploy/public-production/keycloak/',
                            'docker/keycloak/', 'docker/postgres/')
# Reasons that no retry can clear: the releases themselves are incompatible.
FORWARD_REASONS = frozenset({
    'another-service-changes', 'configuration-changes', 'settings-change', 'secret-changes',
    'service-inventory-changes', 'runtime-section-changes', 'schema-advanced', 'migration-inventory-differs',
    'identity-changes', 'persisted-contract-paths',
})
# Reasons that leave a rollback possible once the cause is corrected; every one is a refusal.
REFUSE_REASONS = frozenset({
    'no-upgrade-lineage', 'target-not-last-known-good', 'current-release-unverified', 'last-known-good-unverified',
    'image-missing', 'image-provenance', 'schema-ambiguous', 'source-range-unavailable', 'edge-without-candidate',
    'no-fixed-application-address', 'unfinished-operation', 'fence-closed', 'runtime-drift', 'candidate-running',
    'active-work', 'insufficient-memory',
})
HANDOVER_REASONS = {'another-service-changes': 'another-service-changes', 'configuration-changes': 'configuration-changes',
                    'settings-change': 'settings-change', 'edge-without-candidate': 'edge-without-candidate',
                    'no-fixed-application-address': 'no-fixed-application-address'}
ACTIONS = {
    ALLOW: 'Owner decision: authorize --apply with this assessmentSha256 and an owner reference, then run the '
           'upgrader --verify for the last-known-good revision and the browser checks.',
    REFUSE: 'Nothing changed. Correct the listed precondition or evidence and assess again; never bypass a check.',
    FORWARD: 'No rollback: publish a forward fix through the normal release, or --forward-fix-of for an '
             'interrupted upgrade. If data is damaged, use the separately authorized paired restore on an empty '
             'target. Close public ingress for an unresolved security or data-integrity failure.',
}
IMPACT = [
    'The application restarts behind the edge with a 30-second drain while a temporary candidate serves.',
    'Signed-in BFF sessions end (ADR-0027); a remembered Keycloak session signs back in without a password.',
    'A write on a connection the stopping instance closes may fail once and must be repeated.',
    'The database, Keycloak and every volume are untouched: new ratings, accounts, identity changes and erasures stay.',
    'Every code change in the source range is reverted, including any security fix it contains.',
]
ADVICE = {
    'running': 'Interrupted: --abandon restores the release that was running, or completes a rollback that already named its target.',
    'failed': 'Containment inconclusive: --abandon retries restoring the release that was running.',
    'complete': 'The last-known-good release serves. Run the upgrader --verify for it and the browser checks.',
    'undone': 'The attempt was undone and the release that was running serves again; assess before another attempt.',
}
require = trust.require


def verdict(reasons):
    reasons = set(reasons)
    require(reasons <= FORWARD_REASONS | REFUSE_REASONS, 'Unknown rollback reason.')
    if reasons & FORWARD_REASONS:
        return FORWARD
    return REFUSE if reasons else ALLOW


def configuration_delta(current, target, local=lambda p: p):
    """Changed services between the active and the last-known-good runtime, plus structural reasons."""
    reasons = []
    if set(current['services']) != set(target['services']):
        reasons.append('service-inventory-changes')
    if current.get('secrets') != target.get('secrets'):
        reasons.append('secret-changes')
    if any(current.get(k) != target.get(k) for k in (set(current) | set(target)) - {'services', 'secrets'}):
        reasons.append('runtime-section-changes')
    changed = {}
    for name in sorted(set(current['services']) & set(target['services'])):
        before, after = upgrade.normalized(current['services'][name]), upgrade.normalized(target['services'][name])
        fields = sorted(k for k in set(before) | set(after) if before.get(k) != after.get(k))
        if not fields and upgrade.bound_files_equal(current['services'][name], target['services'][name], local):
            continue
        changed[name] = fields or ['bound-files']
    return changed, reasons


def handover_reasons(current, target, changed):
    """The ADR-0043 handover scope, read in reverse: only the application and its reported version differ."""
    found = upgrade.handover_refusals(current, target, changed, {'pending': []}, True, [])
    return sorted({HANDOVER_REASONS[r] for r in found})


def derive(current, target, kept):
    """The last-known-good configuration with the active definition of every kept dependency."""
    derived = json.loads(json.dumps(target))
    for name in kept:
        derived['services'][name] = json.loads(json.dumps(current['services'][name]))
    return derived


def sanitized(record):
    keys = ('kind', 'status', 'phase', 'failedPhase', 'fromRevision', 'targetRevision', 'assessmentSha256',
            'containment', 'reason', 'cancellation', 'commandCompletion', 'backupServices', 'startedAtUnix',
            'finishedAtUnix')
    return {k: record.get(k) for k in keys if k in record}


class Rollback(upgrade.Upgrade):
    """The upgrader bound to the last-known-good bundle as its target, never to a new publication."""

    def __init__(self, target, **kwargs):
        super().__init__(target, **kwargs)
        self.directory = None  # A rollback journal exists only once an apply claims one.

    # --- seams a test replaces ---
    def deployment_config(self):
        return deploy.read_config()

    @contextlib.contextmanager
    def cycle_lock(self):
        """The lock both backup timers and the Actions deployment hold; a busy or unfinished cycle refuses."""
        cycle = deploy.load_cycle(Path(self.deployment_config()['backupCycleConfig']))
        with cycle.exclusive():
            require(cycle.journal['status'] == 'idle', 'A scheduled backup is unfinished; let its reconciler finish.')
            yield

    def reconcile_services(self):
        """Point the backup, cleanup, deletion and telemetry units at the last-known-good bundle again."""
        deploy.backup_units(Path(self.deployment_config()['backupCycleConfig']), self.bundle / 'candidate', apply=True)

    def changed_paths(self, base, head, paths):
        """Files under paths that the strictly linear source range changes."""
        self.run(['git', '-C', self.repository, 'merge-base', '--is-ancestor', base, head], output=False)
        names = self.run(['git', '-C', self.repository, 'diff', '--name-only', '--no-renames', f'{base}..{head}', '--',
                          *paths]).decode().split()
        require(len(names) <= 2000, 'Source range exceeds its bound.')
        return sorted(names)

    # --- evidence ---
    def journal_at(self, directory):
        path = self.admin / upgrade.UPGRADES / directory / upgrade.JOURNAL
        if not path.exists():
            return None
        install.secure(path, mode=0o600)
        value = json.loads(path.read_text())
        require(isinstance(value, dict), 'Invalid journal.')
        return value

    def plan_of(self, revision, journal):
        """The reviewed plan of a completed upgrade, bound by its journal's plan hash."""
        path = self.bundles / f'{revision[:12]}-private' / upgrade.PLAN
        install.secure(path, mode=0o600)
        plan = json.loads(path.read_text())
        require(upgrade.digest(plan) == journal.get('planSha256'), 'A retained plan differs from its journal.')
        return plan

    def rollbacks(self):
        root = self.admin / upgrade.UPGRADES
        return sorted(root.glob(ROLLBACK_PREFIX + '*/' + upgrade.JOURNAL)) if root.is_dir() else []

    def unfinished_rollbacks(self):
        return [p.parent for p in self.rollbacks() if json.loads(p.read_text()).get('status') in ('running', 'failed')]

    def pointer(self):
        path = self.admin / upgrade.UPGRADES / upgrade.ACTIVE
        if not path.exists():
            return None
        install.secure(path, mode=0o600)
        return json.loads(path.read_text())

    def releases(self, reasons):
        """Current and last-known-good identities; None when the lineage cannot name them."""
        pointer = self.pointer()
        if not pointer or not isinstance(pointer.get('previous'), dict):
            reasons.append('no-upgrade-lineage')
            return None
        previous = pointer['previous']
        current = self.active['revision']
        if previous.get('revision') != self.target or self.target == current:
            reasons.append('target-not-last-known-good')
            return None
        result = {'pointer': pointer, 'current': {'revision': current}, 'lkg': {'revision': self.target}}
        try:
            journal = self.journal_at(current)
            require(journal and journal.get('status') == 'complete'
                    and journal.get('targetConfigurationSha256') == install.sha(self.active['file'])
                    and pointer.get('configurationSha256') == journal['targetConfigurationSha256'],
                    'The active release has no completed upgrade for its configuration.')
            plan = self.plan_of(current, journal)
            result['current'].update(journal=journal, plan=plan, configurationSha256=journal['targetConfigurationSha256'],
                                     planSha256=journal['planSha256'], version=plan['target']['version'],
                                     images={n: self.active['config']['services'][n]['image'] for n in upgrade.PUBLISHED})
        except (trust.Refused, OSError, ValueError, KeyError, TypeError):
            reasons.append('current-release-unverified')
            return None
        try:
            journal = self.journal_at(self.target)
            path = Path(previous.get('configurationFile', ''))
            require(journal and journal.get('status') == 'complete' and install.sha(path) == previous.get('configurationSha256')
                    and self.retained_target(path, journal), 'The last-known-good release is not a completed upgrade with its retained configuration.')
            plan = self.plan_of(self.target, journal)
            config = json.loads(path.read_text())
            require({n: config['services'][n]['image'] for n in upgrade.PUBLISHED} == previous.get('images')
                    and plan['target']['revision'] == self.target, 'The last-known-good images differ from the lineage.')
            # The staged bundle is verified as staged; the predecessor may be a rollback's derived target of it.
            bundle_config = json.loads((self.bundle / upgrade.TARGET_CONFIG).read_text())
            result['lkg'].update(journal=journal, plan=plan, config=config, bundleConfig=bundle_config, configurationFile=str(path),
                                 configurationSha256=journal['targetConfigurationSha256'], planSha256=journal['planSha256'],
                                 version=plan['target']['version'], images=previous['images'])
        except (trust.Refused, OSError, ValueError, KeyError, TypeError):
            reasons.append('last-known-good-unverified')
            return None
        return result

    def retained_target(self, path, journal):
        """The upgrade's own bundle configuration, or the derived target of a completed rollback to it."""
        if path == self.bundle / upgrade.TARGET_CONFIG:
            return install.sha(path) == journal.get('targetConfigurationSha256')
        directory = path.parent
        if path.name != DERIVED or directory.parent != self.admin / upgrade.UPGRADES or not directory.name.startswith(ROLLBACK_PREFIX):
            return False
        record = self.journal_at(directory.name) or {}
        return (record.get('kind') == 'rollback' and record.get('status') == 'complete' and record.get('targetRevision') == self.target
                and record.get('targetConfigurationSha256') == install.sha(path))

    def images_ready(self, lkg, config, reasons):
        if not all(self.local_images(self.required_images(config)).values()):
            reasons.append('image-missing')
            return False
        value = self.local_image(config['services']['application']['image'])
        labels = (value or {}).get('Config', {}).get('Labels') or {}
        if labels.get('org.opencontainers.image.revision') != self.target:
            reasons.append('image-provenance')
            return False
        try:
            self.verify_staged(lkg['plan'], lkg['bundleConfig'])
        except (trust.Refused, OSError, ValueError, KeyError):
            reasons.append('last-known-good-unverified')
            return False
        return True

    def schema(self, current, lkg, reasons):
        if current['plan'].get('migrationInventory') != lkg['plan'].get('migrationInventory'):
            reasons.append('migration-inventory-differs')
        try:
            state = upgrade.flyway_delta(self.query('videogame_platform', upgrade.HISTORY_SQL), lkg['plan']['migrationInventory'])
        except trust.Refused:
            reasons.append('schema-advanced')
            return None
        if state['pending'] or state['currentVersion'] != state['targetVersion']:
            reasons.append('schema-ambiguous')
            return None
        return {'version': state['currentVersion'], 'rank': state['currentRank']}

    def identity_unchanged(self, current, lkg, reasons):
        source = self.bundle / 'candidate' / upgrade.REALM_IMPORT
        same_import = source.read_bytes() == (self.active['source'] / upgrade.REALM_IMPORT).read_bytes()
        realm = current['plan'].get('realm') or {}
        live = self.query('videogame_keycloak', upgrade.REALM_SQL)
        # The plan names the registration notice whenever the import carries the approved one, even
        # when the idempotent actor changes nothing; a real notice change alters the import itself.
        if not (same_import and not realm.get('changes')
                and live == upgrade.desired_session_state(json.loads(source.read_text()))):
            reasons.append('identity-changes')
        return 'identical' if same_import else 'differs'

    def runtime(self, lkg, reasons):
        """Volatile admission; never part of the reviewed hash."""
        observed = {}
        if self.unfinished() or self.unfinished_rollbacks():
            reasons.append('unfinished-operation')
        gate = self.gate_state()
        observed['gate'] = {k: gate.get(k) for k in ('ready', 'pending', 'maintenance')}
        if not gate['ready'] or gate['pending'] or gate['maintenance']:
            reasons.append('fence-closed')
        try:
            observed['liveServices'] = self.observe_services(gate)
        except trust.Refused:
            reasons.append('runtime-drift')
        if self.candidate_running():
            reasons.append('candidate-running')
        observed['activeWork'] = self.query('videogame_platform', upgrade.WORK_SQL)
        if any(observed['activeWork'].values()):
            reasons.append('active-work')
        if lkg.get('config') and self.available_memory() < int(lkg['config']['services']['application']['mem_limit']) \
                + upgrade.HANDOVER_MEMORY_RESERVE:
            reasons.append('insufficient-memory')
        return observed

    def derived_twin(self, derived, kept, reasons):
        """A public edge keeps its private twin: the last-known-good one with the same kept services."""
        lkg_twin = self.bundle / upgrade.PRIVATE_TWIN
        caddy = derived['services']['caddy']
        if not caddy.get('ports'):
            return None
        try:
            install.secure(lkg_twin, mode=0o600)
            twin = json.loads(lkg_twin.read_text())
            if kept:
                current = self.active['bundle'] / upgrade.PRIVATE_TWIN
                install.secure(current, mode=0o600)
                twin = derive(json.loads(current.read_text()), twin, kept)
            edge = [m for m in caddy.get('volumes', []) if m['target'] == ingress.CADDYFILE_TARGET]
            require(len(edge) == 1 and ingress.derive_public(twin, edge[0]['source'], caddy['ports'][0]['host_ip']) == derived,
                    'The public edge is not exactly its private twin plus the reviewed edge.')
            return twin
        except (trust.Refused, OSError, ValueError, KeyError, TypeError, IndexError):
            reasons.append('last-known-good-unverified')
            return None

    def assess(self):
        """Read-only. Static evidence is hashed for the owner's decision; runtime admission is reported apart."""
        reasons = []
        releases = self.releases(reasons)
        result = {'mode': 'assess', 'targetRevision': self.target, 'currentRevision': self.active['revision'],
                  'productionReady': False, 'assessmentSha256': None}
        if releases is None:
            result.update(verdict=verdict(reasons), reasons=sorted(reasons), action=ACTIONS[verdict(reasons)])
            return result
        current, lkg = releases['current'], releases['lkg']
        changed, structural = configuration_delta(self.active['config'], lkg['config'])
        kept = {}
        try:
            persisted = self.changed_paths(self.target, current['revision'], PERSISTED_CONTRACT_PATHS)
            for name, paths in DEPENDENCY_SOURCES.items():
                if changed.get(name) == ['image'] and not self.changed_paths(self.target, current['revision'], paths):
                    kept[name] = self.active['config']['services'][name]['image']
        except trust.Refused:
            persisted = None
            reasons.append('source-range-unavailable')
        if persisted:
            reasons.append('persisted-contract-paths')
        derived = derive(self.active['config'], lkg['config'], kept)
        changed, structural = configuration_delta(self.active['config'], derived)
        reasons += structural
        if not structural:
            reasons += handover_reasons(self.active['config'], derived, changed)
        twin = self.derived_twin(derived, kept, reasons)
        self.target_config, self.derived, self.derived_private = derived, derived, twin
        self.images_ready(lkg, derived, reasons)
        flyway = self.schema(current, lkg, reasons)
        realm_import = self.identity_unchanged(current, lkg, reasons)
        static = sorted(set(reasons))
        reviewed = {
            'current': {k: current[k] for k in ('revision', 'configurationSha256', 'planSha256', 'version', 'images')},
            'lastKnownGood': {**{k: lkg[k] for k in ('revision', 'configurationFile', 'configurationSha256', 'planSha256',
                                                       'version', 'images')},
                              'completedAtUnix': lkg['journal'].get('finishedAtUnix')},
            'changedServices': changed, 'keptServices': kept, 'targetConfigurationSha256': upgrade.digest(derived),
            'flyway': flyway, 'realmImport': realm_import,
            'persistedContractPaths': persisted, 'staticReasons': static,
        }
        observed = self.runtime(lkg, reasons)
        final = verdict(reasons)
        result.update(reviewed, verdict=final, reasons=sorted(set(reasons)), action=ACTIONS[final],
                      assessmentSha256=upgrade.digest(reviewed), runtime=observed,
                      expectedImpact=IMPACT if final != FORWARD else [])
        self.reviewed, self.releases_found = reviewed, releases
        return result

    # --- apply ---
    def verify_operator(self):
        """Offline when this operator is the one the active release claimed, so a GitHub outage cannot block."""
        require(install.operational_fingerprint() == self.adapter, 'The operator source differs from the supplied fingerprint.')
        claimed = self.journal_at(self.active['revision']) or {}
        if (self.operational, self.adapter) != (claimed.get('operationalRevision'), claimed.get('adapterFingerprint')):
            self.verify_operational(self.github())

    def write(self, record):
        record['updatedAtUnix'] = int(time.time())
        control.durable(self.directory / upgrade.JOURNAL, record)

    def apply(self, reviewed_sha, reference):
        require(re.fullmatch(r'[A-Za-z0-9#:._ -]{1,120}', reference or ''), 'An owner authorization reference is required.')
        require(re.fullmatch('[0-9a-f]{64}', reviewed_sha or ''), 'The reviewed assessment hash is required.')
        self.host_checks()
        self.verify_operator()
        with self.cycle_lock(), self.recovery.lock():
            assessment = self.assess()
            require(assessment['assessmentSha256'] == reviewed_sha, 'The assessment differs from the reviewed hash.')
            require(assessment['verdict'] == ALLOW, 'Rollback is not admitted now: ' + ','.join(assessment['reasons']))
            current, lkg = self.releases_found['current'], self.releases_found['lkg']
            started, run = int(time.time()), str(uuid.uuid4())
            # Chronological, so --inspect lists the latest; the run identity keeps each attempt apart.
            self.directory = self.admin / upgrade.UPGRADES / f'{ROLLBACK_PREFIX}{started}-{current["revision"][:12]}-{run[:8]}'
            require(not self.directory.exists(), 'A rollback journal with this identity exists.')
            record = {'schema': 1, 'kind': 'rollback', 'activation': 'handover', 'status': 'running', 'phase': 'claimed',
                      'runUuid': run, 'fromRevision': current['revision'], 'targetRevision': self.target,
                      'assessmentSha256': reviewed_sha, 'planSha256': lkg['planSha256'],
                      'targetConfigurationSha256': assessment['targetConfigurationSha256'],
                      'lastKnownGoodConfigurationSha256': lkg['configurationSha256'],
                      'keptServices': assessment['keptServices'],
                      'previous': assessment['current'], 'ownerReference': reference,
                      'operationalRevision': self.operational, 'adapterFingerprint': self.adapter,
                      'migrationStarted': False, 'identityStarted': False, 'reopened': False,
                      # An optional service already stopped stays so, and is not smoked (#452).
                      'optionalDegraded': sorted(n for n, s in assessment['runtime'].get('liveServices', {}).items()
                                                 if s == 'stopped' and n in upgrade.OPTIONAL),
                      'migration': assessment['flyway'],
                      'dataBefore': self.query('videogame_platform', upgrade.DATA_SQL),
                      'readerBefore': self.query('postgres', upgrade.READER_SQL),
                      'identityConfigurationBefore': self.identity_configuration(),
                      'productionReady': False, 'startedAtUnix': started}
            self.directory.mkdir(mode=0o700, parents=True)
            control.durable(self.directory / ASSESSMENT, {k: v for k, v in assessment.items() if k != 'runtime'})
            # The reviewed target, byte for byte: what ACTIVE will name and Compose will read.
            control.durable(self.directory / DERIVED, self.derived)
            require(install.sha(self.directory / DERIVED) == assessment['targetConfigurationSha256'],
                    'The written target differs from the reviewed one.')
            if self.derived_private is not None:
                control.durable(self.directory / DERIVED_TWIN, self.derived_private)
            self.write(record)
            self.execute_handover(record, {'target': {'revision': self.target, 'version': lkg['version']},
                                           'optionalDegraded': record['optionalDegraded']})
            self.services(record)
        return {'mode': 'rolled-back-to', 'targetRevision': self.target, 'fromRevision': current['revision'],
                'configurationFile': str(self.directory / DERIVED), 'keptServices': record['keptServices'], 'journal': str(self.directory / upgrade.JOURNAL),
                'backupServices': record['backupServices'], 'productionReady': False}

    def identity_configuration(self):
        value = self.query('videogame_keycloak', upgrade.IDENTITY_SQL)
        return {k: value[k] for k in (*upgrade.CONFIGURATION_DIGESTS, 'realm')}

    def services(self, record):
        """Outside the handover: the release already serves, so a failure here never restarts the other one."""
        try:
            self.reconcile_services()
            record['backupServices'] = 'reconciled'
        except BaseException:
            record['backupServices'] = 'failed'
            self.write(record)
            raise trust.Refused('Rolled back, but the backup services still name the reverted bundle; run --reconcile-services.')
        self.write(record)

    def smoke(self, host, record):
        super().smoke(host, record)
        require(self.identity_configuration() == record['identityConfigurationBefore'],
                'Keycloak configuration changed during the rollback.')

    def finish(self, host, record, reviewed):
        result = super().finish(host, record, reviewed)
        record['finishedAtUnix'] = int(time.time())
        self.write(record)
        return result

    def target_host(self, reviewed):
        """Compose reads the reviewed derived target, never the bundle file it was derived from."""
        host = upgrade.Host(self, self.target_config, self.directory / DERIVED, self.bundle / 'candidate',
                            reviewed['target']['revision'], reviewed['target']['version'], self.scope())
        host.degraded = tuple(reviewed.get('optionalDegraded', ()))
        return host

    def write_active(self, record, reviewed):
        """ACTIVE names the last-known-good release again, with its own predecessor and the reverted one."""
        lkg = self.journal_at(self.target)
        twin = self.directory / DERIVED_TWIN
        if twin.exists():
            state_file = self.admin / 'ingress/INGRESS.json'
            state = json.loads(state_file.read_text())
            require(state.get('mode') == 'public', 'A public target needs the recorded public ingress.')
            state.update(revision=self.target, privateConfigurationFile=str(twin), privateConfigurationSha256=install.sha(twin),
                         publicConfigurationFile=str(self.directory / DERIVED),
                         publicConfigurationSha256=record['targetConfigurationSha256'])
            control.durable(state_file, state)
        control.durable(self.admin / upgrade.UPGRADES / upgrade.ACTIVE, {
            'revision': self.target, 'configurationFile': str(self.directory / DERIVED),
            'configurationSha256': record['targetConfigurationSha256'], 'planSha256': lkg['planSha256'],
            'previous': lkg['previous'],
            'rolledBackFrom': {'revision': record['fromRevision'], 'configurationSha256': record['previous']['configurationSha256'],
                               'journal': self.directory.name},
            'updatedAtUnix': int(time.time())})
        self.mark_reverted(record)

    def mark_reverted(self, record):
        """The reverted upgrade keeps its receipt; it is no longer complete, so its SHA is never re-claimed."""
        journal = self.journal_at(record['fromRevision'])
        if journal and journal.get('status') == 'complete':
            journal.update(status='reverted', revertedBy=self.directory.name, revertedAtUnix=int(time.time()))
            control.durable(self.admin / upgrade.UPGRADES / record['fromRevision'] / upgrade.JOURNAL, journal)

    def roll_back(self, record, error=None):
        result = super().roll_back(record, error)
        if record['status'] == 'rolled-back':
            # In a rollback journal the handover's "rolled-back" means the attempt was undone.
            record['status'] = 'undone'
            with contextlib.suppress(BaseException):
                self.write(record)
            result['mode'] = 'undone'
        return result

    # --- interruption ---
    def claimed(self):
        found = self.unfinished_rollbacks()
        require(len(found) == 1, 'No single unfinished rollback exists; use --inspect.')
        self.directory = found[0]
        record = json.loads((self.directory / upgrade.JOURNAL).read_text())
        require(record.get('kind') == 'rollback' and record.get('targetRevision') == self.target,
                'The unfinished rollback names another target.')
        return record

    def abandon(self):
        """Restore the release that was running, or complete a rollback whose pointer already moved."""
        record = self.claimed()
        self.verify_claimed_operator(record)
        with self.recovery.lock():
            pointer = self.pointer() or {}
            if pointer.get('revision') == record['targetRevision']:
                with control.cleanup_window(420):
                    for identity in self.candidate_running():
                        self.run(['docker', 'stop', '--time', str(upgrade.HANDOVER_DRAIN_SECONDS), identity.decode()], output=False)
                    require(not self.candidate_running(), 'The handover candidate did not stop.')
                    host = self.host()
                    host.running(['application'])
                    host.gate_open()
                self.mark_reverted(record)
                record.update(status='complete', phase='complete', containment='confirmed', finishedAtUnix=int(time.time()))
                self.write(record)
                return {'mode': 'rolled-back-to', 'targetRevision': self.target, 'completedBy': 'abandon',
                        'next': '--reconcile-services, then verify', 'productionReady': False}
            require(pointer.get('revision') == record['fromRevision'], 'ACTIVE names neither side of the rollback.')
            return self.roll_back(record)

    def reconcile_only(self):
        pointer = self.pointer() or {}
        require(pointer.get('revision') == self.target and (pointer.get('rolledBackFrom') or {}).get('journal'),
                'The active release is not a completed rollback to this target.')
        self.directory = self.admin / upgrade.UPGRADES / pointer['rolledBackFrom']['journal']
        record = json.loads((self.directory / upgrade.JOURNAL).read_text())
        require(record.get('status') == 'complete', 'The rollback did not complete.')
        self.verify_claimed_operator(record)
        with self.cycle_lock():
            self.services(record)
        return {'mode': 'services-reconciled', 'targetRevision': self.target, 'productionReady': False}


def inspect(rollback):
    """Diagnostic only: the recent rollback journals, sanitized, with the advice for each state."""
    rows = []
    for path in rollback.rollbacks()[-5:]:
        record = json.loads(path.read_text())
        rows.append({'journal': path.parent.name, **sanitized(record), 'advice': ADVICE.get(record.get('status'))})
    return {'mode': 'inspect', 'rollbacks': rows, 'upgrades': rollback.unfinished(), 'productionReady': False}


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument('--apply', action='store_true', help='Run the owner-authorized rollback for the reviewed assessment')
    mode.add_argument('--abandon', action='store_true', help='Contain an interrupted rollback')
    mode.add_argument('--reconcile-services', action='store_true', help='Repoint the backup units after a rollback')
    mode.add_argument('--inspect', action='store_true', help='Read-only rollback journal diagnostics')
    parser.add_argument('--target-revision', required=True, help='Full SHA of the last-known-good release')
    parser.add_argument('--assessment-sha256')
    parser.add_argument('--operational-revision', required=True)
    parser.add_argument('--adapter-fingerprint', required=True)
    parser.add_argument('--owner-reference')
    parser.add_argument('--source-repository', type=Path, default=upgrade.REPOSITORY)
    args = parser.parse_args()
    control.signal_handlers()
    watcher = control.watch_session() if args.apply or args.abandon else None
    try:
        require(os.geteuid() == 0 and sys.platform == 'linux', 'Local Linux root operation required.')
        require(not any(k.startswith('COMPOSE_') for k in os.environ), 'Ambient Compose overrides are forbidden.')
        upgrade.integration.verify_local_docker()
        install.secure(install.ADMIN, mode=0o700, directory=True)
        rollback = Rollback(args.target_revision, operational=args.operational_revision, adapter=args.adapter_fingerprint,
                            repository=args.source_repository).load()
        if args.inspect:
            result = inspect(rollback)
        elif args.apply:
            result = rollback.apply(args.assessment_sha256, args.owner_reference)
        elif args.abandon:
            result = rollback.abandon()
        elif args.reconcile_services:
            result = rollback.reconcile_only()
        else:
            result = rollback.assess()
        print(json.dumps(result))
        return 0 if result.get('verdict', ALLOW) == ALLOW or args.inspect else 2
    except BaseException as error:
        # Raw Docker and environment output never reaches the terminal.
        print(json.dumps({'outcome': 'refused-or-failed', 'reason': upgrade.refusal_text(error), 'productionReady': False}))
        return 1
    finally:
        if watcher:
            watcher.set()


if __name__ == '__main__':
    raise SystemExit(main())
