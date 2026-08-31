import type { Metadata } from 'next';

export const metadata: Metadata = { title: '我的行程' };

export default function ItineraryLayout({ children }: { children: React.ReactNode }) {
  return children;
}
