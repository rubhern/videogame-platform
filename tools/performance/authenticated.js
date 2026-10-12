import http from 'k6/http';
import { check, sleep } from 'k6';
import execution from 'k6/execution';
import { Counter } from 'k6/metrics';

// Credentials live only in a temporary, mode-0700 mount, never in evidence.
const config = JSON.parse(open('/results/workload.json'));
const users = JSON.parse(open('/credentials/users.json'));
const timeouts = new Counter('response_timeouts');
const operations = new Counter('rating_operations');
const sessions = new Counter('authenticated_sessions');
const reused = new Counter('session_reuses');
const cases = [
  'login_begin', 'login_form', 'login_submit', 'login_callback', 'login_landing',
  'session', 'csrf_rejected', 'prepare_rating', 'my_ratings', 'my_title', 'my_value', 'my_search', 'my_alias',
  'peer_absence', 'rating_absence', 'rating_create', 'rating_read_created', 'rating_update',
  'rating_read_updated', 'my_after_update', 'rating_delete', 'rating_read_deleted',
  'my_after_delete', 'restore_rating', 'logout', 'session_after_logout',
];
export const authOptions = {
  scenarios: Object.fromEntries(users.map((_, slot) => [`authenticated_${slot + 1}`, {
    executor: 'per-vu-iterations', vus: 1, env: { PERFORMANCE_USER_SLOT: String(slot) },
    iterations: config.iterationsPerUser, maxDuration: config.maxDuration || '90s', gracefulStop: '5s',
  }])),
  hosts: config.hosts || {},
  noCookiesReset: true,
  maxRedirects: 0,
  systemTags: ['name', 'method', 'status', 'expected_response', 'scenario', 'check', 'error_code'],
  summaryTrendStats: ['count', 'avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
  thresholds: {
    checks: ['rate==1'], http_req_failed: ['rate==0'], dropped_iterations: ['count==0'],
    http_reqs: [`count==${users.length * (16 * config.iterationsPerUser + 12)}`],
    authenticated_sessions: [`count==${users.length}`],
    session_reuses: [`count==${users.length * (config.iterationsPerUser - 1)}`],
    ...Object.fromEntries(cases.map((name) => [`http_req_duration{name:${name}}`, []])),
  },
};
export const options = authOptions;
let state;

function request(name, method, url, body, expected, headers = {}) {
  const correlation = `${config.runId}-${execution.vu.idInTest}-${name}-${execution.vu.iterationInScenario}`;
  const r = http.request(method, url, body, {
    redirects: 0, timeout: '10s',
    responseCallback: http.expectedStatuses(expected),
    tags: { name },
    headers: { Accept: 'application/json', 'X-Correlation-ID': correlation, ...headers },
  });
  timeouts.add(r.error_code === 1050 ? 1 : 0, { name });
  check(r, { 'expected HTTP status': (response) => response.status === expected }, { name });
  if (url.startsWith(config.origin) && !name.startsWith('login_')) {
    check(r, {
      'correlation echoed': () => r.headers['X-Correlation-Id'] === correlation,
      'personal response no-store': () => r.headers['Cache-Control']?.includes('no-store'),
    }, { name });
  }
  sleep(config.requestPauseSeconds);
  return r;
}
function json(r) { try { return r.json(); } catch (_) { return null; } }
function verify(name, result) { check(result, { 'case semantics': (value) => Boolean(value) }, { name }); }
function api(name, method, path, expected = 200, payload = null, headers = {}) {
  return request(name, method, config.origin + '/api/v1' + path,
    payload === null ? null : JSON.stringify(payload), expected,
    { ...(payload ? { 'Content-Type': 'application/json' } : {}), ...headers });
}
function redirect(response, origin) {
  const target = response.headers.Location;
  if (!target) throw new Error('Login response omitted redirect');
  // Fail closed: callbacks may contain codes. Never print or tag the target.
  const url = target.startsWith('/') ? origin + target : target;
  if (!(url.startsWith(config.origin + '/') || url.startsWith(config.keycloakOrigin + '/'))) {
    throw new Error('Login redirect outside configured BFF/Keycloak origins');
  }
  return url;
}
function login(user) {
  const begin = request('login_begin', 'GET', config.origin + '/auth/login/keycloak', null, 302);
  const before = http.cookieJar().cookiesForURL(config.origin)[config.cookieName]?.[0];
  const form = request('login_form', 'GET', redirect(begin, config.origin), null, 200, { Accept: 'text/html' });
  const action = form.html().find('#kc-form-login').attr('action');
  if (!action || !action.startsWith(config.keycloakOrigin + '/')) throw new Error('Missing hosted Keycloak login form');
  const submitted = request('login_submit', 'POST', action,
    { username: user.username, password: user.password, credentialId: '' }, 302,
    { Accept: 'text/html', Origin: config.keycloakOrigin });
  const callback = request('login_callback', 'GET', redirect(submitted, config.keycloakOrigin), null, 302);
  request('login_landing', 'GET', redirect(callback, config.origin), null, 200, { Accept: 'text/html' });
  const cookie = http.cookieJar().cookiesForURL(config.origin)[config.cookieName];
  verify('login_landing', cookie?.length === 1 && cookie[0].split('.').length !== 3 && cookie[0] !== before
    && callback.headers['Set-Cookie']?.includes('HttpOnly')
    && callback.headers['Set-Cookie']?.includes('SameSite=Lax')
    && (!config.origin.startsWith('https://') || /; Secure(?:;|$)/i.test(callback.headers['Set-Cookie'])));
  sessions.add(1);
}
function session() {
  const body = json(api('session', 'GET', '/session'));
  verify('session', body?.authenticated === true && typeof body.csrfToken === 'string'
    && Object.keys(body).sort().join(',') === 'authenticated,csrfToken');
  return body?.csrfToken;
}
function write(name, game, value, etag) {
  const r = api(name, 'PUT', `/me/ratings/${game}`, etag ? 200 : 201, { value },
    { 'X-CSRF-Token': state.csrf, ...(etag ? { 'If-Match': etag } : { 'If-None-Match': '*' }) });
  const body = json(r);
  verify(name, body?.personalRating?.gameId === game && body.personalRating.value === value
    && body.personalRating.entityTag === r.headers.Etag && Boolean(r.headers.Etag)
    && body.ratingStatistics?.status === 'available');
  operations.add(r.status === (etag ? 200 : 201) ? 1 : 0, { operation: etag ? 'update' : 'create', phase: name === 'prepare_rating' ? 'prepare' : 'workload' });
  state.lastWrittenAt = body?.personalRating?.updatedAt;
  return r.headers.Etag;
}
function remove(name, game, etag) {
  const r = api(name, 'DELETE', `/me/ratings/${game}`, 200, null,
    { 'X-CSRF-Token': state.csrf, 'If-Match': etag || '"missing-validator"' });
  const body = json(r);
  verify(name, body?.personalRating === null && body.ratingStatistics?.status === 'available');
  operations.add(r.status === 200 ? 1 : 0, { operation: 'delete', phase: name === 'restore_rating' ? 'restore' : 'workload' });
}
function read(name, game, value, etag) {
  const r = api(name, 'GET', `/me/ratings/${game}`);
  const body = json(r);
  verify(name, body?.gameId === game && body.value === value && body.entityTag === etag && r.headers.Etag === etag);
}
function list(name, query, expected) {
  const body = json(api(name, 'GET', '/me/ratings?page=1&pageSize=20' + query));
  verify(name, body?.page?.totalItems === expected.length && body.page.totalPages === (expected.length ? 1 : 0)
    && body.page.number === 1 && body.page.size === 20 && body.items?.length === expected.length
    && body.items.every((item, i) => item.game.gameId === expected[i].gameId
      && item.personalRating.gameId === expected[i].gameId && item.personalRating.value === expected[i].value
      && item.personalRating.entityTag?.startsWith('"')));
}
export default function authenticated() {
  // A scenario owns one slot, independent of k6's global VU allocation order.
  const slot = Number(__ENV.PERFORMANCE_USER_SLOT);
  const user = users[slot];
  if (!user) throw new Error('One independent synthetic user is required per authenticated VU');
  if (!state) {
    login(user);
    state = { csrf: session() };
    const rejected = api('csrf_rejected', 'PUT', `/me/ratings/${user.gameId}`, 403, { value: user.createValue }, { 'If-None-Match': '*' });
    verify('csrf_rejected', json(rejected)?.code === 'CSRF_VALIDATION_FAILED');
    state.seeds = user.seeds.map((seed) => ({ ...seed, etag: write('prepare_rating', seed.gameId, seed.value), updatedAt: state.lastWrittenAt }));
  } else {
    state.csrf = session();
    reused.add(1);
  }
  const seeds = state.seeds;
  const titleOrder = user.titleOrder.map((index) => seeds[index]);
  const recentOrder = [...seeds].sort((a, b) => b.updatedAt.localeCompare(a.updatedAt) || a.gameId.localeCompare(b.gameId));
  list('my_ratings', '&sort=updatedAt&direction=desc', recentOrder);
  list('my_title', '&sort=canonicalTitle&direction=asc', titleOrder);
  list('my_value', '&sort=ratingValue&direction=desc', [...seeds].reverse());
  list('my_search', '&q=' + encodeURIComponent(user.search), [seeds[1]]);
  list('my_alias', '&q=' + encodeURIComponent(user.alias), [seeds[0]]);
  const peer = users[(slot + 1) % users.length];
  api('peer_absence', 'GET', `/me/ratings/${peer.seeds[0].gameId}`, 404);
  api('rating_absence', 'GET', `/me/ratings/${user.gameId}`, 404);
  const created = write('rating_create', user.gameId, user.createValue);
  read('rating_read_created', user.gameId, user.createValue, created);
  const updated = write('rating_update', user.gameId, user.updateValue, created);
  read('rating_read_updated', user.gameId, user.updateValue, updated);
  list('my_after_update', '&sort=ratingValue&direction=desc',
    [{ gameId: user.gameId, value: user.updateValue }, ...[...seeds].reverse()]);
  remove('rating_delete', user.gameId, updated);
  api('rating_read_deleted', 'GET', `/me/ratings/${user.gameId}`, 404);
  list('my_after_delete', '&sort=canonicalTitle&direction=asc', titleOrder);
  if (execution.vu.iterationInScenario === config.iterationsPerUser - 1) {
    for (const seed of seeds) remove('restore_rating', seed.gameId, seed.etag);
    api('logout', 'POST', '/session', 204, null, { 'X-CSRF-Token': state.csrf });
    verify('session_after_logout', json(api('session_after_logout', 'GET', '/session'))?.authenticated === false);
  }
}
export function handleSummary(data) {
  return { '/results/summary.json': JSON.stringify(data, null, 2),
    stdout: `Requests: ${data.metrics.http_reqs.values.count}; checks: ${data.metrics.checks.values.passes} passed / ${data.metrics.checks.values.fails} failed\n` };
}
