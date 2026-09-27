package com.travel.knowledge.memory;

import java.util.List;
import java.util.Map;

/**
 * SessionContextService 公共契约（AD-2c 接口化）。
 *
 * <p>实现见 {@link SessionContextServiceImpl}（@Service 在 Impl）；
 * 公有方法签名逐字提取（零语义变更）。</p>
 */
public interface SessionContextService {

    /** 写入会话上下文 chunk（向量库）。 */
    void write(SessionContextChunk chunk);

    /** 语义检索会话上下文。 */
    List<Map<String, Object>> search(String sessionId, String query, int topK);

    /** 按序号前缀查询。 */
    List<Map<String, Object>> findBySeqPrefix(String sessionId, String seqPrefix, int limit);

    /** 按序号前缀删除（返回删除数）。 */
    int deleteBySeqPrefix(String sessionId, String seqPrefix);
}
