import type { Metadata } from 'next';

export const metadata: Metadata = { title: '规划行程' };

export default function PlanLayout({ children }: { children: React.ReactNode }) {
  return children;
}
