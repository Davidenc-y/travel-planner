package com.travel.planning.controller;

import com.travel.common.dto.ItineraryGenerateRequestDTO;
import com.travel.common.dto.ItineraryResponseDTO;
import com.travel.common.result.PageResult;
import com.travel.common.result.R;
import com.travel.planning.service.ItineraryService;
import com.travel.planning.util.AuthUtils;
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
        // F68/B3-2：身份来源优先 accessToken（UserContextHolder），其次 X-User-Id 头兜底
        return R.ok(itineraryService.generate(req, AuthUtils.resolveUserId(userId)));
    }

    /**
     * M4-9/P1-5：断点续跑（仅 FAILED / 僵尸 GENERATING 可续；归属校验在 Service）。
     * 同步等待（交互形态与 generate 一致，无轮询）。
     */
    @PostMapping("/{id}/resume")
    public R<ItineraryResponseDTO> resume(@PathVariable Long id,
                                          @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        log.info("行程续跑: id={}", id);
        return R.ok(itineraryService.resume(id, AuthUtils.resolveUserId(userId)));
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
            @RequestParam(required = false) Long userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return R.ok(itineraryService.listByUserId(AuthUtils.resolveUserId(userId), page, size));
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
