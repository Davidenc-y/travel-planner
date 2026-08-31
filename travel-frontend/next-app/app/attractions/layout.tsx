import type { Metadata } from 'next';

export const metadata: Metadata = { title: '景点发现' };

export default function AttractionsLayout({ children }: { children: React.ReactNode }) {
  return children;
}
