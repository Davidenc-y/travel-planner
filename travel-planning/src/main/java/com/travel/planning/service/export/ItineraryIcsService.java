package com.travel.planning.service.export;

import com.travel.common.entity.Itinerary;
import com.travel.common.exception.BusinessException;
import com.travel.planning.repository.ItineraryMapper;
import com.travel.planning.service.ItineraryDtoAssembler;
import com.travel.common.dto.ItineraryResponseDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * M27（E7）：行程导出 .ics（iCalendar 2.0 / RFC 5545）——确定性纯生成，零 LLM。
 *
 * <p>数据源单源：dayPlans 复用 {@link ItineraryDtoAssembler} 解析（与详情页同源）；
 * 日期解析优先级：day.date（yyyy-MM-dd）→ 行程 startDate + 第 N 天 → 明天起顺序排天
 * （与 M26-F4 的"禁止编造过去日期"约束一致，导出事件不早于明天，除非 day.date 显式给定）。</p>
 *
 * <p>规范要点：CRLF 行结尾、UTF-8、75 八位组折行、TEXT 值转义（反斜杠/分号/逗号/换行）；
 * timeSlot 形如 "14:00-17:00" 解析为定时事件（浮动本地时间），缺失/非法降级为全天事件。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ItineraryIcsService {

    private static final DateTimeFormatter ICS_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter ICS_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");
    private static final int MAX_OCTETS = 75;

    private final ItineraryMapper itineraryMapper;
    private final ItineraryDtoAssembler dtoAssembler;

    /**
     * 导出本人行程的 .ics 字节（UTF-8）。
     * 归属语义与详情端点一致：不存在 40401 / 非本人 40302。
     */
    public byte[] exportIcs(Long userId, Long itineraryId) {
        Itinerary entity = itineraryMapper.selectById(itineraryId);
        if (entity == null) {
            throw new BusinessException(40401, "行程不存在: " + itineraryId);
        }
        if (!userId.equals(entity.getUserId())) {
            throw new BusinessException(40302, "无权访问该行程");
        }
        return generate(entity, dtoAssembler.toResponseDTO(entity, false))
                .getBytes(StandardCharsets.UTF_8);
    }

    /** 纯生成（供单测直接调用）：entity 提供元信息，dto 提供解析后的 dayPlans。 */
    public String generate(Itinerary entity, ItineraryResponseDTO dto) {
        LocalDate fallbackBase = resolveFallbackBase(entity.getStartDate());
        List<String> lines = new ArrayList<>();
        lines.add("BEGIN:VCALENDAR");
        lines.add("VERSION:2.0");
        lines.add("PRODID:-//TravelPlanner//Itinerary Export//CN");
        lines.add("CALSCALE:GREGORIAN");
        lines.add("METHOD:PUBLISH");
        lines.add("X-WR-CALNAME:" + escape(firstNonBlank(entity.getTitle(),
                entity.getDestination() + (entity.getDays() != null ? entity.getDays() + "日游" : "行程"))));
        String stamp = ICS_STAMP.format(java.time.LocalDateTime.now().withNano(0));

        List<ItineraryResponseDTO.DayPlan> days = dto != null && dto.getDayPlans() != null
                ? dto.getDayPlans() : List.of();
        if (days.isEmpty()) {
            // 无可解析日程（内容缺失/损坏）：整程一个全天事件，仍可导入日历占位
            int span = entity.getDays() != null && entity.getDays() > 0 ? entity.getDays() : 1;
            appendAllDay(lines, 0, fallbackBase, span,
                    escape(firstNonBlank(entity.getTitle(), "行程占位")),
                    "（行程内容暂无可解析的日程安排）", stamp, entity.getId());
        } else {
            int seq = 0;
            for (ItineraryResponseDTO.DayPlan day : days) {
                seq++;
                LocalDate date = resolveDayDate(day, fallbackBase);
                String summary = daySummary(seq, day);
                String description = dayDescription(day);
                List<int[]> slots = timeSlots(day);
                if (slots.isEmpty()) {
                    appendAllDay(lines, seq, date, 1, escape(summary), escape(description), stamp, entity.getId());
                } else {
                    for (int[] slot : slots) {
                        appendTimed(lines, seq, date, slot[0], slot[1],
                                escape(summary), escape(description), stamp, entity.getId());
                    }
                }
            }
        }
        lines.add("END:VCALENDAR");
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(fold(line)).append("\r\n");
        }
        return sb.toString();
    }

    // ==================== 事件装配 ====================

    private void appendAllDay(List<String> lines, int seq, LocalDate date, int spanDays,
                              String summary, String description, String stamp, Long itineraryId) {
        lines.add("BEGIN:VEVENT");
        lines.add("UID:itinerary-" + itineraryId + "-day-" + seq + "@travel-planner");
        lines.add("DTSTAMP:" + stamp);
        lines.add("DTSTART;VALUE=DATE:" + ICS_DATE.format(date));
        lines.add("DTEND;VALUE=DATE:" + ICS_DATE.format(date.plusDays(spanDays)));
        lines.add("SUMMARY:" + summary);
        lines.add("DESCRIPTION:" + description);
        lines.add("END:VEVENT");
    }

    private void appendTimed(List<String> lines, int seq, LocalDate date, int startMin, int endMin,
                             String summary, String description, String stamp, Long itineraryId) {
        lines.add("BEGIN:VEVENT");
        lines.add("UID:itinerary-" + itineraryId + "-day-" + seq + "-" + startMin + "@travel-planner");
        lines.add("DTSTAMP:" + stamp);
        lines.add("DTSTART:" + ICS_DATE.format(date) + "T" + hhmmss(startMin));
        lines.add("DTEND:" + ICS_DATE.format(date) + "T" + hhmmss(Math.max(endMin, startMin + 1)));
        lines.add("SUMMARY:" + summary);
        lines.add("DESCRIPTION:" + description);
        lines.add("END:VEVENT");
    }

    private String daySummary(int seq, ItineraryResponseDTO.DayPlan day) {
        String head = "第" + seq + "天";
        if (day.getAttractions() != null && !day.getAttractions().isEmpty()) {
            StringBuilder sb = new StringBuilder(head);
            for (int i = 0; i < day.getAttractions().size() && i < 3; i++) {
                sb.append(i == 0 ? " " : "·").append(day.getAttractions().get(i).getName());
            }
            if (day.getAttractions().size() > 3) {
                sb.append("等").append(day.getAttractions().size()).append("处");
            }
            return sb.toString();
        }
        return head + (day.getSummary() != null && !day.getSummary().isBlank()
                ? " " + day.getSummary().trim() : "");
    }

    private String dayDescription(ItineraryResponseDTO.DayPlan day) {
        StringBuilder sb = new StringBuilder();
        if (day.getSummary() != null && !day.getSummary().isBlank()) {
            sb.append(day.getSummary().trim());
        }
        if (day.getAttractions() != null) {
            for (ItineraryResponseDTO.AttractionVisit visit : day.getAttractions()) {
                if (sb.length() > 0) {
                    sb.append("\\n");
                }
                sb.append(visit.getName() != null ? visit.getName() : "");
                if (visit.getTimeSlot() != null && !visit.getTimeSlot().isBlank()) {
                    sb.append("（").append(visit.getTimeSlot().trim()).append("）");
                }
                if (visit.getNotes() != null && !visit.getNotes().isBlank()) {
                    sb.append("：").append(visit.getNotes().trim());
                }
            }
        }
        return sb.length() > 0 ? sb.toString() : "（无详细安排）";
    }

    // ==================== 解析与工具 ====================

    /** day.date 优先；否则 fallbackBase + 第 N 天（N 为日程序号，调用方保证从 1 递增）。 */
    private LocalDate resolveDayDate(ItineraryResponseDTO.DayPlan day, LocalDate fallbackBase) {
        String date = day.getDate();
        if (date != null) {
            try {
                return LocalDate.parse(date.trim().substring(0, Math.min(10, date.trim().length())));
            } catch (Exception ignore) {
                // 非法日期格式按 fallback 处理
            }
        }
        int n = day.getDay() != null && day.getDay() > 0 ? day.getDay() : 1;
        return fallbackBase.plusDays(n - 1L);
    }

    /** 行程 startDate（yyyy-MM-dd 前缀容错）解析失败或缺省 → 明天（不早于明天）。 */
    private LocalDate resolveFallbackBase(String startDate) {
        if (startDate != null && startDate.length() >= 10) {
            try {
                return LocalDate.parse(startDate.trim().substring(0, 10));
            } catch (Exception ignore) {
                // 非法格式走默认
            }
        }
        return LocalDate.now().plusDays(1);
    }

    /** timeSlot 解析为 [startMin,endMin] 列表；支持 "14:00-17:00"、"09:30~12:00"、"14:00-17:00、19:00-21:00" 多段。 */
    private List<int[]> timeSlots(ItineraryResponseDTO.DayPlan day) {
        List<int[]> out = new ArrayList<>();
        if (day.getAttractions() == null) {
            return out;
        }
        for (ItineraryResponseDTO.AttractionVisit visit : day.getAttractions()) {
            String slot = visit.getTimeSlot();
            if (slot == null || slot.isBlank()) {
                continue;
            }
            for (String seg : slot.split("[、,，;；]")) {
                String[] parts = seg.trim().split("[-~～至]");
                if (parts.length == 2) {
                    int start = minutes(parts[0].trim());
                    int end = minutes(parts[1].trim());
                    if (start >= 0 && end > start && end <= 24 * 60) {
                        out.add(new int[]{start, end});
                    }
                }
            }
        }
        return out;
    }

    /** "14:00"/"14:30" → 分钟数；非法返回 -1。 */
    private int minutes(String hhmm) {
        String[] p = hhmm.split(":");
        if (p.length != 2) {
            return -1;
        }
        try {
            int h = Integer.parseInt(p[0].trim());
            int m = Integer.parseInt(p[1].trim());
            if (h < 0 || h > 24 || m < 0 || m > 59) {
                return -1;
            }
            return h * 60 + m;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private String hhmmss(int minutes) {
        return String.format("%02d%02d00", minutes / 60, minutes % 60);
    }

    /** RFC 5545 TEXT 转义：反斜杠、分号、逗号、换行。 */
    private String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\\", "\\\\")
                .replace(";", "\\;")
                .replace(",", "\\,")
                .replace("\r\n", "\\n")
                .replace("\r", "\\n")
                .replace("\n", "\\n");
    }

    /** 长行按 75 八位组折行（续行前缀空格；UTF-8 多字节不切断）。 */
    private String fold(String line) {
        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_OCTETS) {
            return line;
        }
        StringBuilder out = new StringBuilder();
        int lineStart = 0;
        while (lineStart < line.length()) {
            int end = lineStart;
            int used = 0;
            int limit = out.length() == 0 ? MAX_OCTETS : MAX_OCTETS - 1;
            while (end < line.length()) {
                int ch = line.codePointAt(end);
                // UTF-8 编码字节数：<0x80→1，<0x800→2，<0x10000→3，补充平面→4
                int chLen = ch >= 0x10000 ? 4 : ch >= 0x800 ? 3 : ch >= 0x80 ? 2 : 1;
                if (used + chLen > limit) {
                    break;
                }
                used += chLen;
                end += Character.charCount(ch);
            }
            if (out.length() > 0) {
                // 续行：CRLF + 空格前缀（RFC 5545 折行；前缀计入 75 八位组限额）
                out.append("\r\n ");
            }
            out.append(line, lineStart, end);
            lineStart = end;
            if (end >= line.length()) {
                break;
            }
        }
        return out.toString();
    }

    private String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }
}
