"""
Concurrency test for the rate limiter.

What this proves:
  - The bucket capacity is 10 tokens (see RateLimiterService.CAPACITY).
  - We fire 30 concurrent requests for the SAME user at the SAME instant,
    against both endpoints.
  - /api/check-naive (GET-then-SET, no atomicity) should let MORE than
    10 requests through -- the race condition in action.
  - /api/check (Lua script, atomic) should let EXACTLY 10 through,
    no matter how many concurrent requests hit it.

Run with:  python concurrency_test.py
Requires:  pip install requests
"""

import requests
import concurrent.futures
import uuid

BASE_URL = "http://localhost:8080"
NUM_REQUESTS = 30  # more than the bucket capacity (10), on purpose


def fire_request(endpoint: str, user_id: str) -> bool:
    """Returns True if the request was allowed, False if rejected."""
    try:
        resp = requests.get(f"{BASE_URL}{endpoint}", params={"userId": user_id}, timeout=5)
        return resp.status_code == 200
    except requests.RequestException as e:
        print(f"Request failed: {e}")
        return False


def run_burst(endpoint: str, label: str):
    # Fresh user each run so previous test runs don't affect the bucket state.
    user_id = f"testuser-{uuid.uuid4().hex[:8]}"

    print(f"\n--- {label} ---")
    print(f"Firing {NUM_REQUESTS} concurrent requests for user '{user_id}' at {endpoint}")

    with concurrent.futures.ThreadPoolExecutor(max_workers=NUM_REQUESTS) as executor:
        futures = [executor.submit(fire_request, endpoint, user_id) for _ in range(NUM_REQUESTS)]
        results = [f.result() for f in futures]

    allowed = sum(results)
    rejected = len(results) - allowed

    print(f"Allowed:  {allowed}")
    print(f"Rejected: {rejected}")
    print(f"Expected allowed: 10 (bucket capacity)")

    if allowed > 10:
        print(f"RACE CONDITION CONFIRMED: {allowed} requests got through, "
              f"{allowed - 10} more than the bucket should allow.")
    elif allowed == 10:
        print("Correct: exactly bucket capacity was allowed.")
    else:
        print("Fewer than capacity allowed -- unexpected, investigate.")


if __name__ == "__main__":
    run_burst("/api/check-naive", "NAIVE endpoint (no Lua, GET-then-SET)")
    run_burst("/api/check", "ATOMIC endpoint (Lua script)")
