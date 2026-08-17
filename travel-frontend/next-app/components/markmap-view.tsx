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

export function MarkmapView({ data }: MarkmapViewProps) {
  const ref = useRef<HTMLDivElement>(null);
  const instanceRef = useRef<any>(null);

  useEffect(() => {
    let cancelled = false;

    async function render() {
      if (!ref.current) return;

      const { Transformer } = await import('markmap-lib');
      const { Markmap } = await import('markmap-view');

      if (cancelled || !ref.current) return;

      const md = toMarkdown(data);
      const transformer = new Transformer();
      const { root } = transformer.transform(md);

      if (!instanceRef.current) {
        instanceRef.current = Markmap.create(ref.current, {}, root);
      } else {
        instanceRef.current.setData(root);
        instanceRef.current.fit();
      }
    }

    render();

    return () => {
      cancelled = true;
    };
  }, [data]);

  return <div ref={ref} className="w-full h-full" />;
}
