-- M4-1a/P0-1：摘要双 key CAS 原子写入（滚动摘要与会话收口共用）
-- KEYS[1] = session:{id}:summary      （摘要文本）
-- KEYS[2] = session:{id}:summary:meta （lastMessageId/version/summaryType）
-- ARGV[1] = expectedVersion（调用方读到的版本；首次写入传 0）
-- ARGV[2] = summary 文本
-- ARGV[3] = metaJson（含新 version/lastMessageId）
-- ARGV[4] = ttlSeconds
-- 返回 1 = 写入成功；0 = 版本冲突（并发写者中版本较大者已胜出，本次放弃）

local meta = redis.call('GET', KEYS[2])
local current = 0
if meta then
  local ok, decoded = pcall(cjson.decode, meta)
  if ok and type(decoded) == 'table' and decoded.version ~= nil then
    current = tonumber(decoded.version) or 0
  end
end
if tonumber(ARGV[1]) ~= current then
  return 0
end
local ttl = tonumber(ARGV[4])
redis.call('SET', KEYS[1], ARGV[2], 'EX', ttl)
redis.call('SET', KEYS[2], ARGV[3], 'EX', ttl)
return 1
