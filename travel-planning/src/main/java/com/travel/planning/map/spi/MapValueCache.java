package com.travel.planning.map.spi;

import java.time.Duration;
import java.util.Optional;

/**
 * 地图外部结果缓存抽象（M12）：生产 Redis，测试/关闭可用内存或空实现。
 */
public interface MapValueCache {

    Optional<String> get(String key);

    void put(String key, String value, Duration ttl);

    /** 写入空结果标记（如地理编码无结果），TTL 短于正常结果。 */
    void putEmpty(String key, Duration ttl);
}
