import { redirect } from 'next/navigation';

/**
 * M23（E1/P-C，D-V8-2）：规划独立入口下线——常驻 307 重定向到聊天页。
 * 规划收敛为聊天会话内行程话题锚定（输入框左下角圆点按钮）。
 */
export default function PlanPage() {
  redirect('/chat');
}
