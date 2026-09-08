'use client';

import ChatPageContent from '@/components/chat/chat-page-content';

/** M28-17：薄壳入口（无 props，消除 TS71007 serializable-props 检查面）。 */
export default function ChatPage() {
  return <ChatPageContent />;
}
