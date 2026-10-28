# High-Concurrency Distributed Rate Limiter

A distributed rate limiter using the **token bucket** algorithm, backed by
Redis for shared state and a **Lua script** for atomic check-and-decrement.
It runs as **3 Spring Boot instances behind an nginx load balancer**, and the
limit holds no matter which instance a request lands on.

Load-tested with k6 on one laptop: **11,471 req/s at 42 ms p99** across
3 instances, and **0% over-admission** with 20,000 concurrent requests
hitting the same user's bucket.

## Why these design choices

- **Token bucket** over fixed window: fixed window has a boundary bug
  (a client can burst 2x the limit across a window edge). Token bucket
  enforces a true average rate over time while still allowing legitimate
  bursts up to capacity.
- **Redis** for shared state: if each server instance kept its own
  in-memory bucket, a client's effective limit would be
  `limit x number of servers` instead of the intended limit -- there'd be
  no single source of truth across instances behind a load balancer.
- **Lua script** for atomicity: a naive `GET` -> check -> `SET` sequence
  is NOT atomic. Two concurrent requests for the *same user* can both
  read the same token count before either writes back, causing a lost
  update (both get allowed when only one should have been). Redis
  executes an entire Lua script as one uninterruptible command, so the
  read-check-decrement-write cycle can never be interleaved by another
  request for the same key.
