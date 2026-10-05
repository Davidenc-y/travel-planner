package com.travel.common.cache;

/**
 * BB-5：Redis 键空间注册表——全仓 Redis 键前缀的唯一权威清单。
 *
 * <p>每条注册包含：前缀模式、所属模块、用途、是否有 TTL、失效方式。
 * 新增 Redis 缓存键时必须在此注册（code review 检查项）。</p>
 *
 * <p>管理方式：AdminCacheController /admin/redis/caches 消费本表，
 * 逐前缀 SCAN 统计键数并展示；/admin/redis/caches/{prefix}/evict 按前缀手动清除。</p>
 */
public final class RedisKeyRegistry {

    private RedisKeyRegistry() {
    }

    /** 注册条目：前缀（支持 glob 通配）、模块、用途、TTL 类型、失效方式 */
    public record KeyEntry(String pattern, String module, String purpose, TtlType ttl, Eviction eviction) {}

    public enum TtlType { NONE, SHORT(60), MEDIUM(600), LONG(3600), DAY(86400), WEEK(604800);
        final int seconds;
        TtlType(int s) { this.seconds = s; }
        TtlType() { this.seconds = 0; }
        public int getSeconds() { return seconds; }
    }

    public enum Eviction { TTL_ONLY, WRITE_EVICT, PUBSUB_INVALIDATE, MANUAL, NATURE_EXPIRE }

    /** 全仓键前缀注册表（BB 时点快照，新增缓存须追加） */
    public static final KeyEntry[] ENTRIES = {
        // ==== 缓存类（BB 新增/增强） ====
        new KeyEntry("travel:cache:stats:*",        "planning",  "看板 stats 聚合结果缓存",      TtlType.SHORT, Eviction.TTL_ONLY),
        new KeyEntry("travel:cache:turnlatency:*",  "planning",  "看板轮次耗时缓存",            TtlType.SHORT, Eviction.TTL_ONLY),
        new KeyEntry("travel:cache:latencyspans:*", "planning",  "看板延迟分段缓存",            TtlType.SHORT, Eviction.TTL_ONLY),
        new KeyEntry("travel:cache:usage:*",        "planning",  "个人中心用量统计缓存",        TtlType.MEDIUM, Eviction.TTL_ONLY),
        new KeyEntry("travel:attr:cities",           "knowledge", "城市列表缓存",               TtlType.DAY,   Eviction.WRITE_EVICT),
        new KeyEntry("travel:attr:coords:*",         "knowledge", "城市坐标集缓存",             TtlType.DAY,   Eviction.WRITE_EVICT),
        new KeyEntry("travel:chat:sessions:*",       "planning",  "会话列表缓存",               TtlType.SHORT, Eviction.WRITE_EVICT),
        // ==== 既有缓存（登记不动） ====
        new KeyEntry("travel:itn:detail:*",         "planning",  "行程详情缓存",               TtlType.MEDIUM, Eviction.WRITE_EVICT),
        new KeyEntry("travel:map:route:v1:*",        "planning",  "高德路线缓存",               TtlType.DAY,   Eviction.TTL_ONLY),
        new KeyEntry("travel:map:geocode:v1:*",      "planning",  "地理编码缓存",               TtlType.DAY,   Eviction.TTL_ONLY),
        // ==== 计数器/配额（fail-closed 语义） ====
        new KeyEntry("travel:map:quota:*",           "planning",  "高德地图日/月配额",          TtlType.DAY,   Eviction.NATURE_EXPIRE),
        new KeyEntry("travel:tavily:credits:*",      "knowledge", "Tavily 月度信用计数",        TtlType.WEEK,  Eviction.NATURE_EXPIRE),
        new KeyEntry("ai:budget:*",                   "ai-gateway","LLM token 预算计数",         TtlType.DAY,   Eviction.NATURE_EXPIRE),
        new KeyEntry("travel:rag:metrics:*",         "chat-domain","RAG 质量日计数",            TtlType.DAY,   Eviction.NATURE_EXPIRE),
        // ==== 会话/状态存储 ====
        new KeyEntry("chat:interrupt:*",             "chat-domain","聊天中断标记",              TtlType.SHORT, Eviction.NATURE_EXPIRE),
        new KeyEntry("chat:breakpoint:*",            "chat-domain","聊天断点快照",              TtlType.MEDIUM,Eviction.NATURE_EXPIRE),
        new KeyEntry("travel:brief:*",               "chat-domain","简报快照",                  TtlType.SHORT, Eviction.NATURE_EXPIRE),
        new KeyEntry("chat:supledger:*",             "chat-domain","supervisor 结果台账",        TtlType.MEDIUM,Eviction.NATURE_EXPIRE),
        new KeyEntry("refresh_token:*",              "planning",  "刷新令牌",                    TtlType.WEEK,  Eviction.WRITE_EVICT),
        new KeyEntry("travel:jwt:blacklist:*",       "planning",  "JWT 吊销黑名单",             TtlType.DAY,   Eviction.NATURE_EXPIRE),
        new KeyEntry("travel:gray:overrides",        "common",    "灰度开关覆盖层",             TtlType.NONE,  Eviction.MANUAL),
        // ==== Stream 队列（无 TTL，受 allkeys-lru 驱逐风险） ====
        new KeyEntry("travel:etl:changes",           "knowledge", "ETL 变更事件 Stream",        TtlType.NONE,  Eviction.MANUAL),
        new KeyEntry("travel:chat:writeback",        "planning",  "writeback 事件 Stream",      TtlType.NONE,  Eviction.MANUAL),
    };
}
