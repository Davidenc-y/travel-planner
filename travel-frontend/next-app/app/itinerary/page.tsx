'use client';

import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import Link from 'next/link';
import { toast } from 'sonner';
import { Loader2, MapPin, Calendar, DollarSign, Trash2, Plus } from 'lucide-react';
import { itineraryApi } from '@/lib/api';
import { useAuth } from '@/lib/auth-context';
import { AuthProvider } from '@/lib/auth-context';
import type { ItineraryResponse, PageResult } from '@/types';
import { formatCurrency, formatDate } from '@/lib/utils';

function ItineraryListContent() {
  const router = useRouter();
  const { userId, isAuthenticated } = useAuth();
  const [data, setData] = useState<PageResult<ItineraryResponse> | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!isAuthenticated) {
      router.push('/login');
      return;
    }
    if (userId) {
      loadData();
    }
  }, [userId, isAuthenticated]);

  const loadData = async () => {
    try {
      const res = await itineraryApi.list(userId!, 1, 20);
      setData(res.data.data);
    } catch (err: any) {
      toast.error('加载失败');
    } finally {
      setLoading(false);
    }
  };

  const handleDelete = async (id: number) => {
    if (!confirm('确定删除此行程？')) return;
    try {
      await itineraryApi.delete(id);
      toast.success('删除成功');
      loadData();
    } catch {
      toast.error('删除失败');
    }
  };

  if (loading) {
    return (
      <div className="flex justify-center items-center py-20">
        <Loader2 className="h-8 w-8 animate-spin text-brand-500" />
      </div>
    );
  }

  return (
    <div>
      <div className="flex items-center justify-between mb-6">
        <h1 className="text-2xl font-bold">我的行程</h1>
        <Link
          href="/"
          className="flex items-center gap-1.5 px-4 py-2 rounded-lg bg-brand-500 text-white text-sm font-medium hover:bg-brand-600 magnetic"
        >
          <Plus className="h-4 w-4" /> 新建行程
        </Link>
      </div>

      {data && data.list.length > 0 ? (
        <div className="grid gap-4 md:grid-cols-2">
          {data.list.map((item) => (
            <div
              key={item.id}
              className="glass rounded-xl p-5 hover:shadow-lg transition-all magnetic cursor-pointer"
              onClick={() => router.push(`/itinerary/${item.id}`)}
            >
              <div className="flex items-start justify-between">
                <div className="flex-1">
                  <h3 className="font-semibold text-lg mb-2">{item.title}</h3>
                  <div className="space-y-1 text-sm text-slate-500 dark:text-slate-400">
                    <p className="flex items-center gap-1.5">
                      <MapPin className="h-3.5 w-3.5" /> {item.destination}
                    </p>
                    <p className="flex items-center gap-1.5">
                      <Calendar className="h-3.5 w-3.5" /> {item.days} 天
                    </p>
                    <p className="flex items-center gap-1.5">
                      <DollarSign className="h-3.5 w-3.5" /> {formatCurrency(item.estimatedCost)}
                    </p>
                    <p className="text-xs">{formatDate(item.generatedAt)}</p>
                  </div>
                </div>
                <button
                  onClick={(e) => { e.stopPropagation(); handleDelete(item.id); }}
                  className="p-1.5 rounded-lg hover:bg-red-50 dark:hover:bg-red-900/20 text-slate-400 hover:text-red-500 transition-colors"
                >
                  <Trash2 className="h-4 w-4" />
                </button>
              </div>
            </div>
          ))}
        </div>
      ) : (
        <div className="text-center py-20 text-slate-400">
          <MapPin className="h-12 w-12 mx-auto mb-3 opacity-50" />
          <p>还没有行程，开始规划你的第一次旅行吧！</p>
        </div>
      )}
    </div>
  );
}

export default function ItineraryListPage() {
  return (
    <AuthProvider>
      <ItineraryListContent />
    </AuthProvider>
  );
}
