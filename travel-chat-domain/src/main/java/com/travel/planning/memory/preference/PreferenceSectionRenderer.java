package com.travel.planning.memory.preference;

import com.travel.common.dto.PreferenceTagsDTO;
import com.travel.planning.prompt.Markers;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * M23b（E4）：【本轮偏好约束】段确定性渲染器（零 LLM）。
 *
 * <p>语义（文档 3 §4.5/§4.6）：显式标签=本轮结构化约束，注入后位于
 * 【锚定行程】段之后（丢弃顺位最低=用户显式输入优先保留）；与锚定行程
 * 目的地冲突时附<b>确定性提示行</b>（非 LLM 判断），由 AI 按提示声明
 * "按新目的地重新规划"。</p>
 *
 * <p>显式标签<b>不自动写长期画像</b>（单次偏好≠长期偏好）。</p>
 */
@Component
public class PreferenceSectionRenderer {

    /** 无锚定时返回纯约束段；与锚定目的地冲突时追加确定性提示行。 */
    public String render(PreferenceTagsDTO tags, String anchoredDestination) {
        if (tags == null || isEmpty(tags)) {
            return "";
        }
        StringBuilder sb = new StringBuilder(Markers.PREFERENCE_TAGS).append('\n');
        boolean any = false;
        if (tags.getDestination() != null && !tags.getDestination().isBlank()) {
            sb.append("- 目的地:").append(tags.getDestination()).append('\n');
            any = true;
            // M23b（§4.6）：目的地冲突确定性提示（锚定段已含锚定行程目的地，二者同现时 AI 需声明取舍）
            if (anchoredDestination != null && !anchoredDestination.isBlank()
                    && !anchoredDestination.equals(tags.getDestination())) {
                sb.append("- 注意:用户本轮指定目的地(").append(tags.getDestination())
                  .append(")与锚定行程目的地(").append(anchoredDestination)
                  .append(")不同，请优先按本轮目的地处理并在回复开头说明\n");
            }
        }
        if (tags.getDays() != null) {
            sb.append("- 天数:").append(tags.getDays()).append('\n');
            any = true;
        }
        if (tags.getBudget() != null) {
            sb.append("- 预算:").append(tags.getBudget().toPlainString()).append('\n');
            any = true;
        }
        if (tags.getParty() != null && !tags.getParty().isBlank()) {
            sb.append("- 同行人:").append(tags.getParty()).append('\n');
            any = true;
        }
        if (tags.getInterests() != null && !tags.getInterests().isEmpty()) {
            sb.append("- 兴趣:").append(String.join("、", tags.getInterests())).append('\n');
            any = true;
        }
        if (tags.getStartDate() != null && !tags.getStartDate().isBlank()) {
            sb.append("- 出发日期:").append(tags.getStartDate()).append('\n');
            any = true;
        }
        return any ? sb.toString() : "";
    }

    /** 检索 query 拼接后缀（目的地+兴趣；供预检索 query 增强，空偏好返回空串）。 */
    public String querySuffix(PreferenceTagsDTO tags) {
        if (tags == null || isEmpty(tags)) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (tags.getDestination() != null && !tags.getDestination().isBlank()) {
            sb.append(' ').append(tags.getDestination());
        }
        if (tags.getInterests() != null) {
            for (String it : tags.getInterests()) {
                sb.append(' ').append(it);
            }
        }
        return sb.toString();
    }

    private boolean isEmpty(PreferenceTagsDTO tags) {
        return (tags.getDestination() == null || tags.getDestination().isBlank())
                && tags.getDays() == null
                && tags.getBudget() == null
                && (tags.getParty() == null || tags.getParty().isBlank())
                && (tags.getInterests() == null || tags.getInterests().isEmpty())
                && (tags.getStartDate() == null || tags.getStartDate().isBlank());
    }
}
