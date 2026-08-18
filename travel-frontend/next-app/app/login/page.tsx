'use client';

import { useState } from 'react';
import { useRouter } from 'next/navigation';
import { toast } from 'sonner';
import { Loader2, LogIn } from 'lucide-react';
import { useEffect } from 'react';
import { authApi, getErrorMessage } from '@/lib/api';
import { useAuth } from '@/lib/auth-context';

function LoginContent() {
  const router = useRouter();
  const { login, isAuthenticated } = useAuth();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [loading, setLoading] = useState(false);

  // F93：已登录（localStorage 有 token）时自动回跳原目标页（middleware 307 带 from 参数）
  useEffect(() => {
    if (isAuthenticated) {
      const from = new URLSearchParams(window.location.search).get('from');
      router.replace(from && from.startsWith('/') ? from : '/');
    }
  }, [isAuthenticated, router]);

  const handleLogin = async () => {
    if (!username || !password) {
      toast.error('请输入用户名和密码');
      return;
    }
    setLoading(true);
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
    } catch (err: any) {
      toast.error('登录失败: ' + getErrorMessage(err));
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="min-h-screen flex items-center justify-center px-4">
      <div className="w-full max-w-sm glass rounded-2xl p-8 animate-slide-up">
        <div className="text-center mb-6">
          <div className="inline-flex items-center justify-center w-14 h-14 rounded-2xl bg-brand-500 text-white mb-3">
            <LogIn className="h-7 w-7" />
          </div>
          <h1 className="text-2xl font-bold">登录</h1>
          <p className="text-sm text-slate-400 mt-1">旅游行程智能规划助手</p>
        </div>

        <div className="space-y-4">
          <div>
            <label className="text-sm font-medium mb-1 block">用户名</label>
            <input
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && handleLogin()}
              className="w-full px-4 py-2.5 rounded-lg border border-slate-200 dark:border-slate-700 bg-transparent focus:ring-2 focus:ring-brand-500 outline-none"
              placeholder="输入用户名"
            />
          </div>
          <div>
            <label className="text-sm font-medium mb-1 block">密码</label>
            <input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && handleLogin()}
              className="w-full px-4 py-2.5 rounded-lg border border-slate-200 dark:border-slate-700 bg-transparent focus:ring-2 focus:ring-brand-500 outline-none"
              placeholder="输入密码"
            />
          </div>
          <button
            onClick={handleLogin}
            disabled={loading}
            className="w-full py-2.5 rounded-lg bg-brand-500 text-white font-medium hover:bg-brand-600 disabled:opacity-50 magnetic flex items-center justify-center gap-2"
          >
            {loading ? <Loader2 className="h-5 w-5 animate-spin" /> : '登录'}
          </button>
          <p className="text-center text-sm text-slate-400">
            没有账号？{' '}
            <a href="/register" className="text-brand-500 hover:underline">注册</a>
          </p>
        </div>
      </div>
    </div>
  );
}

export default function LoginPage() {
  return <LoginContent />;
}
