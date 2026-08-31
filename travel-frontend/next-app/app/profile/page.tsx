'use client';

import { useEffect, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import { User, LogOut, MapPin, Mail, Camera } from 'lucide-react';
import { toast } from 'sonner';
import dynamic from 'next/dynamic';
import { itineraryApi, userApi, getErrorMessage } from '@/lib/api';
import { useAuth } from '@/lib/auth-context';
import { UserAvatar } from '@/components/ui/user-avatar';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { useCountUp } from '@/lib/use-count-up';
import { Skeleton } from '@/components/ui/skeleton';

// U1：使用统计（recharts 图表按需加载，refetch 随 range 切换）
const UsageStats = dynamic(
  () => import('@/components/feature/usage-stats').then((m) => m.UsageStats),
  {
    ssr: false,
    loading: () => (
      <div className="space-y-4">
        <Skeleton className="h-8 w-40" />
        <Skeleton className="h-24 w-full" />
        <Skeleton className="h-40 w-full" />
      </div>
    ),
  }
);

// M5-1：邮箱格式校验（与后端一致）
const EMAIL_RE = /^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$/;

function ProfileContent() {
  const { username, userId, isAuthenticated, logout, avatar, refreshUser } = useAuth();
  const router = useRouter();
  const [tripCount, setTripCount] = useState<number | null>(null);
  const [email, setEmail] = useState<string | null>(null);
  // M5-1：邮箱绑定表单（注册未填邮箱时展示）
  const [emailInput, setEmailInput] = useState('');
  const [emailError, setEmailError] = useState<string | null>(null);
  const [emailSaving, setEmailSaving] = useState(false);
  // M3-21：头像上传本地预览（上传成功/失败后清除，回退服务端头像）
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);
  const fileRef = useRef<HTMLInputElement>(null);
  // B3（04 §4.7）：行程数数字滚动
  const tripCountDisplay = useCountUp(tripCount ?? undefined);

  useEffect(() => {
    if (!isAuthenticated || userId == null) return;
    // F87：展示真实行程统计（用户面 GET /api/v1/itineraries）
    itineraryApi.list(userId, 1, 1)
      .then((res) => setTripCount(res.data.data.total))
      .catch(() => setTripCount(null));
    // F121：展示邮箱（头像由 AuthContext 统一管理）
    userApi.me()
      .then((res) => setEmail(res.data.data.email || null))
      .catch(() => {});
  }, [isAuthenticated, userId]);

  const [avatarUploading, setAvatarUploading] = useState(false);
  const handleAvatarChange = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file || avatarUploading) return; // S5：上传进行中防连点重复上传
    setAvatarUploading(true);
    setPreviewUrl(URL.createObjectURL(file));
    try {
      await userApi.uploadAvatar(file);
      toast.success('头像已更新');
      await refreshUser();
      setPreviewUrl(null);
    } catch (err) {
      setPreviewUrl(null);
      toast.error('头像上传失败: ' + getErrorMessage(err));
    } finally {
      setAvatarUploading(false);
      if (fileRef.current) fileRef.current.value = '';
    }
  };

  const handleEmailChange = (value: string) => {
    setEmailInput(value);
    const trimmed = value.trim();
    if (trimmed && !EMAIL_RE.test(trimmed)) {
      setEmailError('邮箱格式不正确');
    } else {
      setEmailError(null);
    }
  };

  const handleBindEmail = async () => {
    const value = emailInput.trim();
    if (!EMAIL_RE.test(value)) {
      setEmailError('邮箱格式不正确');
      return;
    }
    setEmailSaving(true);
    try {
      await userApi.updateEmail(value);
      setEmail(value);
      setEmailInput('');
      setEmailError(null);
      toast.success('邮箱绑定成功');
    } catch (err) {
      toast.error('邮箱绑定失败: ' + getErrorMessage(err));
    } finally {
      setEmailSaving(false);
    }
  };

  // B3（04 §4.7）：登录守卫移入 useEffect（渲染期副作用反模式在 PE-02 后会于预渲染期报错）
  useEffect(() => {
    if (!isAuthenticated) {
      router.replace('/');
    }
  }, [isAuthenticated, router]);

  return (
    <div className="max-w-3xl mx-auto">
      <h1 className="text-2xl font-bold mb-6">个人中心</h1>

      <div className="card p-6 mb-4">
        <div className="flex items-center gap-4 mb-6">
          {/* F121：真实头像 + 悬浮相机图标上传 */}
          <button
            type="button"
            onClick={() => fileRef.current?.click()}
            className="relative group"
            aria-label="更换头像"
          >
            <UserAvatar name={username} src={previewUrl || avatar} size="lg" />
            <span className="absolute inset-0 flex items-center justify-center rounded-full bg-black/40 text-white opacity-0 group-hover:opacity-100 transition-opacity">
              <Camera className="h-5 w-5" />
            </span>
          </button>
          <input
            ref={fileRef}
            type="file"
            accept="image/jpeg,image/png"
            className="hidden"
            onChange={handleAvatarChange}
          />
          <div>
            <h2 className="text-xl font-semibold">{username}</h2>
            <p className="text-sm text-ink-faint">用户 ID: {userId}</p>
            {email ? (
              <p className="text-sm text-ink-faint">{email}</p>
            ) : (
              <div className="mt-1 max-w-sm">
                <div className="flex gap-2">
                  <Input
                    type="email"
                    value={emailInput}
                    onChange={(e) => handleEmailChange(e.target.value)}
                    onBlur={() => {
                      if (emailInput.trim() && !EMAIL_RE.test(emailInput.trim())) {
                        setEmailError('邮箱格式不正确');
                      }
                    }}
                    placeholder="your@email.com"
                    aria-label="绑定邮箱"
                    error={emailError}
                    className="flex-1 px-3 py-1.5 text-sm"
                  />
                  <Button
                    onClick={handleBindEmail}
                    disabled={emailSaving || !emailInput.trim() || !EMAIL_RE.test(emailInput.trim())}
                    size="sm"
                    className="self-start mt-1"
                  >
                    {emailSaving ? '绑定中…' : '绑定邮箱'}
                  </Button>
                </div>
              </div>
            )}
          </div>
        </div>

        {/* B3（04 §4.7）：统计卡真实化——行程数（数字滚动）+ 邮箱绑定态（替代假数据"活跃"） */}
        <div className="grid grid-cols-2 gap-3">
          <div className="bg-surface-2 rounded-lg p-4">
            <MapPin className="h-5 w-5 text-brand-500 mb-1" />
            <p className="text-xs text-ink-faint">我的行程</p>
            <p className="font-medium">{tripCount == null ? '—' : tripCountDisplay}</p>
          </div>
          <div className="bg-surface-2 rounded-lg p-4">
            <Mail className="h-5 w-5 text-brand-500 mb-1" />
            <p className="text-xs text-ink-faint">邮箱</p>
            <p className="font-medium">{email ? '已绑定' : '未绑定'}</p>
          </div>
        </div>
      </div>

      {/* U1：使用统计（个人名片与快捷操作之间，参考 Z.ai 应用用量样式） */}
      <div className="mb-4">
        <UsageStats />
      </div>

      <div className="card p-6">
        <h3 className="font-semibold mb-3">快捷操作</h3>
        <div className="space-y-2">
          <button
            onClick={() => router.push('/itinerary')}
            className="w-full flex items-center gap-2 px-4 py-2.5 rounded-lg hover:bg-surface-2 transition-colors text-left focus-ring"
          >
            <MapPin className="h-4 w-4 text-brand-500" />
            <span>我的行程</span>
          </button>
          <button
            onClick={() => router.push('/chat')}
            className="w-full flex items-center gap-2 px-4 py-2.5 rounded-lg hover:bg-surface-2 transition-colors text-left focus-ring"
          >
            <User className="h-4 w-4 text-brand-500" />
            <span>规划对话</span>
          </button>
          <Button
            variant="danger-ghost"
            onClick={logout}
            className="w-full justify-start px-4 py-2.5"
          >
            <LogOut className="h-4 w-4" />
            <span>退出登录</span>
          </Button>
        </div>
      </div>
    </div>
  );
}

export default function ProfilePage() {
  return <ProfileContent />;
}
