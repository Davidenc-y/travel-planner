'use client';

import { createContext, useContext, useEffect, useState, ReactNode } from 'react';
import { useRouter } from 'next/navigation';
import { authApi } from './api';
import { toast } from 'sonner';
import { isTokenExpired } from './token';

// F91：cookie 与 localStorage 双写，供 middleware.ts 路由守卫读取
function setAuthCookie(token: string) {
  document.cookie = `accessToken=${encodeURIComponent(token)}; path=/; max-age=86400; SameSite=Lax`;
}

function clearAuthCookie() {
  document.cookie = 'accessToken=; path=/; max-age=0; SameSite=Lax';
}

function clearLocalAuth() {
  localStorage.removeItem('accessToken');
  localStorage.removeItem('refreshToken');
  localStorage.removeItem('userId');
  localStorage.removeItem('username');
}

interface AuthContextType {
  userId: number | null;
  username: string | null;
  token: string | null;
  isAuthenticated: boolean;
  login: (token: string, refreshToken: string, userId: number, username: string) => void;
  logout: () => void;
}

const AuthContext = createContext<AuthContextType | undefined>(undefined);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [userId, setUserId] = useState<number | null>(null);
  const [username, setUsername] = useState<string | null>(null);
  const [token, setToken] = useState<string | null>(null);
  const [mounted, setMounted] = useState(false);
  const router = useRouter();

  useEffect(() => {
    setMounted(true);
    const savedToken = localStorage.getItem('accessToken');
    const savedUserId = localStorage.getItem('userId');
    const savedUsername = localStorage.getItem('username');
    if (savedToken && savedUserId) {
      // F95：进入任意页面先校验 accessToken 是否过期；过期则清理并回首页未登录态
      if (isTokenExpired(savedToken)) {
        clearLocalAuth();
        clearAuthCookie();
        toast.error('登录已过期，请重新登录');
        if (window.location.pathname !== '/') {
          router.replace('/');
        }
      } else {
        setToken(savedToken);
        setUserId(Number(savedUserId));
        setUsername(savedUsername);
        // F93：老会话（cookie 双写前登录）只有 localStorage，无 accessToken cookie，
        // middleware 读不到 cookie 会把 /itinerary 等 307 到登录页；挂载时同步补写 cookie。
        setAuthCookie(savedToken);
      }
    }
  }, []);

  const login = (token: string, refreshToken: string, userId: number, username: string) => {
    localStorage.setItem('accessToken', token);
    localStorage.setItem('refreshToken', refreshToken);
    localStorage.setItem('userId', String(userId));
    localStorage.setItem('username', username);
    setAuthCookie(token);
    setToken(token);
    setUserId(userId);
    setUsername(username);
  };

  const logout = () => {
    // F87：通知后端注销 Redis refreshToken（best-effort，失败仅清本地）
    if (typeof window !== 'undefined' && localStorage.getItem('accessToken')) {
      authApi.logout().catch(() => {});
    }
    clearLocalAuth();
    clearAuthCookie();
    setToken(null);
    setUserId(null);
    setUsername(null);
    router.push('/login');
  };

  if (!mounted) {
    return null;
  }

  return (
    <AuthContext.Provider value={{
      userId,
      username,
      token,
      isAuthenticated: !!token,
      login,
      logout,
    }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used within AuthProvider');
  return ctx;
}
