package com.travel.planning.client;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import com.travel.common.result.R;

import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AK-2b：Knowledge Client 传输模式选择器（E-61 发现模式灰度）。
 *
 * <p>AK 二审 AR-1 后形态：本类暴露 {@link KnowledgeSearchPort} 的<b>唯一实现 Bean</b>（委托适配器），
 * 构造期按 {@code travel.knowledge.discovery-mode} 选定委托目标（双 Feign Client 已脱离 port 继承，
 * 因 OpenFeign 注册器默认 primary=true，双 Client 同继 port=双 primary 必炸——实证见 git AR-1）。</p>
 *
 * <ul>
 *   <li>默认（键缺失或 false）：直连 Client 委托=URL 直连行为等价（E-61 关闭态）。</li>
 *   <li>true：发现 Client 委托=服务名+LoadBalancer（多实例；开启归审计窗 R396，E-60）。</li>
 * </ul>
 *
 * <p>回退=键改回 false（两 Client Bean 恒在，Feign 代理惰性解析）；零 yml 键新增。</p>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Configuration
@lombok.extern.slf4j.Slf4j
public class KnowledgeClientSelector {

    /** E-61 灰度键（Nacos 侧开启归审计窗 R396；yml 零触碰） */
    public static final String DISCOVERY_MODE_KEY = "travel.knowledge.discovery-mode";

    /**
     * AK 二审 AR-1（2026-09-30）重设计：唯一 port 适配器。
     *
     * <p>原两条件 @Primary 包装 Bean 方案在生产炸毁——Spring Cloud OpenFeign 注册器对每个
     * @FeignClient 默认 primary=true（SelectorWiringDiagTest 实证：双 Feign Bean primary=true
     * +包装 primary=三 primary 并存→按 port 注入 "more than one 'primary' bean found"）。
     * 双 Feign 接口已脱离 port 继承；本 Bean 是 {@link KnowledgeSearchPort} 的<b>唯一实现</b>，
     * 按键值在构造期选定委托目标（两 Feign Client 均常驻 Bean，Feign 代理惰性解析零网络调用）。</p>
     *
     * <p>E-61 语义不变：默认（键缺失/false）=直连 Client 委托=URL 直连字节等价；
     * true=发现 Client 委托（服务名+LB 多实例；开启与双实例验证归审计窗 R396，E-60）。
     * 回退=键改回 false。构造期选择（非条件装配）=两态 Bean 面恒定单 port，无装配歧义。</p>
     */
    @Bean
    public KnowledgeSearchPort knowledgeSearchPort(
            ObjectProvider<KnowledgeClient> directClient,
            ObjectProvider<KnowledgeDiscoveryClient> discoveryClient,
            @Value("${" + DISCOVERY_MODE_KEY + ":false}") boolean discoveryMode) {
        if (discoveryMode) {
            KnowledgeDiscoveryClient delegate = discoveryClient.getObject();
            log.info("[KnowledgeSelector] 发现模式（服务名+LB）：travel.knowledge.discovery-mode=true");
            return new PortAdapter(
                    (ragType, query, topK) -> delegate.search(ragType, query, topK),
                    delegate::writeSessionContext,
                    delegate::searchSessionContext,
                    delegate::findSessionContextByPrefix,
                    delegate::deleteSessionContextByPrefix,
                    delegate::cityCounts);
        }
        KnowledgeClient delegate = directClient.getObject();
        log.info("[KnowledgeSelector] 直连模式（URL 现状）：travel.knowledge.discovery-mode=false");
        return new PortAdapter(
                delegate::search,
                delegate::writeSessionContext,
                delegate::searchSessionContext,
                delegate::findSessionContextByPrefix,
                delegate::deleteSessionContextByPrefix,
                delegate::cityCounts);
    }

    /** AK 二审 AR-1：五方法委托适配器（port 唯一实现；签名与 port 逐字对齐） */
    @FunctionalInterface
    interface WriteFn {
        R<Object> writeSessionContext(Map<String, Object> chunk);
    }

    @FunctionalInterface
    interface SearchFn {
        R<List<Map<String, Object>>> search(
                String ragType, String query, int topK);
    }

    @FunctionalInterface
    interface SearchSessionFn {
        R<List<Map<String, Object>>> searchSessionContext(
                String sessionId, String query, int topK);
    }

    @FunctionalInterface
    interface FindPrefixFn {
        R<List<Map<String, Object>>> findSessionContextByPrefix(
                String sessionId, String seqPrefix, int limit);
    }

    @FunctionalInterface
    interface DeletePrefixFn {
        R<Integer> deleteSessionContextByPrefix(String sessionId, String seqPrefix);
    }

    /** AL-2b（GL-2）：城市语料计数委托面（port 第六方法）。 */
    @FunctionalInterface
    interface CityCountsFn {
        R<Map<String, Integer>> cityCounts();
    }

    static final class PortAdapter implements KnowledgeSearchPort {
        private final SearchFn searchFn;
        private final WriteFn writeFn;
        private final SearchSessionFn searchSessionFn;
        private final FindPrefixFn findPrefixFn;
        private final DeletePrefixFn deletePrefixFn;
        private final CityCountsFn cityCountsFn;

        PortAdapter(SearchFn searchFn,
                WriteFn writeFn,
                SearchSessionFn searchSessionFn, FindPrefixFn findPrefixFn, DeletePrefixFn deletePrefixFn,
                CityCountsFn cityCountsFn) {
            this.searchFn = searchFn;
            this.writeFn = writeFn;
            this.searchSessionFn = searchSessionFn;
            this.findPrefixFn = findPrefixFn;
            this.deletePrefixFn = deletePrefixFn;
            this.cityCountsFn = cityCountsFn;
        }

        @Override
        public R<List<Map<String, Object>>> search(
                String ragType, String query, int topK) {
            return searchFn.search(ragType, query, topK);
        }

        @Override
        public R<Object> writeSessionContext(java.util.Map<String, Object> chunk) {
            return writeFn.writeSessionContext(chunk);
        }

        @Override
        public R<List<Map<String, Object>>> searchSessionContext(
                String sessionId, String query, int topK) {
            return searchSessionFn.searchSessionContext(sessionId, query, topK);
        }

        @Override
        public R<List<Map<String, Object>>> findSessionContextByPrefix(
                String sessionId, String seqPrefix, int limit) {
            return findPrefixFn.findSessionContextByPrefix(sessionId, seqPrefix, limit);
        }

        @Override
        public R<Integer> deleteSessionContextByPrefix(String sessionId, String seqPrefix) {
            return deletePrefixFn.deleteSessionContextByPrefix(sessionId, seqPrefix);
        }

        @Override
        public R<Map<String, Integer>> cityCounts() {
            return cityCountsFn.cityCounts();
        }
    }
}
