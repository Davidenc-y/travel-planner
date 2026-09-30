package com.travel.planning.client;

import com.travel.common.result.R;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

/**
 * AK-2a：Knowledge 模块 Feign 客户端——服务名发现模式（E-61）。
 *
 * <p>与 {@link KnowledgeClient}（URL 直连）同契约（{@link KnowledgeSearchPort} 五方法），
 * 差异仅传输寻址：<b>无 url 属性</b>——Feign 走服务名 {@code travel-knowledge} +
 * LoadBalancer（chat-domain pom 既有 spring-cloud-starter-loadbalancer），
 * 多实例部署时经 Nacos 实例列表负载均衡（E-60 审计窗验证）。</p>
 *
 * <p>E-61 灰度：{@code travel.knowledge.discovery-mode} 默认 false=URL 直连字节等价
 * （本 Client Bean 存在但不被注入，URL 模式代码路径零删除）；true=经
 * {@link KnowledgeClientSelector}（AK-2b）切换 @Primary。contextId 独立
 * （travel-knowledge-discovery）避免与直连 Client 的 FeignClientSpecification 冲突；
 * 服务名解析仍用 name=travel-knowledge。</p>
 *
 * <p>降级语义与直连共用：{@link KnowledgeDiscoveryClientFallbackFactory} 委托
 * {@link KnowledgeClientFallbackFactory} 共用失败体（显式失败禁 R.ok 伪装=P0⑦）。</p>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@FeignClient(name = "travel-knowledge", contextId = "travel-knowledge-discovery",
        configuration = KnowledgeClientConfig.class,
        fallbackFactory = KnowledgeDiscoveryClientFallbackFactory.class)
// AK 二审 AR-1：脱离 port 同 KnowledgeClient（双 primary 根因）；适配器见 KnowledgeClientSelector。
public interface KnowledgeDiscoveryClient {

    /** RAG 检索（knowledge /api/v1/rag/search；契约与 KnowledgeClient 逐字一致）。 */
    @GetMapping("/api/v1/rag/search")
    R<List<Map<String, Object>>> search(
            @RequestParam("ragType") String ragType,
            @RequestParam("query") String query,
            @RequestParam("topK") int topK);

    /** Phase C/F78：写入一条会话知识切片（knowledge /api/v1/memory/session-context）。 */
    @PostMapping("/api/v1/memory/session-context")
    R<Object> writeSessionContext(@RequestBody Map<String, Object> chunk);

    /** Phase C/F78：检索会话知识（sessionId 过滤 + Hybrid RRF）。 */
    @GetMapping("/api/v1/memory/session-context/search")
    R<List<Map<String, Object>>> searchSessionContext(
            @RequestParam("sessionId") String sessionId,
            @RequestParam("query") String query,
            @RequestParam("topK") int topK);

    /** M4-5b：按 seq 前缀取回会话切片（二次取父，itinerary_day 完整天块视图）。 */
    @GetMapping("/api/v1/memory/session-context/by-prefix")
    R<List<Map<String, Object>>> findSessionContextByPrefix(
            @RequestParam("sessionId") String sessionId,
            @RequestParam("seqPrefix") String seqPrefix,
            @RequestParam("limit") int limit);

    /** M8-9：按 seq 前缀删除会话切片（REFINE/重生成覆盖旧版本）。 */
    @DeleteMapping("/api/v1/memory/session-context/by-prefix")
    R<Integer> deleteSessionContextByPrefix(
            @RequestParam("sessionId") String sessionId,
            @RequestParam("seqPrefix") String seqPrefix);

    /** AL-2b（GL-2）：城市语料计数（knowledge /api/v1/etl/city-counts；契约与 KnowledgeClient 逐字一致）。 */
    @GetMapping("/api/v1/etl/city-counts")
    R<Map<String, Integer>> cityCounts();
}

/**
 * AK-2a：发现模式 Client 降级工厂——委托 {@link KnowledgeClientFallbackFactory}
 * 共用失败体（同码 50301/同文案/同 WARN 日志语义）。
 */
@Slf4j
@Component
class KnowledgeDiscoveryClientFallbackFactory
        implements FallbackFactory<KnowledgeDiscoveryClient> {

    public KnowledgeDiscoveryClient create(Throwable cause) {
        log.warn("[KnowledgeFallback] knowledge（discovery 模式）调用降级: {}", cause.toString());
        return new KnowledgeClientFallbackFactory.KnowledgeUnavailableFallback();
    }
}
