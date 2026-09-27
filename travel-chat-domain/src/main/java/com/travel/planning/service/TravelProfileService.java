package com.travel.planning.service;

import com.travel.common.entity.TravelProfile;
import com.travel.memory.longterm.ProfilePort;
import com.travel.memory.longterm.behavior.InferredStylePort;

/**
 * TravelProfileService 公共契约（AD-2b 接口化）。
 *
 * <p>实现见 {@link TravelProfileServiceImpl}（@Service 在 Impl）；继承既有双端口
 * {@link ProfilePort}/{@link InferredStylePort}（getOrCreate/recordTrip/update×2/
 * applyInferredStyle 契约经继承保位，bean 双端口可赋值性零变），本接口补声明
 * getByUserId 只读查询。包私有注入点（setSlotEnrichPort/setSlotEnrichEnabled）
 * 留守实现类不入公共契约。</p>
 */
public interface TravelProfileService extends ProfilePort, InferredStylePort {

    /** 按用户取画像（存在即返回不创建——getOrCreate 的只读形态）。 */
    TravelProfile getByUserId(Long userId);
}
