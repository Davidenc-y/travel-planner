'use client';

import { useState } from 'react';
import { toast } from 'sonner';
import { Loader2, UserPlus, Eye, EyeOff } from 'lucide-react';
import { authApi, getErrorMessage } from '@/lib/api';
import { useAuth } from '@/lib/auth-context';
import { FormShell } from '@/components/ui/form-shell';
import { Input } from '@/components/ui/input';
import { Button } from '@/components/ui/button';

function RegisterContent() {
  const { login } = useAuth();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [email, setEmail] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [showPassword, setShowPassword] = useState(false);

  const handleRegister = async () => {
    if (!username || !password) {
      setError('请输入用户名和密码');
      return;
    }
    setLoading(true);
    setError(null);
    try {
      const res = await authApi.register(username, password, email || undefined);
      const data = res.data.data;
      login(data.accessToken, data.refreshToken, data.userId, data.username);
      toast.success('注册成功');
      // B3（04 §4.8）：与登录对齐为整页跳转（R2 一致性：cookie 写入后 middleware 必然放行）
      window.location.href = '/';
    } catch (err: unknown) {
      setError('注册失败: ' + getErrorMessage(err));
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="min-h-screen flex items-center justify-center px-4">
      <div className="w-full max-w-sm">
        <FormShell
          icon={<UserPlus className="h-7 w-7" />}
          title="注册"
          description="创建你的旅游规划账号"
          error={error}
          footer={
            <>
              <Button
                onClick={handleRegister}
                disabled={loading}
                size="lg"
                className="w-full"
              >
                {loading ? <Loader2 className="h-5 w-5 animate-spin" /> : '注册'}
              </Button>
              <p className="text-center text-sm text-ink-faint mt-4">
                已有账号？{' '}
                <a href="/login" className="text-brand-500 hover:underline">登录</a>
              </p>
            </>
          }
        >
          <div className="space-y-4">
            <div>
              <label htmlFor="register-username" className="text-sm font-medium mb-1 block">用户名</label>
              <Input
                id="register-username"
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                placeholder="输入用户名"
                autoComplete="username"
              />
            </div>
            <div>
              <label htmlFor="register-password" className="text-sm font-medium mb-1 block">密码</label>
              <div className="relative">
                <Input
                  id="register-password"
                  type={showPassword ? 'text' : 'password'}
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  placeholder="输入密码"
                  autoComplete="new-password"
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
            <div>
              <label htmlFor="register-email" className="text-sm font-medium mb-1 block">邮箱（选填）</label>
              <Input
                id="register-email"
                type="email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                placeholder="your@email.com"
                autoComplete="email"
              />
            </div>
          </div>
        </FormShell>
      </div>
    </div>
  );
}

export default function RegisterPage() {
  return <RegisterContent />;
}
