package com.travel.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.travel.common.entity.Attraction;
import com.travel.common.exception.AttractionNotFoundException;
import com.travel.common.result.PageResult;
import com.travel.knowledge.rag.RagDispatcher;
import com.travel.knowledge.rag.QueryIntent;
import com.travel.knowledge.rag.QueryUnderstandingService;
import com.travel.knowledge.rag.SearchResult;
import com.travel.knowledge.repository.AttractionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

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

    /**
     * 分页查询景点
     */
    public PageResult<Attraction> list(String city, String type, int page, int size) {
        LambdaQueryWrapper<Attraction> wrapper = new LambdaQueryWrapper<>();
        if (city != null && !city.isBlank()) {
            wrapper.eq(Attraction::getCity, city);
        }
        if (type != null && !type.isBlank()) {
            wrapper.eq(Attraction::getType, type);
        }
        wrapper.orderByDesc(Attraction::getRating);
        Page<Attraction> p = attractionMapper.selectPage(new Page<>(page, size), wrapper);
        return PageResult.of(p.getRecords(), p.getTotal(), page, size);
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
        QueryIntent intent = queryUnderstandingService.understand(query);
        return ragDispatcher.dispatch(ragType, intent, topK);
    }
}
