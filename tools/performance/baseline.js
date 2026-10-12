import http from 'k6/http';
import { check } from 'k6';
import execution from 'k6/execution';
import { Counter } from 'k6/metrics';

// Supplied by baseline.py after read-only local preflight; no remote imports.
const config = JSON.parse(open(__ENV.PERFORMANCE_CONFIG || '/results/workload.json'));
export const requests = config.requests || [
  { name: 'releases_recent', path: '/releases?view=recent&weeks=1&page=1&pageSize=20' },
  { name: 'releases_upcoming', path: '/releases?view=upcoming&weeks=1&page=1&pageSize=20' },
  { name: 'releases_featured', path: '/featured-releases' },
  { name: 'catalogue_search', path: '/games?q=zelda&page=1&pageSize=20' },
  { name: 'game_detail', path: `/games/${encodeURIComponent(config.gameId)}` },
];

export const options = {
  scenarios: {
    public_reads: {
      executor: 'constant-arrival-rate',
      rate: config.rate || 1,
      timeUnit: '1s',
      duration: `${config.durationSeconds || 120}s`,
      preAllocatedVUs: config.preAllocatedVUs || 1,
      maxVUs: config.maxVUs || 1,
      gracefulStop: '10s',
    },
  },
  // Checks are functional gates. Latency is evidence, with no SLO threshold.
  thresholds: {
    checks: ['rate==1'],
    http_req_failed: ['rate==0'],
    ...(config.expectedRequests !== undefined ? {
      http_reqs: [`count==${config.expectedRequests}`], dropped_iterations: ['count==0'],
    } : config.dataset === 'interference' ? {
      http_reqs: [`count==${config.rate * config.durationSeconds}`], dropped_iterations: ['count==0'],
    } : config.dataset === 'growth' ? {} : {
      http_reqs: ['count==120'], dropped_iterations: ['count==0'],
    }),
    ...Object.fromEntries(requests.map(({ name }) => [
      `http_req_duration{name:${name}}`, [],
    ])),
  },
  summaryTrendStats: ['count', 'avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
  systemTags: ['name', 'method', 'status', 'expected_response', 'scenario', 'check'],
  maxRedirects: 0,
  userAgent: 'Gameometro-public-baseline/k6',
};

http.setResponseCallback(http.expectedStatuses(200));

function isGame(item) {
  return item && typeof item.gameId === 'string' && item.gameId.length > 0
    && typeof item.slug === 'string' && typeof item.canonicalTitle === 'string'
    && item.primaryCover && typeof item.primaryCover.kind === 'string';
}

const timeouts = new Counter('response_timeouts');
const budget = (config.rate || 1) * (config.durationSeconds || 120);

function isPage(body, request) {
  const page = body?.page;
  return Array.isArray(body?.items) && body.items.length <= 20
    && (page?.number === (request.page || 1)) && page.size === 20
    && Number.isInteger(page.totalItems) && page.totalItems >= body.items.length
    && Number.isInteger(page.totalPages) && page.totalPages >= 0
    && new Set(body.items.map((item) => item.gameId)).size === body.items.length
    && body.items.every(isGame);
}

function isStatistics(statistics) {
  if (statistics?.status === 'unavailable') {
    return statistics.reasonCode === 'RATING_STATISTICS_READ_FAILED';
  }
  if (statistics?.status !== 'available' || !Number.isInteger(statistics.count)
      || statistics.count < 0 || !statistics.distribution) return false;
  const buckets = Array.from({ length: 10 }, (_, i) => statistics.distribution[String(i + 1)]);
  return buckets.every((count) => Number.isInteger(count) && count >= 0)
    && buckets.reduce((sum, count) => sum + count, 0) === statistics.count
    && (statistics.count === 0 ? statistics.mean === null
      : typeof statistics.mean === 'number' && statistics.mean >= 1 && statistics.mean <= 10);
}

function isContract(body, request) {
  const name = request.kind || request.name;
  if (name === 'catalogue_search') {
    return isPage(body, request) && body.items.length > 0
      && (request.expectedTotal === undefined || body.page.totalItems === request.expectedTotal)
      && (!request.firstGameId || body.items[0].gameId === request.firstGameId)
      && (['growth', 'interference', 'authenticated'].includes(config.dataset) || body.items[0].gameId === config.gameId)
      && body.items.every((item) => Array.isArray(item.releaseContext) && item.releaseSummary);
  }
  if (name === 'game_detail') {
    return isGame(body) && body.gameId === (request.gameId || config.gameId)
      && ['aliases', 'developers', 'publishers', 'genres', 'gameModes', 'releases']
        .every((field) => Array.isArray(body[field]))
      && body.summary && typeof body.ratingEligibility?.eligible === 'boolean'
      && isStatistics(body.ratingStatistics)
      && (request.releaseCount === undefined || body.releases.length === request.releaseCount)
      && (request.ratingCount === undefined || body.ratingStatistics.status === 'available'
        && body.ratingStatistics.count === request.ratingCount);
  }
  if (name === 'releases_featured') {
    return Array.isArray(body?.items) && body.items.length <= 6
      && /^\d{4}-\d{2}$/.test(body.month) && body.evaluatedOn === config.evaluatedOn
      && ['ranked', 'popularity_unavailable', 'no_qualifying_releases'].includes(body.selection?.status)
      && body.items.every((item) => isGame(item) && item.featuredImage
        && Array.isArray(item.releases) && item.releases.length > 0);
  }
  return isPage(body, request) && body.view === (request.view || name.replace('releases_', ''))
    && body.evaluatedOn === config.evaluatedOn && body.window?.from && body.window?.to
    && body.activeFilters && body.availableFilters
    && (request.expectedTotal === undefined || body.page.totalItems === request.expectedTotal)
    && (!request.platformIds || JSON.stringify([...body.activeFilters.platformIds].sort())
      === JSON.stringify([...request.platformIds].sort()))
    && (!request.regionIds || JSON.stringify([...body.activeFilters.regionIds].sort())
      === JSON.stringify([...request.regionIds].sort()))
    && body.items.every((item) => Array.isArray(item.releases) && item.releases.length > 0);
}

export default function () {
  const index = execution.scenario.iterationInTest;
  // An iteration at the executor's end boundary must not exceed the request budget.
  if (index >= budget) return;
  const request = requests[index % requests.length];
  publicRead(request, index);
}

export function publicRead(request, index) {
  const correlationId = `${config.runId}-${index}`;
  const response = http.get(`${config.baseUrl || 'http://application:8080/api/v1'}${request.path}`, {
    timeout: '10s',
    headers: { Accept: 'application/json', 'X-Correlation-ID': correlationId },
    tags: { name: request.name },
  });
  timeouts.add(response.error_code === 1050 ? 1 : 0, { name: request.name });
  let body;
  try { body = response.json(); } catch (_) { body = null; }
  check(response, {
    'HTTP 200': (r) => r.status === 200,
    'JSON response': (r) => r.headers['Content-Type']?.includes('application/json') && body !== null,
    'correlation echoed': (r) => r.headers['X-Correlation-Id'] === correlationId,
    'public contract': () => isContract(body, request),
  }, { name: request.name });
}

export function handleSummary(data) {
  const metrics = data.metrics;
  const lines = [
    `Requests: ${metrics.http_reqs.values.count}; throughput: ${metrics.http_reqs.values.rate.toFixed(3)}/s`,
    `HTTP failure rate: ${metrics.http_req_failed.values.rate}; check pass rate: ${metrics.checks.values.rate}`,
    'Endpoint                 count   mean ms   p50 ms   p95 ms   p99 ms',
  ];
  for (const { name } of requests) {
    const values = metrics[`http_req_duration{name:${name}}`]?.values;
    if (values) lines.push(`${name.padEnd(24)} ${String(values.count).padStart(5)}  `
      + [values.avg, values.med, values['p(95)'], values['p(99)']]
        .map((value) => value.toFixed(2).padStart(8)).join(' '));
  }
  return { [`${__ENV.PERFORMANCE_RESULTS || '/results'}/summary.json`]: JSON.stringify(data, null, 2), stdout: lines.join('\n') + '\n' };
}
