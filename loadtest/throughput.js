// Throughput: many different users at once, the way real traffic looks.
// Each request picks a random user from a pool, so most requests are
// allowed and we measure how fast the limiter answers under load.
import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE_URL || 'http://lb';
const USERS = Number(__ENV.USERS || 10000);
const VUS = Number(__ENV.VUS || 200);
const DURATION = __ENV.DURATION || '30s';

export const options = {
  scenarios: {
    load: { executor: 'constant-vus', vus: VUS, duration: DURATION },
  },
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
  thresholds: {
    // Anything other than 200 (allowed) or 429 (rate limited) is a failure.
    checks: ['rate>0.999'],
  },
};

export default function () {
  const user = `u${Math.floor(Math.random() * USERS)}`;
  const res = http.get(`${BASE}/api/check?userId=${user}`);
  check(res, { 'answered 200 or 429': (r) => r.status === 200 || r.status === 429 });
}

export function handleSummary(s) {
  const m = s.metrics;
  const lat = m.http_req_duration.values;
  const out = [
    `virtual users     ${VUS}, ${USERS} distinct user IDs, ${DURATION}`,
    `requests          ${m.http_reqs.values.count}`,
    `throughput        ${m.http_reqs.values.rate.toFixed(0)} req/s`,
    `latency p50/p95/p99  ${lat['p(50)'].toFixed(1)} / ${lat['p(95)'].toFixed(1)} / ${lat['p(99)'].toFixed(1)} ms`,
    `failed checks     ${((1 - m.checks.values.rate) * 100).toFixed(3)}%`,
  ].join('\n');
  return { stdout: `\n${out}\n`, '/out/throughput-summary.json': JSON.stringify({ vus: VUS, users: USERS, duration: DURATION, reqs: m.http_reqs.values.count, rps: m.http_reqs.values.rate, lat, checkRate: m.checks.values.rate }, null, 2) };
}
