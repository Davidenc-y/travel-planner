import type { ItineraryResponse } from '@/types';

/**
 * B3/B4（front_design 05 M6）：行程 → Markdown 文本（"复制为 Markdown"用）。
 * 纯前端序列化，dayPlans/mindmap 缺失时按现有字段降级。
 */
export function itineraryToMarkdown(data: ItineraryResponse): string {
  const lines: string[] = [];
  lines.push(`# ${data.title || '旅行行程'}`);
  lines.push('');
  lines.push(
    [
      `**目的地**：${data.destination ?? '—'}`,
      `**天数**：${data.days ?? '—'} 天`,
      data.estimatedCost != null ? `**估算费用**：¥${data.estimatedCost}` : null,
      data.generatedAt ? `**生成时间**：${data.generatedAt}` : null,
    ]
      .filter(Boolean)
      .join(' ｜ ')
  );

  if (data.dayPlans && data.dayPlans.length > 0) {
    lines.push('');
    lines.push('## 每日行程');
    for (const day of data.dayPlans) {
      lines.push('');
      lines.push(`### 第 ${day.day} 天${day.date ? `（${day.date}）` : ''}`);
      if (day.summary) lines.push(day.summary);
      if (day.transportMode) lines.push(`- 交通方式：${day.transportMode}`);
      if (day.attractions && day.attractions.length > 0) {
        for (const attr of day.attractions) {
          const note = attr.notes ? `（${attr.notes}）` : '';
          lines.push(`- ${attr.timeSlot ? `${attr.timeSlot} ` : ''}**${attr.name}**${note}`);
        }
      }
      if (day.hotelSuggestion) lines.push(`- 🏨 住宿建议：${day.hotelSuggestion}`);
    }
  }

  if (data.mindmap) {
    lines.push('');
    lines.push('## 行程要点');
    for (const section of data.mindmap.sections || []) {
      lines.push('');
      lines.push(`### ${section.title}`);
      for (const item of section.items || []) {
        lines.push(`- ${item}`);
      }
    }
  }

  lines.push('');
  lines.push('—— 由旅游行程智能规划助手生成');
  return lines.join('\n');
}
