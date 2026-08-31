'use client';

import { useEffect, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { toast } from 'sonner';
import { Loader2, MapPin, Calendar, DollarSign, Users, Sparkles, Check } from 'lucide-react';
import { itineraryApi, getErrorMessage, isAbortError, httpErrorCode } from '@/lib/api';
import { useAuth } from '@/lib/auth-context';
import { generateUUID } from '@/lib/utils';
import { buildItineraryUrl } from '@/lib/url-guard';
import { PagedMultiSelect, PagedSingleSelect } from '@/components/ui/paged-options';
import { FormShell } from '@/components/ui/form-shell';
import { DestinationAutocomplete } from '@/components/plan/DestinationAutocomplete';
import { ModelSelector } from '@/components/model/ModelSelector';
import { useModelPreference } from '@/hooks/useModelPreference';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { ChatMessageContent } from '@/components/feature/chat-message-content';
import { useThrottledValue } from '@/lib/use-throttled-value';
import { cn } from '@/lib/utils';

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

// B3（04 §4.2 / D-11）：生成进度阶段条——thinking 文案按关键词本地归入三阶段（纯展示）
const PLAN_STAGES = ['理解偏好', '检索知识', '编排行程'] as const;

function classifyStage(message: string): number {
  if (/知识|检索|召回|景点|向量/.test(message)) return 1;
  if (/行程|编排|导图|快照|汇总|整理/.test(message)) return 2;
  return 0;
}

function PlanPageContent() {
  const router = useRouter();
  const { isAuthenticated } = useAuth();
  // M7 Batch 3：用户模型偏好（'' = 智能默认）
  const modelPref = useModelPreference();
  const [loading, setLoading] = useState(false);
  const [selectedInterests, setSelectedInterests] = useState<string[]>([]);
  const [party, setParty] = useState<string | undefined>(undefined);
  // M6-16：行程流式状态
  const [streamPhase, setStreamPhase] = useState<'idle' | 'thinking' | 'streaming'>('idle');
  const [thinkingLines, setThinkingLines] = useState<string[]>([]);
  const [streamingText, setStreamingText] = useState('');
  const streamAbortRef = useRef<AbortController | null>(null);
  // D-11：流式预览 Markdown 渲染节流（与聊天 C-02 同守卫，09 §4.3）
  const throttledStreamText = useThrottledValue(streamingText, 120);

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
      ...(modelPref.model ? { model: modelPref.model } : {}),
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
    } catch (err: unknown) {
      if (isAbortError(err)) return;
      // M7 Batch 3：所选模型不可用 → 提示并回退智能默认（跳过同模型 JSON 重试）
      const code = httpErrorCode(err);
      if (code === 40005) {
        toast.error('所选模型不可用，已切换回智能默认');
        modelPref.select('');
        return;
      }
      // M6-16：流式异常 → 回退 JSON generate
      try {
        const res = await itineraryApi.generate(payload);
        toast.success('行程生成成功！');
        router.push(buildItineraryUrl(res.data.data.id));
      } catch (err2: unknown) {
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

  const stageIndex = thinkingLines.reduce(
    (acc, line) => Math.max(acc, classifyStage(line)),
    0
  );

  return (
    <div className="max-w-2xl mx-auto">
      <div className="text-center mb-8 animate-slide-up">
        <div className="inline-flex items-center gap-2 px-4 py-1.5 rounded-full bg-brand-50 dark:bg-brand-900/30 text-brand-600 dark:text-brand-300 text-sm font-medium mb-4">
          <Sparkles className="h-4 w-4" />
          AI 驱动的智能行程规划
        </div>
        <h1 className="text-3xl font-bold mb-2">规划你的下一次旅行</h1>
        <p className="text-ink-secondary">输入偏好，AI 为你生成个性化行程</p>
      </div>

      <form onSubmit={handleSubmit(onSubmit)}>
        {/* B3（04 §4.2）：生成中禁用表单区，避免成功跳转前的歧义修改 */}
        <fieldset disabled={loading} className="min-w-0 disabled:opacity-60">
        <FormShell
          footer={
            <Button
              type="submit"
              disabled={loading}
              size="lg"
              className="w-full"
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
            </Button>
          }
        >
        <div className="space-y-5">
          <div>
            <label className="flex items-center gap-2 text-sm font-medium mb-1.5">
              <Sparkles className="h-4 w-4 text-brand-500" /> 模型
            </label>
            {/* M7 Batch 3：模型选择（智能默认=不传 model，后端角色默认） */}
            <ModelSelector value={modelPref.model} onChange={modelPref.select} />
          </div>
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
          {errors.destination && <p className="text-danger text-xs mt-1">{errors.destination.message}</p>}
        </div>

        <div className="grid grid-cols-2 gap-4">
          <div>
            <label className="flex items-center gap-2 text-sm font-medium mb-1.5">
              <Calendar className="h-4 w-4 text-brand-500" /> 天数
            </label>
            <Input
              type="number"
              {...register('days', { valueAsNumber: true })}
            />
            {errors.days && <p className="text-danger text-xs mt-1">{errors.days.message}</p>}
          </div>
          <div>
            <label className="flex items-center gap-2 text-sm font-medium mb-1.5">
              <DollarSign className="h-4 w-4 text-brand-500" /> 预算（元）
            </label>
            <Input
              type="number"
              {...register('budget', { valueAsNumber: true })}
              min={0}
              onBlur={handleBudgetBlur}
              placeholder="不限"
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
          <Input
            type="date"
            {...register('startDate')}
          />
        </div>

        </div>
        </FormShell>
        </fieldset>
      </form>

      {/* M6-16 + 04 §4.2：生成进度——阶段步骤条 + 最新阶段文案 + Markdown 流式预览（打字光标） */}
      {streamPhase !== 'idle' && (
        <div className="card mt-4 p-4">
          <div className="flex items-center gap-1.5" aria-label="生成进度">
            {PLAN_STAGES.map((stage, idx) => (
              <div key={stage} className="flex items-center gap-1.5">
                <span
                  className={cn(
                    'flex h-5 w-5 items-center justify-center rounded-full text-[10px] font-bold transition-colors duration-base',
                    idx < stageIndex
                      ? 'bg-success-soft text-success'
                      : idx === stageIndex
                        ? 'bg-brand-500 text-white'
                        : 'bg-surface-2 text-ink-faint'
                  )}
                >
                  {idx < stageIndex ? <Check className="h-3 w-3" /> : idx + 1}
                </span>
                <span
                  className={cn(
                    'text-xs',
                    idx === stageIndex ? 'text-ink font-medium' : 'text-ink-faint'
                  )}
                >
                  {stage}
                </span>
                {idx < PLAN_STAGES.length - 1 && (
                  <span aria-hidden className="mx-1 h-px w-6 bg-line" />
                )}
              </div>
            ))}
          </div>

          {thinkingLines.length > 0 && (
            <div className="mt-3 flex items-center gap-2 text-sm text-ink-secondary">
              <Loader2 className="h-4 w-4 animate-spin" />
              {thinkingLines[thinkingLines.length - 1]}
            </div>
          )}
          {streamingText && (
            <div className="mt-3 max-h-72 overflow-y-auto rounded-lg bg-surface-2/60 p-3">
              <div className="text-sm prose prose-sm dark:prose-invert max-w-none prose-p:my-1 prose-ul:my-1">
                <ChatMessageContent content={throttledStreamText} />
                <span aria-hidden className="ml-0.5 inline-block h-4 w-0.5 bg-brand-500 animate-blink align-text-bottom" />
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  );
}

export default function PlanPage() {
  return <PlanPageContent />;
}
