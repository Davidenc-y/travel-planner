'use client';

import { useEffect, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import { User, LogOut, MapPin, Calendar, Route, Camera } from 'lucide-react';
import { toast } from 'sonner';
import { itineraryApi, userApi, getErrorMessage } from '@/lib/api';
import { useAuth } from '@/lib/auth-context';
import { UserAvatar } from '@/components/ui/user-avatar';

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

  const handleAvatarChange = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
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

  if (!isAuthenticated) {
    router.replace('/');
    return null;
  }

  return (
    <div className="max-w-2xl mx-auto">
      <h1 className="text-2xl font-bold mb-6">个人中心</h1>

      <div className="glass rounded-2xl p-6 mb-4">
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
            <p className="text-sm text-slate-400">用户 ID: {userId}</p>
            {email ? (
              <p className="text-sm text-slate-400">{email}</p>
            ) : (
              <div className="mt-1 max-w-sm">
                <div className="flex gap-2">
                  <input
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
                    className="flex-1 px-3 py-1.5 rounded-lg border border-slate-200 dark:border-slate-700 bg-transparent focus:ring-2 focus:ring-brand-500 outline-none text-sm"
                  />
                  <button
                    type="button"
                    onClick={handleBindEmail}
                    disabled={emailSaving || !emailInput.trim() || !EMAIL_RE.test(emailInput.trim())}
                    className="px-3 py-1.5 rounded-lg bg-brand-500 text-white text-sm hover:bg-brand-600 disabled:opacity-50 magnetic"
                  >
                    {emailSaving ? '绑定中…' : '绑定邮箱'}
                  </button>
                </div>
                {emailError && (
                  <p className="text-xs text-red-500 mt-1" role="alert">{emailError}</p>
                )}
              </div>
            )}
          </div>
        </div>

          <div className="grid grid-cols-2 gap-3">
            <div className="bg-slate-50 dark:bg-slate-800/50 rounded-lg p-4">
              <Route className="h-5 w-5 text-brand-500 mb-1" />
              <p className="text-xs text-slate-400">我的行程</p>
              <p className="font-medium">{tripCount ?? '—'}</p>
            </div>
            <div className="bg-slate-50 dark:bg-slate-800/50 rounded-lg p-4">
              <Calendar className="h-5 w-5 text-brand-500 mb-1" />
              <p className="text-xs text-slate-400">账号状态</p>
              <p className="font-medium">活跃</p>
            </div>
          </div>
      </div>

      <div className="glass rounded-2xl p-6">
        <h3 className="font-semibold mb-3">快捷操作</h3>
        <div className="space-y-2">
          <button
            onClick={() => router.push('/itinerary')}
            className="w-full flex items-center gap-2 px-4 py-2.5 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors text-left"
          >
            <MapPin className="h-4 w-4 text-brand-500" />
            <span>我的行程</span>
          </button>
          <button
            onClick={() => router.push('/chat')}
            className="w-full flex items-center gap-2 px-4 py-2.5 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors text-left"
          >
            <User className="h-4 w-4 text-brand-500" />
            <span>规划对话</span>
          </button>
          <button
            onClick={logout}
            className="w-full flex items-center gap-2 px-4 py-2.5 rounded-lg hover:bg-red-50 dark:hover:bg-red-900/20 text-red-500 transition-colors text-left"
          >
            <LogOut className="h-4 w-4" />
            <span>退出登录</span>
          </button>
        </div>
      </div>
    </div>
  );
}

export default function ProfilePage() {
  return <ProfileContent />;
}
