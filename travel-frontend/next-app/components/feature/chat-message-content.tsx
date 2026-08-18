'use client';

import ReactMarkdown from 'react-markdown';

/** F92：聊天消息 Markdown 渲染（助手消息） */
export function ChatMessageContent({ content }: { content: string }) {
  return (
    <div className="prose prose-sm dark:prose-invert max-w-none prose-p:my-1 prose-ul:my-1">
      <ReactMarkdown>{content}</ReactMarkdown>
    </div>
  );
}
