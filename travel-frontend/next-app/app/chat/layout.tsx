import type { Metadata } from 'next';

export const metadata: Metadata = { title: '规划对话' };

export default function ChatLayout({ children }: { children: React.ReactNode }) {
  return children;
}
