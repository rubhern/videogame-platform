import { sleep } from 'k6';
import execution from 'k6/execution';
import authenticated, { authOptions, handleSummary } from './authenticated.js';
import { publicRead, requests } from './baseline.js';
const config = JSON.parse(open(__ENV.PERFORMANCE_CONFIG || '/results/workload.json'));
export const options = {
  ...authOptions,
  scenarios: {
    ...authOptions.scenarios,
    public_reads: { executor: 'per-vu-iterations', vus: 1, iterations: config.publicIterations,
      exec: 'publicReads', maxDuration: config.maxDuration || '90s', gracefulStop: '5s',
      ...(config.publicStartSeconds ? { startTime: `${config.publicStartSeconds}s` } : {}) },
  },
  thresholds: { ...authOptions.thresholds,
    http_reqs: [`count==${config.userPool.length * (16 * config.iterationsPerUser + 12) + config.publicIterations}`],
    ...Object.fromEntries(requests.map(({ name }) => [`http_req_duration{name:${name}}`, []])),
  },
};
export default authenticated;
export { handleSummary };
export function publicReads() {
  const index = execution.scenario.iterationInTest;
  publicRead(requests[index % requests.length], `public-${index}`);
  sleep(config.requestPauseSeconds);
}
