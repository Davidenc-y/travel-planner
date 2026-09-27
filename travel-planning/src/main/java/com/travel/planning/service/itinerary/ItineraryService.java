package com.travel.planning.service.itinerary;

import com.travel.common.dto.ItineraryGenerateRequestDTO;
import com.travel.common.dto.ItineraryResponseDTO;
import com.travel.common.result.PageResult;

/**
 * ItineraryService 公共契约（AD-2d 接口化）。
 *
 * <p>实现见 {@link ItineraryServiceImpl}（@Service 在 Impl）；公有方法签名逐字提取
 * （零语义变更）。包私有 static（resolveResumeFrom/sanitizeSnapshots/buildResumeMessage——
 * ItineraryResumeCoordinator 薄委托、仅测试直连）留守实现类不入公共契约。</p>
 */
public interface ItineraryService {

    /** 行程生成（M4 幂等 + M7 模型路由）。 */
    ItineraryResponseDTO generate(ItineraryGenerateRequestDTO req, Long userId);

    /** 断点续跑生成。 */
    ItineraryResponseDTO resume(Long id, Long userId);

    /** 按 id 查询（归属校验）。 */
    ItineraryResponseDTO getById(Long id, Long userId);

    /** 用户行程分页列表。 */
    PageResult<ItineraryResponseDTO> listByUserId(Long userId, int page, int size);

    /** 删除行程（归属校验）。 */
    void delete(Long id, Long userId);

    /** 分享态读取（免登录，share token 路径）。 */
    ItineraryResponseDTO getByIdForShare(Long id);

    /** 会话关联行程 id 列表。 */
    java.util.List<Long> findSessionItineraryIds(String sessionId);
}
