'use client';

import { useEffect, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { toast } from 'sonner';
import { Loader2, MapPin, Calendar, DollarSign, Users, Sparkles } from 'lucide-react';
import { itineraryApi, getErrorMessage } from '@/lib/api';
import { useAuth } from '@/lib/auth-context';
import { generateUUID } from '@/lib/utils';
import { buildItineraryUrl } from '@/lib/url-guard';
import { PagedMultiSelect, PagedSingleSelect } from '@/components/ui/paged-options';
import { FormShell } from '@/components/ui/form-shell';
import { DestinationAutocomplete } from '@/components/plan/DestinationAutocomplete';

const schema = z.object({
  destination: z.string().min(1, '目的地不能为空'),
  days: z.number().min(1, '天数最少 1 天').max(30, '天数最多 30 天'),
  budget: z.number().optional(),
  interests: z.array(z.string()).optional(),
  party: z.string().optional(),
  startDate: z.string().optional(),
});

type FormData = z.infer<typeof schema>;

const interestOptions = ['文化', '自然', '美食', '购物', '亲子', '休闲'];
const partyOptions = ['独行', '情侣', '家庭', '朋友'];

function PlanPageContent() {
  const router = useRouter();
  const { isAuthenticated } = useAuth();
  const [loading, setLoading] = useState(false);
  const [selectedInterests, setSelectedInterests] = useState<string[]>([]);
  const [party, setParty] = useState<string | undefined>(undefined);
  // M6-16：行程流式状态
  const [streamPhase, setStreamPhase] = useState<'idle' | 'thinking' | 'streaming'>('idle');
  const [thinkingLines, setThinkingLines] = useState<string[]>([]);
  const [streamingText, setStreamingText] = useState('');
  const streamAbortRef = useRef<AbortController | null>(null);

  useEffect(() => () => streamAbortRef.current?.abort(), []);

  const { register, handleSubmit, setValue, getValues, watch, formState: { errors } } = useForm<FormData>({
    resolver: zodResolver(schema),
    defaultValues: { days: 3 },
  });

  // F97：预算非负校验（失焦检测）
  const handleBudgetBlur = () => {
    const v = getValues('budget');
    if (v != null && v < 0) {
      setValue('budget', 0);
      toast.warning('预算不能为负数，已自动重置为 0');
    }
  };

  const onSubmit = async (data: FormData) => {
    // F97：提交时兜底校验，负数直接重置并提示
    if (data.budget != null && data.budget < 0) {
      setValue('budget', 0);
      toast.warning('预算不能为负数，已自动重置为 0');
      return;
    }
    if (!isAuthenticated) {
      toast.info('请先登录');
      router.replace('/');
      return;
    }
    setLoading(true);
    setStreamPhase('thinking');
    setThinkingLines(['已提交，正在生成行程…']);
    setStreamingText('');
    const payload = {
      ...data,
      party: party || undefined,
      interests: selectedInterests,
      clientRequestId: generateUUID(),
    };
    const controller = new AbortController();
    streamAbortRef.current = controller;
    try {
      let doneId: number | undefined;
      let acc = '';
      await itineraryApi.generateStream(payload, controller.signal, {
        onThinking: (p) => {
          setStreamPhase('thinking');
          const msg = p.message;
          if (msg) {
            setThinkingLines((prev) => (prev.includes(msg) ? prev : [...prev, msg]));
          }
        },
        onToken: (p) => {
          if (p.text) {
            acc += p.text;
            setStreamPhase('streaming');
            setStreamingText(acc);
          }
        },
        onDone: (p) => {
          doneId = p.itineraryId;
        },
        onError: (p) => {
          const e: any = new Error(p.message || '行程生成失败');
          e.code = p.code;
          throw e;
        },
      });
      if (!doneId) {
        throw new Error('未返回行程 ID');
      }
      toast.success('行程生成成功！');
      router.push(buildItineraryUrl(doneId));
    } catch (err: any) {
      if (err?.name === 'AbortError') return;
      // M6-16：流式异常 → 回退 JSON generate
      try {
        const res = await itineraryApi.generate(payload);
        toast.success('行程生成成功！');
        router.push(buildItineraryUrl(res.data.data.id));
      } catch (err2: any) {
        toast.error('生成失败: ' + getErrorMessage(err2));
      }
    } finally {
      setLoading(false);
      setStreamPhase('idle');
      setThinkingLines([]);
      setStreamingText('');
      streamAbortRef.current = null;
    }
  };

  const toggleInterest = (interest: string) => {
    setSelectedInterests((prev) =>
      prev.includes(interest) ? prev.filter((i) => i !== interest) : [...prev, interest]
    );
  };

  return (
    <div className="max-w-2xl mx-auto">
      <div className="text-center mb-8 animate-slide-up">
        <div className="inline-flex items-center gap-2 px-4 py-1.5 rounded-full bg-brand-50 dark:bg-brand-900/30 text-brand-600 dark:text-brand-300 text-sm font-medium mb-4">
          <Sparkles className="h-4 w-4" />
          AI 驱动的智能行程规划
        </div>
        <h1 className="text-3xl font-bold mb-2">规划你的下一次旅行</h1>
        <p className="text-slate-500 dark:text-slate-400">输入偏好，AI 为你生成个性化行程</p>
      </div>

      <form onSubmit={handleSubmit(onSubmit)}>
        <FormShell
          footer={
            <button
              type="submit"
              disabled={loading}
              className="w-full py-3 rounded-lg bg-brand-500 text-white font-medium hover:bg-brand-600 disabled:opacity-50 transition-all magnetic flex items-center justify-center gap-2"
            >
              {loading ? (
                <>
                  <Loader2 className="h-5 w-5 animate-spin" /> AI 正在规划中...
                </>
              ) : (
                <>
                  <Sparkles className="h-5 w-5" /> 生成行程
                </>
              )}
            </button>
          }
        >
        <div className="space-y-5">
          <div>
          <label className="flex items-center gap-2 text-sm font-medium mb-1.5">
            <MapPin className="h-4 w-4 text-brand-500" /> 目的地
          </label>
          {/* M7-1：目的地输入 + 城市模糊匹配自动补全（可自由输入，选中回填城市名） */}
          <DestinationAutocomplete
            value={watch('destination') ?? ''}
            onChange={(v) => setValue('destination', v, { shouldValidate: true })}
            placeholder="例如：北京"
          />
          {errors.destination && <p className="text-red-500 text-xs mt-1">{errors.destination.message}</p>}
        </div>

        <div className="grid grid-cols-2 gap-4">
          <div>
            <label className="flex items-center gap-2 text-sm font-medium mb-1.5">
              <Calendar className="h-4 w-4 text-brand-500" /> 天数
            </label>
            <input
              type="number"
              {...register('days', { valueAsNumber: true })}
              className="w-full px-4 py-2.5 rounded-lg border border-slate-200 dark:border-slate-700 bg-transparent focus:ring-2 focus:ring-brand-500 outline-none"
            />
            {errors.days && <p className="text-red-500 text-xs mt-1">{errors.days.message}</p>}
          </div>
          <div>
            <label className="flex items-center gap-2 text-sm font-medium mb-1.5">
              <DollarSign className="h-4 w-4 text-brand-500" /> 预算（元）
            </label>
            <input
              type="number"
              {...register('budget', { valueAsNumber: true })}
              min={0}
              onBlur={handleBudgetBlur}
              placeholder="不限"
              className="w-full px-4 py-2.5 rounded-lg border border-slate-200 dark:border-slate-700 bg-transparent focus:ring-2 focus:ring-brand-500 outline-none"
            />
          </div>
        </div>

        {/* F99：兴趣标签——分页下拉多选（默认每页 10 条，承载未来大数据量） */}
        <div>
          <label className="text-sm font-medium mb-1.5 block">兴趣标签</label>
          <PagedMultiSelect
            options={interestOptions.map((i) => ({ value: i }))}
            selected={selectedInterests}
            onToggle={toggleInterest}
            placeholder="选择兴趣标签（可多选）"
            defaultPageSize={10}
          />
        </div>

        {/* F99：出行人员——分页下拉单选（再次点击可取消，非必填） */}
        <div>
          <label className="flex items-center gap-2 text-sm font-medium mb-1.5">
            <Users className="h-4 w-4 text-brand-500" /> 出行人员
          </label>
          <PagedSingleSelect
            options={partyOptions.map((p) => ({ value: p }))}
            value={party}
            onChange={setParty}
            placeholder="选择出行人员（选填，可取消）"
            defaultPageSize={10}
          />
        </div>

        <div>
          <label className="text-sm font-medium mb-1.5 block">开始日期（选填）</label>
          <input
            type="date"
            {...register('startDate')}
            className="w-full px-4 py-2.5 rounded-lg border border-slate-200 dark:border-slate-700 bg-transparent focus:ring-2 focus:ring-brand-500 outline-none"
          />
        </div>

        </div>
        </FormShell>
      </form>

      {/* M6-16：行程流式生成进度与内容预览 */}
      {streamPhase !== 'idle' && (
        <div className="mt-4 glass rounded-xl p-4">
          {streamPhase === 'thinking' && (
            <div className="flex items-center gap-2 text-sm text-slate-500 dark:text-slate-400">
              <Loader2 className="h-4 w-4 animate-spin" />
              行程规划中…
            </div>
          )}
          {thinkingLines.length > 0 && (
            <div className="mt-2 space-y-1">
              {thinkingLines.map((line, idx) => (
                <p key={idx} className="text-xs text-slate-500/80 dark:text-slate-400/80">
                  {line}
                </p>
              ))}
            </div>
          )}
          {streamingText && (
            <pre className="mt-3 whitespace-pre-wrap text-sm max-h-72 overflow-y-auto">
              {streamingText}
            </pre>
          )}
        </div>
      )}
    </div>
  );
}

export default function PlanPage() {
  return <PlanPageContent />;
}
