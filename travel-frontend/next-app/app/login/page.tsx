'use client';

import { useState, useEffect } from 'react';
import { useRouter } from 'next/navigation';
import { toast } from 'sonner';
import { Loader2, LogIn, Eye, EyeOff } from 'lucide-react';
import { authApi, getErrorMessage } from '@/lib/api';
import { useAuth } from '@/lib/auth-context';
import { FormShell } from '@/components/ui/form-shell';
import { Input } from '@/components/ui/input';
import { Button } from '@/components/ui/button';

function LoginContent() {
  const router = useRouter();
  const { login, isAuthenticated } = useAuth();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [loading, setLoading] = useState(false);
  // B3（04 §4.8，D-14）：错误内联到 FormShell error 槽（保留 toast 移除双通道）
  const [error, setError] = useState<string | null>(null);
  // 密码可见性切换（纯前端）
  const [showPassword, setShowPassword] = useState(false);

  // F93：已登录（localStorage 有 token）时自动回跳原目标页（middleware 307 带 from 参数）
  useEffect(() => {
    if (isAuthenticated) {
      const from = new URLSearchParams(window.location.search).get('from');
      router.replace(from && from.startsWith('/') ? from : '/');
    }
  }, [isAuthenticated, router]);

  const handleLogin = async () => {
    if (!username || !password) {
      setError('请输入用户名和密码');
      return;
    }
    setLoading(true);
    setError(null);
    try {
      const res = await authApi.login(username, password);
      const data = res.data.data;
      login(data.accessToken, data.refreshToken, data.userId, data.username);
      toast.success('登录成功');
      // F97：整页跳转（cookie 已写入，服务端 middleware 必然放行），
      // 避免客户端 router 导航与 middleware 竞争导致"停留在登录页、需手动刷新"
      const from = new URLSearchParams(window.location.search).get('from');
      const target = from && from.startsWith('/') ? from : '/';
      window.location.href = target;
    } catch (err: unknown) {
      const message = '登录失败: ' + getErrorMessage(err);
      setError(message);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="min-h-screen flex items-center justify-center px-4">
      <div className="w-full max-w-sm">
        <FormShell
          icon={<LogIn className="h-7 w-7" />}
          title="登录"
          description="旅游行程智能规划助手"
          error={error}
          footer={
            <>
              <Button
                onClick={handleLogin}
                disabled={loading}
                size="lg"
                className="w-full"
              >
                {loading ? <Loader2 className="h-5 w-5 animate-spin" /> : '登录'}
              </Button>
              <p className="text-center text-sm text-ink-faint mt-4">
                没有账号？{' '}
                <a href="/register" className="text-brand-500 hover:underline">注册</a>
              </p>
            </>
          }
        >
          <div className="space-y-4">
            <div>
              <label htmlFor="login-username" className="text-sm font-medium mb-1 block">用户名</label>
              <Input
                id="login-username"
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                onKeyDown={(e) => e.key === 'Enter' && handleLogin()}
                placeholder="输入用户名"
                autoComplete="username"
              />
            </div>
            <div>
              <label htmlFor="login-password" className="text-sm font-medium mb-1 block">密码</label>
              <div className="relative">
                <Input
                  id="login-password"
                  type={showPassword ? 'text' : 'password'}
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  onKeyDown={(e) => e.key === 'Enter' && handleLogin()}
                  placeholder="输入密码"
                  autoComplete="current-password"
                  className="pr-10"
                />
                <button
                  type="button"
                  onClick={() => setShowPassword((v) => !v)}
                  aria-label={showPassword ? '隐藏密码' : '显示密码'}
                  className="absolute right-3 top-1/2 -translate-y-1/2 text-ink-faint hover:text-ink-secondary focus-ring rounded"
                >
                  {showPassword ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
                </button>
              </div>
            </div>
          </div>
        </FormShell>
      </div>
    </div>
  );
}

export default function LoginPage() {
  return <LoginContent />;
}
