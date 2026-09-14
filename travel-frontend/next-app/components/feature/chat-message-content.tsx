'use client';

import ReactMarkdown, { type Components } from 'react-markdown';
import { safeHref } from '@/lib/safeHref';

/**
 * F92：聊天消息 Markdown 渲染（助手消息）。
 * FE-S1a（20260912 前端专项）：链接协议白名单覆写——href 非 http(s)/mailto/# 开头
 * （safeHref 返回 null）时降级为纯文本 span，不再输出 <a>；白名单内保持默认锚点
 * 行为（不加 target/rel 等未授权属性）。
 */
const markdownComponents: Components = {
  a: ({ href, children }) => {
    const safe = safeHref(href);
    if (safe == null) {
      return <span className="break-all">{children}</span>;
    }
    return <a href={safe}>{children}</a>;
  },
};

/** F92：聊天消息 Markdown 渲染（助手消息） */
export function ChatMessageContent({ content }: { content: string }) {
  return (
    <div className="prose prose-sm dark:prose-invert max-w-none prose-p:my-1 prose-ul:my-1">
      <ReactMarkdown components={markdownComponents}>{content}</ReactMarkdown>
    </div>
  );
}
