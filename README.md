# High-Concurrency Distributed Rate Limiter

A distributed rate limiter using the **token bucket** algorithm, backed by
Redis for shared state and a **Lua script** for atomic check-and-decrement,
preventing race conditions when the same user sends concurrent requests
across multiple server instances.

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
concurrency_test.py             - proves the race condition exists / is fixed
Dockerfile                      - multi-stage build (Maven -> slim JRE)
docker-compose.yml              - app + redis
```

## Running it

Requires Docker only (no local Java/Maven needed -- the build happens
inside a Maven container image).

```bash
docker compose up --build
```

This starts Redis on `6379` and the app on `8080`.

Endpoints (bucket capacity = 10 tokens, refill rate = 1 token/sec):

- `GET /api/check?userId=alice` -- atomic, Lua-backed. This is the real one.
- `GET /api/check-naive?userId=alice` -- intentionally broken (GET-then-SET),
  kept only to demonstrate the race condition for comparison.

## Proving the race condition

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
  
 ## Results (actual test run)

​```
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
​```

## What this demonstrates (for interviews)

- Understanding of rate limiting algorithm tradeoffs (token bucket vs
  fixed window vs leaky bucket vs sliding window)
- Why distributed systems need shared state, not per-instance memory
- Concrete understanding of race conditions and atomicity, backed by a
  test that reproduces the bug and proves the fix -- not just a claim
  of using Redis and Lua
