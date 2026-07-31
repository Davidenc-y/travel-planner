'use client';

import { useRouter } from 'next/navigation';
import { User, LogOut, MapPin, Calendar } from 'lucide-react';
import { useAuth } from '@/lib/auth-context';
import { AuthProvider } from '@/lib/auth-context';

function ProfileContent() {
  const { username, userId, isAuthenticated, logout } = useAuth();
  const router = useRouter();

  if (!isAuthenticated) {
    router.push('/login');
    return null;
  }

  return (
    <div className="max-w-2xl mx-auto">
      <h1 className="text-2xl font-bold mb-6">个人中心</h1>

      <div className="glass rounded-2xl p-6 mb-4">
        <div className="flex items-center gap-4 mb-6">
          <div className="w-16 h-16 rounded-full bg-brand-500 text-white flex items-center justify-center text-2xl font-bold">
            {username?.charAt(0).toUpperCase() || 'U'}
          </div>
          <div>
            <h2 className="text-xl font-semibold">{username}</h2>
            <p className="text-sm text-slate-400">用户 ID: {userId}</p>
          </div>
        </div>

        <div className="grid grid-cols-2 gap-3">
          <div className="bg-slate-50 dark:bg-slate-800/50 rounded-lg p-4">
            <Calendar className="h-5 w-5 text-brand-500 mb-1" />
            <p className="text-xs text-slate-400">账号状态</p>
            <p className="font-medium">活跃</p>
          </div>
          <div className="bg-slate-50 dark:bg-slate-800/50 rounded-lg p-4">
            <MapPin className="h-5 w-5 text-brand-500 mb-1" />
            <p className="text-xs text-slate-400">会员等级</p>
            <p className="font-medium">普通用户</p>
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
  return (
    <AuthProvider>
      <ProfileContent />
    </AuthProvider>
  );
}
