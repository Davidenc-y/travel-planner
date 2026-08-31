'use client';

import { useEffect, useRef } from 'react';
import type { MindmapData } from '@/types';

interface MarkmapViewProps {
  data: MindmapData;
}

function toMarkdown(data: MindmapData): string {
  let md = `# ${data.title}\n`;
  if (data.destination) md += `\n## 📍 ${data.destination}\n`;
  if (data.days) md += `- 天数：${data.days} 天\n`;
  if (data.budget) md += `- 预算：${data.budget} 元\n`;

  for (const section of data.sections || []) {
    md += `\n## ${section.title}\n`;
    for (const item of section.items || []) {
      md += `- ${item}\n`;
    }
  }
  return md;
}

/**
 * C4/F1：思维导图渲染修复。
 * - 根因：Markmap.create 后未调用 fit()，内容按原始坐标（宽 9999px）绘制，
 *   视觉上堆叠在容器左上角成一列文字（卡片弹窗与详情页均受影响）；
 * - 修复：create/setData 后一律 fit()；ResizeObserver 监听容器尺寸变化
 *   （弹窗打开动画/窗口缩放/折叠）后重适配（rAF 节流）；
 * - 卸载 destroy()：修复反复开关弹窗的 markmap 实例与监听泄漏；
 * - { duration: 0 }：首帧直接到位（弹窗内不希望节点飞入动画）。
 */
export function MarkmapView({ data }: MarkmapViewProps) {
  const ref = useRef<HTMLDivElement>(null);
  const instanceRef = useRef<any>(null);

  useEffect(() => {
    let cancelled = false;
    let observer: ResizeObserver | undefined;
    let raf = 0;

    async function render() {
      if (!ref.current) return;

      const { Transformer } = await import('markmap-lib');
      const { Markmap } = await import('markmap-view');

      if (cancelled || !ref.current) return;

      const md = toMarkdown(data);
      const transformer = new Transformer();
      const { root } = transformer.transform(md);

      if (!instanceRef.current) {
        // C4/F1 根因修复：Markmap.create 第一参数必须是真正的 <svg> 元素——
        // 传入 div 时 style/g 被直接塞进 HTML 容器，SVG transform 不生效，
        // 内容按原始尺寸（宽数千 px）平铺成"一列文字"（运行时实证）。
        const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
        svg.setAttribute('class', 'markmap');
        svg.setAttribute('style', 'width: 100%; height: 100%;');
        ref.current.appendChild(svg);
        instanceRef.current = Markmap.create(svg, { duration: 0 }, root);
      } else {
        instanceRef.current.setData(root);
      }
      // 创建/更新后一律适配视口
      instanceRef.current?.fit();
    }

    render();

    if (ref.current && typeof ResizeObserver !== 'undefined') {
      observer = new ResizeObserver(() => {
        if (raf) return;
        raf = requestAnimationFrame(() => {
          raf = 0;
          instanceRef.current?.fit();
        });
      });
      observer.observe(ref.current);
    }

    return () => {
      cancelled = true;
      if (raf) cancelAnimationFrame(raf);
      observer?.disconnect();
      instanceRef.current?.destroy();
      instanceRef.current = null;
    };
  }, [data]);

  return <div ref={ref} className="w-full h-full" />;
}
