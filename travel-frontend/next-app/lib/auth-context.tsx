'use client';

import { createContext, useContext, useEffect, useState, ReactNode } from 'react';
import { useRouter } from 'next/navigation';
import { authApi } from './api';

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
      setToken(savedToken);
      setUserId(Number(savedUserId));
      setUsername(savedUsername);
    }
  }, []);

  const login = (token: string, refreshToken: string, userId: number, username: string) => {
    localStorage.setItem('accessToken', token);
    localStorage.setItem('refreshToken', refreshToken);
    localStorage.setItem('userId', String(userId));
    localStorage.setItem('username', username);
    setToken(token);
    setUserId(userId);
    setUsername(username);
  };

  const logout = () => {
    // F87：通知后端注销 Redis refreshToken（best-effort，失败仅清本地）
    if (typeof window !== 'undefined' && localStorage.getItem('accessToken')) {
      authApi.logout().catch(() => {});
    }
    localStorage.removeItem('accessToken');
    localStorage.removeItem('refreshToken');
    localStorage.removeItem('userId');
    localStorage.removeItem('username');
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
