'use client';

import { useEffect, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import { User, LogOut, MapPin, Calendar, Route, Camera } from 'lucide-react';
import { toast } from 'sonner';
import { itineraryApi, userApi, getErrorMessage } from '@/lib/api';
import { useAuth } from '@/lib/auth-context';
import { UserAvatar } from '@/components/ui/user-avatar';

function ProfileContent() {
  const { username, userId, isAuthenticated, logout, avatar, refreshUser } = useAuth();
  const router = useRouter();
  const [tripCount, setTripCount] = useState<number | null>(null);
  const [email, setEmail] = useState<string | null>(null);
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
    try {
      await userApi.uploadAvatar(file);
      toast.success('头像已更新');
      await refreshUser();
    } catch (err) {
      toast.error('头像上传失败: ' + getErrorMessage(err));
    } finally {
      if (fileRef.current) fileRef.current.value = '';
    }
  };

  if (!isAuthenticated) {
    router.push('/login');
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
            <UserAvatar name={username} src={avatar} size="lg" />
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
            {email && <p className="text-sm text-slate-400">{email}</p>}
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
