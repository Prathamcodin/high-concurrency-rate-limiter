-- token_bucket.lua
-- Atomic lazy-refill token bucket.
-- KEYS[1] = redis key for this user's bucket (e.g. "bucket:userA")
-- ARGV[1] = capacity        (max tokens the bucket can hold)
-- ARGV[2] = refill_rate     (tokens added per second)
-- ARGV[3] = now             (current time, in seconds, as a float)
-- ARGV[4] = requested       (tokens this request needs, usually 1)
--
-- Returns 1 if allowed, 0 if rejected.
-- This entire script runs as ONE atomic Redis command -- no other
-- client's command (including another instance of this same script)
-- can run in between any of these lines. That's what makes it safe
-- under concurrency: two requests for the same key can never both
-- read the same stale token count.

local bucket = redis.call("HMGET", KEYS[1], "tokens", "timestamp")
local tokens = tonumber(bucket[1])
local timestamp = tonumber(bucket[2])

local capacity = tonumber(ARGV[1])
local refill_rate = tonumber(ARGV[2])
local now = tonumber(ARGV[3])
local requested = tonumber(ARGV[4])

-- First request ever for this user: bucket starts full.
if tokens == nil then
    tokens = capacity
    timestamp = now
end

-- Lazy refill: figure out how many tokens should have accumulated
-- since the last request, based on elapsed time. No background
-- job needed -- the math happens right here, on read.
local elapsed = math.max(0, now - timestamp)
local refilled = math.min(capacity, tokens + (elapsed * refill_rate))

local allowed = 0
if refilled >= requested then
    refilled = refilled - requested
    allowed = 1
end

redis.call("HMSET", KEYS[1], "tokens", refilled, "timestamp", now)
redis.call("EXPIRE", KEYS[1], 3600)

return allowed
