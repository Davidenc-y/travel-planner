package com.travel.planning.client;

import com.travel.common.result.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

/**
 * Knowledge 模块 Feign 客户端
 *
 * <p>供 travel-planning 模块通过 HTTP 调用 travel-knowledge 的 RAG 检索 API。</p>
 *
 * <p>配置：travel.knowledge.base-url（chat-domain 域单源 classpath:application-chat.yml:69，
 * 默认 http://localhost:8082；X-1a 盘点修正：该键不在各进程 application.yml）</p>
 *
 * <p>超时与重试：{@link KnowledgeClientConfig}（B1.1；默认连接 2s/读取 8s、重试 1 次，KNOWLEDGE_FEIGN_* 环境变量可覆盖）</p>
 *
 * <p>X-2b：{@code fallbackFactory} 挂接 {@link KnowledgeClientFallbackFactory}（显式失败降级，
 * 禁 R.ok 伪装）——默认态 circuitbreaker=false 仅挂接不接管（异常直抛=现状），开启归审计实弹。</p>
 *
 * <p>MI-5：本接口 extends {@link KnowledgeSearchPort}（业务端口，纯签名零注解）——
 * Feign 注解留在本接口（传输契约不外溢），三调用方改注入端口类型（Spring 按类型
 * 解析同一代理，行为零变更）；knowledge 微服务抽取时提供同契约实现即可替换传输层。</p>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@FeignClient(name = "travel-knowledge", url = "${travel.knowledge.base-url:http://localhost:8082}",
        configuration = KnowledgeClientConfig.class,
        fallbackFactory = KnowledgeClientFallbackFactory.class)
public interface KnowledgeClient extends KnowledgeSearchPort {

    /**
     * RAG 检索（调用 knowledge 的 /api/v1/rag/search）
     *
     * @param ragType 策略类型（naive/hybrid/self_rag/corrective_rag）
     * @param query   查询文本
     * @param topK    返回结果数
     * @return R<List<Map>> 检索结果（docId/title/snippet/score/...）
     */
    @GetMapping("/api/v1/rag/search")
    @Override
    R<List<Map<String, Object>>> search(
            @RequestParam("ragType") String ragType,
            @RequestParam("query") String query,
            @RequestParam("topK") int topK);

    /**
     * Phase C/F78：写入一条会话知识切片（knowledge /api/v1/memory/session-context）
     */
    @PostMapping("/api/v1/memory/session-context")
    @Override
    R<Object> writeSessionContext(@RequestBody Map<String, Object> chunk);

    /**
     * Phase C/F78：检索会话知识（sessionId 过滤 + Hybrid RRF）
     */
    @GetMapping("/api/v1/memory/session-context/search")
    @Override
    R<List<Map<String, Object>>> searchSessionContext(
            @RequestParam("sessionId") String sessionId,
            @RequestParam("query") String query,
            @RequestParam("topK") int topK);

    /**
     * M4-5b：按 seq 前缀取回会话切片（二次取父，itinerary_day 完整天块视图）。
     * sessionId 隔离与 search 同口径（knowledge 侧 term 过滤）。
     */
    @GetMapping("/api/v1/memory/session-context/by-prefix")
    @Override
    R<List<Map<String, Object>>> findSessionContextByPrefix(
            @RequestParam("sessionId") String sessionId,
            @RequestParam("seqPrefix") String seqPrefix,
            @RequestParam("limit") int limit);

    /**
     * M8-9：按 seq 前缀删除会话切片（REFINE/重生成覆盖旧版本）。
     */
    @DeleteMapping("/api/v1/memory/session-context/by-prefix")
    @Override
    R<Integer> deleteSessionContextByPrefix(
            @RequestParam("sessionId") String sessionId,
            @RequestParam("seqPrefix") String seqPrefix);
}
