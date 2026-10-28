// Correctness under load: hammer ONE user's bucket from many concurrent
// clients, spread across every app instance by the load balancer, and
// count how many requests were allowed.
//
// The bucket holds 10 tokens and refills at 1 token/sec, so the most
// that may ever be allowed is 10 + (test duration in seconds). Anything
// above that is over-admission.
//
//   ENDPOINT=/api/check        (atomic Lua version, default)
//   ENDPOINT=/api/check-naive  (broken GET-then-SET version, for contrast)
import http from 'k6/http';
import { Counter } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://lb';
const ENDPOINT = __ENV.ENDPOINT || '/api/check';
const TOTAL = Number(__ENV.TOTAL || 5000);
const VUS = Number(__ENV.VUS || 200);
const CAPACITY = 10;
const REFILL_PER_SEC = 1;

const allowed = new Counter('allowed');
const rejected = new Counter('rejected');
const errors = new Counter('errors');

export const options = {
  scenarios: {
    burst: { executor: 'shared-iterations', vus: VUS, iterations: TOTAL, maxDuration: '2m' },
  },
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  return { user: `hot-${Date.now()}`, start: Date.now() };
}

export default function (data) {
  const res = http.get(`${BASE}${ENDPOINT}?userId=${data.user}`);
  if (res.status === 200) allowed.add(1);
  else if (res.status === 429) rejected.add(1);
  else errors.add(1);
}

export function handleSummary(s) {
  const m = s.metrics;
  const ok = m.allowed ? m.allowed.values.count : 0;
  const no = m.rejected ? m.rejected.values.count : 0;
  const err = m.errors ? m.errors.values.count : 0;
  const secs = s.state.testRunDurationMs / 1000;
  const maxAllowed = CAPACITY + Math.floor(secs * REFILL_PER_SEC);
  const over = Math.max(0, ok - maxAllowed);
  const lat = m.http_req_duration.values;
  const out = [
    `endpoint          ${ENDPOINT}`,
    `requests          ${ok + no + err} for one user from ${VUS} concurrent clients`,
    `duration          ${secs.toFixed(2)} s  (${(m.http_reqs.values.rate).toFixed(0)} req/s)`,
    `allowed           ${ok}`,
    `rejected (429)    ${no}`,
    `errors            ${err}`,
    `max allowed       ${maxAllowed}  (10 tokens + 1/sec refill)`,
    `over-admitted     ${over}  (${maxAllowed ? ((over / maxAllowed) * 100).toFixed(0) : 0}%)`,
    `latency p50/p95/p99  ${lat['p(50)'].toFixed(1)} / ${lat['p(95)'].toFixed(1)} / ${lat['p(99)'].toFixed(1)} ms`,
  ].join('\n');
  return { stdout: `\n${out}\n`, '/out/hotkey-summary.json': JSON.stringify({ endpoint: ENDPOINT, ok, no, err, secs, maxAllowed, over, lat }, null, 2) };
}
