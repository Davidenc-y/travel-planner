package com.travel.planning.controller;

import com.travel.common.dto.ItineraryGenerateRequestDTO;
import com.travel.common.dto.ItineraryResponseDTO;
import com.travel.common.result.PageResult;
import com.travel.common.result.R;
import com.travel.planning.service.ItineraryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 行程接口
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/itineraries")
@RequiredArgsConstructor
public class ItineraryController {

    private final ItineraryService itineraryService;

    /**
     * 生成行程
     */
    @PostMapping("/generate")
    public R<ItineraryResponseDTO> generate(@Valid @RequestBody ItineraryGenerateRequestDTO req,
                                             @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        log.info("生成行程: destination={}, days={}", req.getDestination(), req.getDays());
        return R.ok(itineraryService.generate(req, userId != null ? userId : 0L));
    }

    /**
     * 查询行程详情
     */
    @GetMapping("/{id}")
    public R<ItineraryResponseDTO> getById(@PathVariable Long id) {
        return R.ok(itineraryService.getById(id));
    }

    /**
     * 分页查询用户行程
     */
    @GetMapping
    public R<PageResult<ItineraryResponseDTO>> list(
            @RequestParam Long userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return R.ok(itineraryService.listByUserId(userId, page, size));
    }

    /**
     * 删除行程
     */
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        itineraryService.delete(id);
        return R.ok();
    }
}
