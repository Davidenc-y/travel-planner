package com.travel.planning.controller;

import com.travel.common.dto.ItineraryGenerateRequestDTO;
import com.travel.common.dto.ItineraryResponseDTO;
import com.travel.common.result.PageResult;
import com.travel.common.result.R;
import com.travel.core.stream.StreamPreflight;
import com.travel.core.stream.StreamRequest;
import com.travel.planning.map.model.ItineraryMapRouteResponse;
import com.travel.planning.map.service.ItineraryMapRouteService;
import com.travel.planning.service.ItineraryStreamingPipeline;
import com.travel.planning.service.ItineraryService;
import com.travel.planning.service.ItineraryVersionService;
import com.travel.planning.stream.ItineraryStreamProperties;
import com.travel.planning.stream.StreamErrorMapper;
import com.travel.webmvc.stream.SseStreamAdapter;
import com.travel.planning.util.AuthUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

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
    private final ItineraryStreamingPipeline itineraryStreamingPipeline;
    private final SseStreamAdapter sseStreamAdapter;
    private final ItineraryStreamProperties itineraryStreamProps;
    private final ItineraryVersionService itineraryVersionService;
    private final com.travel.planning.service.share.ShareTokenService shareTokenService;
    private final ItineraryMapRouteService itineraryMapRouteService;

    /**
     * 生成行程
     */
    @PostMapping("/generate")
    public R<ItineraryResponseDTO> generate(@Valid @RequestBody ItineraryGenerateRequestDTO req) {
        log.info("生成行程: destination={}, days={}", req.getDestination(), req.getDays());
        // M16-1：身份仅认 accessToken（UserContextHolder）
        return R.ok(itineraryService.generate(req, AuthUtils.resolveUserId()));
    }

    /**
     * M6-15 Item 4：行程流式生成（SSE）。
     *
     * <p>注意：成功路径直接返回 SseEmitter（禁止 ResponseEntity 包装，见 M6-4）。</p>
     */
    @PostMapping("/generate/stream")
    public Object generateStream(@Valid @RequestBody ItineraryGenerateRequestDTO req) {
        if (!itineraryStreamProps.isEnabled()) {
            return ResponseEntity.notFound().build();
        }
        Long userId = AuthUtils.resolveUserId();
        StreamRequest request = new StreamRequest("itinerary", userId, null, null,
                req.getClientRequestId(), Map.of("req", req), null);
        StreamPreflight pre = itineraryStreamingPipeline.preflight(request);
        if (!pre.ok()) {
            return ResponseEntity.status(StreamErrorMapper.httpStatus(pre.code()))
                    .body(R.fail(pre.code(), pre.message()));
        }
        SseEmitter emitter = sseStreamAdapter.toEmitter(
                itineraryStreamingPipeline.stream(request, pre),
                itineraryStreamProps.getTimeoutMs(),
                itineraryStreamProps.getKeepaliveMs());
        return emitter;
    }

    /**
     * M4-9/P1-5：断点续跑（仅 FAILED / 僵尸 GENERATING 可续；归属校验在 Service）。
     * 同步等待（交互形态与 generate 一致，无轮询）。
     */
    @PostMapping("/{id}/resume")
    public R<ItineraryResponseDTO> resume(@PathVariable Long id) {
        log.info("行程续跑: id={}", id);
        return R.ok(itineraryService.resume(id, AuthUtils.resolveUserId()));
    }

    /**
     * 查询行程详情
     */
    @GetMapping("/{id}")
    public R<ItineraryResponseDTO> getById(@PathVariable Long id) {
        return R.ok(itineraryService.getById(id, AuthUtils.resolveUserId()));
    }

    /**
     * M12：行程地图路线（真实路网/住宿锚点；高德调用由配额+限频+缓存治理）。
     */
    @GetMapping("/{id}/map-routes")
    public R<ItineraryMapRouteResponse> mapRoutes(@PathVariable Long id) {
        return R.ok(itineraryMapRouteService.mapRoutes(id, AuthUtils.resolveUserId()));
    }

    /**
     * 分页查询用户行程
     */
    @GetMapping
    public R<PageResult<ItineraryResponseDTO>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return R.ok(itineraryService.listByUserId(AuthUtils.resolveUserId(), page, size));
    }

    /**
     * 删除行程
     */
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        itineraryService.delete(id, AuthUtils.resolveUserId());
        return R.ok();
    }

    /** M11-1：行程历史版本列表（本人行程，按版本倒序）。 */
    @GetMapping("/{id}/versions")
    public R<java.util.List<java.util.Map<String, Object>>> versions(@PathVariable Long id) {
        return R.ok(itineraryVersionService.list(id, AuthUtils.resolveUserId()));
    }

    /** M11-1：行程历史版本详情（只读快照回看）。 */
    @GetMapping("/{id}/versions/{version}")
    public R<java.util.Map<String, Object>> version(@PathVariable Long id,
                                                     @PathVariable Integer version) {
        return R.ok(itineraryVersionService.detail(id, version, AuthUtils.resolveUserId()));
    }

    /**
     * M13-2e/M15-4：版本切换（选中版本即当前使用，固定版本总数不递增）。
     * 保留 /rollback 兼容别名。
     */
    @PostMapping("/{id}/versions/{version}/switch")
    public R<java.util.Map<String, Object>> switchVersion(
            @PathVariable Long id,
            @PathVariable Integer version) {
        Integer activeVersion = itineraryVersionService.switchTo(
                AuthUtils.resolveUserId(), id, version);
        return R.ok(java.util.Map.of(
                "itineraryId", id, "version", activeVersion, "activeVersion", activeVersion));
    }

    @PostMapping("/{id}/versions/{version}/rollback")
    public R<java.util.Map<String, Object>> rollback(
            @PathVariable Long id,
            @PathVariable Integer version) {
        Integer activeVersion = itineraryVersionService.rollbackTo(
                AuthUtils.resolveUserId(), id, version);
        return R.ok(java.util.Map.of(
                "itineraryId", id, "version", activeVersion, "activeVersion", activeVersion));
    }

    /**
     * M25（E5）：生成行程分享链接（本人行程；token 无状态 HMAC 签名，默认 7 天有效 ≤30 clamp）。
     */
    @PostMapping("/{id}/share")
    public R<java.util.Map<String, Object>> share(@PathVariable Long id) {
        Long userId = AuthUtils.resolveUserId();
        String token = shareTokenService.issue(userId, id);
        return R.ok(java.util.Map.of("token", token));
    }
}
