local bucket = KEYS[1]
local capacity = tonumber(ARGV[1])
local rate = tonumber(ARGV[2])
local now = tonumber(ARGV[3])
local cost = tonumber(ARGV[4])

local tokens_str = redis.call("hget", bucket, "tokens")
local last_refill_str = redis.call("hget", bucket, "timestamp")

local tokens
local last_refill

if tokens_str == false or last_refill_str == false then
    tokens = capacity
    last_refill = now
else
    tokens = tonumber(tokens_str)
    last_refill = tonumber(last_refill_str)
    local elapsed_ms = now - last_refill
    if elapsed_ms > 0 then
        local new_tokens = (elapsed_ms / 1000.0) * rate
        tokens = math.min(capacity, tokens + new_tokens)
    end
end

if tokens >= cost then
    tokens = tokens - cost
    redis.call("hset", bucket, "tokens", tokens)
    redis.call("hset", bucket, "timestamp", now)
    redis.call("expire", bucket, 86400)
    return 1
else
    redis.call("hset", bucket, "tokens", tokens)
    redis.call("hset", bucket, "timestamp", now)
    redis.call("expire", bucket, 86400)
    return 0
end