- **Redis's clock, not the app server's**: the script reads the time with
  `redis.call('TIME')` instead of taking a timestamp from the app. Every
  instance then agrees on time, and time only moves forward in the order
  requests actually execute. Passing in app-server time caused real
  over-admission under load -- see [the clock-ordering bug](#a-second-bug-the-load-test-found).

## Project structure

```
src/main/java/com/pratham/ratelimiter/
  RateLimiterApplication.java   - Spring Boot entrypoint
  RedisConfig.java              - loads the Lua script as a Spring bean
  RateLimiterService.java       - atomic (Lua) and naive (broken) implementations
  RateLimiterController.java    - /api/check and /api/check-naive endpoints
src/main/resources/
  token_bucket.lua              - the atomic lazy-refill token bucket script
  application.properties
loadtest/
  nginx.conf                    - round-robin load balancer config
  hotkey.js                     - k6: thousands of concurrent requests for ONE user
  throughput.js                 - k6: many users, measures req/s and latency
concurrency_test.py             - small demo: race condition exists / is fixed
Dockerfile                      - multi-stage build (Maven -> slim JRE)
docker-compose.yml              - 1 app instance + redis (simplest setup)
docker-compose.cluster.yml      - 3 app instances + nginx + redis
```

## Running it

Requires Docker only (no local Java/Maven needed -- the build happens
inside a Maven container image).

**Single instance:**

```bash
docker compose up --build
```

**3 instances behind a load balancer:**

```bash
docker compose -f docker-compose.cluster.yml up --build -d
```

Either way the API is on `localhost:8080` (in the cluster setup, nginx
listens there and spreads requests across the instances; each response
has an `X-Upstream` header showing which instance answered).

Endpoints (bucket capacity = 10 tokens, refill rate = 1 token/sec):

- `GET /api/check?userId=alice` -- atomic, Lua-backed. This is the real one.
- `GET /api/check-naive?userId=alice` -- intentionally broken (GET-then-SET),
  kept only to demonstrate the race condition for comparison. It sleeps
  15 ms between the read and the write to widen the race window so the bug
  shows up reliably in a demo; real traffic hits the same race with a
  smaller window.

Stop everything with `docker compose down` (add
`-f docker-compose.cluster.yml` for the cluster).

## Proving the race condition (quick demo)

```bash
pip install -r test-requirements.txt
python concurrency_test.py
```

This fires 30 concurrent requests for the same (fresh) user at both
endpoints and counts how many were allowed:

- `/api/check-naive` should let **more than 10** through -- the race
  condition causing lost updates under concurrency.
- `/api/check` should let **exactly 10** through, no matter how many
  concurrent requests arrive, because the Lua script guarantees no
  interleaving between requests for the same key.

Results (actual test run, single instance):

<img width="1472" height="766" alt="concurrency_test.py output" src="https://github.com/user-attachments/assets/15e6ee27-8590-4311-ac87-5309fa6cfd9d" />

```
--- NAIVE endpoint (no Lua, GET-then-SET) ---
Firing 30 concurrent requests for user 'testuser-fc240690' at /api/check-naive
Allowed:  30
Rejected: 0
Expected allowed: 10 (bucket capacity)
RACE CONDITION CONFIRMED: 30 requests got through, 20 more than the bucket should allow.

--- ATOMIC endpoint (Lua script) ---
Firing 30 concurrent requests for user 'testuser-8911a9f4' at /api/check
Allowed:  10
Rejected: 20
Expected allowed: 10 (bucket capacity)
Correct: exactly bucket capacity was allowed.
```

## Load test: 3 instances behind a load balancer

The k6 scripts in `loadtest/` run inside Docker, so nothing else needs
installing. With the cluster running:

```bash
# correctness under load: 5,000 concurrent requests for one user
docker run --rm --network high-concurrency-rate-limiter_default \
  -v "$PWD/loadtest:/scripts:ro" -v "$PWD/loadtest/results:/out" \
  grafana/k6:0.54.0 run /scripts/hotkey.js

# throughput and latency: 10,000 users, 200 clients, 30 s
docker run --rm --network high-concurrency-rate-limiter_default \
  -v "$PWD/loadtest:/scripts:ro" -v "$PWD/loadtest/results:/out" \
  grafana/k6:0.54.0 run /scripts/throughput.js
```

Options are passed with `-e`, e.g. `-e ENDPOINT=/api/check-naive`,
`-e TOTAL=20000 -e VUS=500` for `hotkey.js`, or `-e VUS=200 -e DURATION=30s`
for `throughput.js`. On Windows Git Bash, prefix the command with
`MSYS_NO_PATHCONV=1` so the volume paths aren't rewritten.

`hotkey.js` counts how many requests were allowed and compares that with
the most the bucket could ever allow: 10 tokens plus 1 token per second
of test time. Anything above that is over-admission.

### Results

On one laptop (16 cores; k6, nginx, Redis and all app instances on the
same machine, so the scaling numbers are conservative):

| Test | Result |
|---|---|
| 5,000 concurrent requests for one user, 200 clients, 3 instances (3 runs) | exactly **10 allowed** every run, 0% over-admission |
| 20,000 concurrent requests for one user, 500 clients | **12 allowed** (10 tokens + 2 s of refill), 0% over-admission |
| Same 5,000-request test on `/api/check-naive` | **924 allowed**: 913 over the limit |
| Throughput, 10,000 users, 200 clients, 30 s, **3 instances** | **11,471 req/s**, p50 15.9 ms, p99 42.0 ms, 344,177 requests, 0 errors, traffic split ~1/3 per instance |
| Same, **1 instance** | 7,113 req/s, p99 63.9 ms |

### A second bug the load test found

The first version passed each app server's clock
(`System.currentTimeMillis()`) into the Lua script. Under 5,000 concurrent
requests across 3 instances, the atomic endpoint admitted **17 instead of
10**, even though the script itself was atomic.

Requests reached Redis in a different order than their timestamps were
taken. So the stored timestamp sometimes moved *backwards*, and the next
request was re-credited refill time that had already been counted. Each
re-credit was tiny, but over thousands of requests they added up to whole
extra tokens. Across real machines, clock skew would make it worse.

The fix: the script reads Redis's own clock (`redis.call('TIME')`), which
is the same for every instance and moves forward in execution order.
After the fix, every run admitted exactly the limit.

## What this demonstrates (for interviews)

- Understanding of rate limiting algorithm tradeoffs (token bucket vs
  fixed window vs leaky bucket vs sliding window)
- Why distributed systems need shared state, not per-instance memory --
  shown with real instances behind a load balancer, not just claimed
- Concrete understanding of race conditions and atomicity, backed by a
  test that reproduces the bug and proves the fix -- not just a claim
  of using Redis and Lua
- That atomicity alone isn't enough: inputs to the atomic step (here,
  time) must also be consistent across instances -- a bug found by
  load-testing, explained, and fixed
- Measuring instead of guessing: k6 load tests for correctness under
  contention, throughput and tail latency
