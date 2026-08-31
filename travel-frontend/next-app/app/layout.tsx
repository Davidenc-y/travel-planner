import type { Metadata } from 'next';
import { Inter } from 'next/font/google';
import './globals.css';
import { ThemeProvider } from '@/components/theme-provider';
import { ClientLayout } from '@/components/client-layout';
import { AuthProvider } from '@/lib/auth-context';
import { ErrorBoundary } from '@/lib/error-boundary';
import { PrefetchProvider } from '@/components/prefetch-provider';
import { ConfirmProvider } from '@/components/ui/confirm-dialog';
import { Toaster } from 'sonner';

const inter = Inter({ subsets: ['latin'] });

export const metadata: Metadata = {
  title: {
    default: '旅游行程智能规划助手',
    template: '%s · 旅游行程智能规划助手',
  },
  description: '基于 AI 的旅游行程智能规划平台',
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="zh-CN" suppressHydrationWarning>
      <body className={inter.className}>
        <ThemeProvider attribute="class" defaultTheme="system" enableSystem>
          <ConfirmProvider>
            <AuthProvider>
              <ErrorBoundary>
                <PrefetchProvider />
                <ClientLayout>{children}</ClientLayout>
                <Toaster position="top-right" richColors />
              </ErrorBoundary>
            </AuthProvider>
          </ConfirmProvider>
        </ThemeProvider>
      </body>
    </html>
  );
}
