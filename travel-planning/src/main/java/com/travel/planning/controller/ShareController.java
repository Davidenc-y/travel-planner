package com.travel.planning.controller;

import com.travel.common.dto.ItineraryResponseDTO;
import com.travel.common.result.R;
import com.travel.planning.service.ItineraryService;
import com.travel.planning.service.share.ShareTokenService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * M25（E5）：行程分享公开只读端点（无认证——授权=不可伪造的签名 token；
 * 经 M22 permit-paths 豁免 + /api/** 匿名限流面）。
 *
 * <p>脱敏：返回 ItineraryResponseDTO（不含用户字段）；不做坐标装饰（分享页无地图）。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/share")
@RequiredArgsConstructor
public class ShareController {

    private final ShareTokenService shareTokenService;
    private final ItineraryService itineraryService;

    @GetMapping("/{token}")
    public R<ItineraryResponseDTO> getShared(@PathVariable String token) {
        Long itineraryId = shareTokenService.parse(token)
                .orElseThrow(() -> new com.travel.common.exception.BusinessException(40401, "分享链接无效或已过期"));
        return R.ok(itineraryService.getByIdForShare(itineraryId));
    }
}
