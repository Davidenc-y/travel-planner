package com.travel.knowledge.etl;

import com.travel.core.data.MergeRules;
import com.travel.core.data.SourceConfidence;

/**
 * DG-2：字段合并策略单点（空值防护收口）。
 *
 * <p>统一口径：</p>
 * <ul>
 *   <li>身份字段（name/city/poiId）<b>永不覆盖</b>——仅旧值为空时以新值回填；</li>
 *   <li>数据字段仅当<b>新值非空</b>且（<b>旧值为空</b> 或 <b>新来源置信度 ≥ 旧来源</b>）才覆盖
 *       （"外部补充数据不被空值清空"的强形式）；</li>
 *   <li>web_enrich 回填的 7 天防抖为独立判定，保留在调用方（WebEnrichWritebackService），
 *       不入本策略。</li>
 * </ul>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
public final class FieldMergePolicy {

    private FieldMergePolicy() {
    }

    /**
     * 数据字段合并（导入 upsert 路径；语义 = MergeRules.choose：新值 null 保留旧值、
     * 旧值 null 取新值、否则新来源置信度 ≥ 旧来源时覆盖——严格等价迁移）。
     */
    public static <T> T choose(T oldVal, SourceConfidence oldConf, T newVal, SourceConfidence newConf) {
        return MergeRules.choose(oldVal, oldConf, newVal, newConf);
    }

    /**
     * 身份字段合并（name/city/poiId）：永不覆盖，仅旧值为空时以新值回填。
     */
    public static String chooseIdentity(String oldVal, String newVal) {
        if (oldVal == null && newVal != null) {
            return newVal;
        }
        return oldVal;
    }

    /**
     * 回填判定（web_enrich 等补充通道）：新值非空（字符串含空白拒绝）且
     * （旧值为空 或 新来源置信度 ≥ 旧来源）。旧值未知（由回填 SQL 空值保护
     * WHERE isNull 兜底）时传 null，按"允许尝试"处理。
     */
    public static boolean shouldOverwrite(String field, Object oldVal, Object newVal,
                                          SourceConfidence oldSrc, SourceConfidence newSrc) {
        if (newVal == null || (newVal instanceof String s && s.isBlank())) {
            return false;
        }
        if (oldVal == null || (oldVal instanceof String s && s.isBlank())) {
            return true;
        }
        if (oldSrc == null) {
            return true;
        }
        return newSrc != null && newSrc.level() >= oldSrc.level();
    }
}
