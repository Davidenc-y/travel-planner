package com.travel.planning.memory.pipeline;

import java.util.List;

/**
 * AL-2c（GL-2）：薄语料城市止损话术（静态模板工具，零 LLM 零图流）。
 *
 * <p>格式对齐 SupervisorResponseFormatter 既有分节风格（编号列表+括注）；
 * 纯文案工具无状态——行为变更面仅当 gate 键开启且命中薄语料城市（默认关=E-33）。</p>
 */
public final class CityPrecheckMessages {

    private CityPrecheckMessages() {
    }

    /**
     * 薄语料城市模板直出：告知语料充实中 + 推荐有语料城市 TOP5。
     */
    public static String thinCorpusReply(String thinCity, List<String> richCities) {
        StringBuilder sb = new StringBuilder();
        sb.append("抱歉，「").append(thinCity)
                .append("」的景点语料还在充实中，暂时无法生成完整的行程推荐。\n");
        if (richCities != null && !richCities.isEmpty()) {
            sb.append("以下城市的语料更充足，推荐先看看：\n");
            int idx = 1;
            for (String city : richCities) {
                sb.append(idx++).append(". ").append(city).append('\n');
            }
        }
        sb.append("您也可以换一个城市，或稍后再来。");
        return sb.toString();
    }

    /**
     * AM-3（GM-4）：多城薄语料话术——"您提到的{A}、{B}等城市语料建设中"（方案 §2.3）
     * + 推荐语料城市清单（尾段与单参形态同构）。单城入参委托既有单参文案
     * （单城文案不变=P0⑳ 伴随约束——调用方无需按 size 分叉）。
     */
    public static String thinCorpusReply(List<String> thinCities, List<String> richCities) {
        if (thinCities == null || thinCities.isEmpty()) {
            throw new IllegalArgumentException("thinCities 不能为空");
        }
        if (thinCities.size() == 1) {
            return thinCorpusReply(thinCities.get(0), richCities);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("抱歉，您提到的").append(String.join("、", thinCities))
                .append("等城市语料建设中，暂时无法生成完整的行程推荐。\n");
        if (richCities != null && !richCities.isEmpty()) {
            sb.append("以下城市的语料更充足，推荐先看看：\n");
            int idx = 1;
            for (String city : richCities) {
                sb.append(idx++).append(". ").append(city).append('\n');
            }
        }
        sb.append("您也可以换一个城市，或稍后再来。");
        return sb.toString();
    }
}
