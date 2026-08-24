package com.travel.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.travel.common.entity.Attraction;
import com.travel.common.exception.AttractionNotFoundException;
import com.travel.common.result.PageResult;
import com.travel.knowledge.rag.service.RagDispatcher;
import com.travel.knowledge.rag.model.QueryIntent;
import com.travel.knowledge.rag.service.QueryUnderstandingService;
import com.travel.knowledge.rag.model.SearchResult;
import com.travel.knowledge.trace.KnowledgeTraceRecorder;
import com.travel.knowledge.repository.AttractionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Arrays;

/**
 * 景点服务
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AttractionService {

    private final AttractionMapper attractionMapper;
    private final RagDispatcher ragDispatcher;
    private final QueryUnderstandingService queryUnderstandingService;
    private final KnowledgeTraceRecorder knowledgeTraceRecorder;

    /**
     * 分页查询景点
     */
    public PageResult<Attraction> list(String city, String type, int page, int size) {
        LambdaQueryWrapper<Attraction> wrapper = new LambdaQueryWrapper<>();
        if (city != null && !city.isBlank()) {
            // F101：多城市筛选——前端以逗号分隔（"北京,上海"）传入，按 in 查询
            List<String> cities = Arrays.stream(city.split(","))
                    .map(String::trim)
                    .filter(c -> !c.isBlank())
                    .toList();
            if (cities.size() == 1) {
                wrapper.eq(Attraction::getCity, cities.get(0));
            } else if (!cities.isEmpty()) {
                wrapper.in(Attraction::getCity, cities);
            }
        }
        if (type != null && !type.isBlank()) {
            wrapper.eq(Attraction::getType, type);
        }
        wrapper.orderByDesc(Attraction::getRating);
        Page<Attraction> p = attractionMapper.selectPage(new Page<>(page, size), wrapper);
        return PageResult.of(p.getRecords(), p.getTotal(), page, size);
    }

    /**
     * M5-1：全部城市去重列表（“浏览全部”城市下拉）。
     */
    public List<String> listCities() {
        return attractionMapper.listCities();
    }

    /**
     * 查询景点详情
     */
    public Attraction getById(Long id) {
        Attraction a = attractionMapper.selectById(id);
        if (a == null) {
            throw new AttractionNotFoundException(id);
        }
        return a;
    }

    /**
     * RAG 景点检索
     */
    public List<SearchResult> search(String query, String ragType, int topK) {
        // F40/P1：前置查询理解，产出结构化意图后路由到策略。
        // F89：RAG 链路追溯（模型/路径/耗时/状态落 t_agent_trace）
        return knowledgeTraceRecorder.aroundRag(query, ragType, () -> {
            QueryIntent intent = queryUnderstandingService.understand(query);
            return ragDispatcher.dispatch(ragType, intent, topK);
        });
    }
}
