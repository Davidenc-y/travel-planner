# travel-planning Prompt 模板清单（M3-20 / P1-17）

模板统一存放于 `resources/prompts/*.st`，由 `com.travel.planning.prompt.PromptTemplates`
从 classpath 加载并缓存；版本以 git 跟踪 + 本清单记录为准。

| 模板 | 用途 | 版本 |
|---|---|---|
| supervisor_system | Supervisor mainAgent 路由决策 system prompt（F82 硬性路由规则） | 1.0 |
| direct_recall_system | 直答兜底-回顾类（不得编造景点） | 1.0 |
| direct_answer_system | 直答兜底/入口直答-信息优先级（feedback > constraint > 画像） | 1.0 |
| recall_system | RECALL 回顾管线（骨架整理，不得增删景点） | 1.0 |
| agent_budget_system / instruction | 预算估算子 Agent（M3-7 模板） | 1.0 |
| agent_preference_system / instruction | 偏好分析子 Agent（含 get_user_profile/save_user_profile 工具指引） | 1.0 |
| agent_route_system / instruction | 路线编排子 Agent | 1.0 |
| agent_attraction_system / instruction | 景点筛选子 Agent（候选注入 + attraction_search 兜底） | 1.0 |
| preference_extract | F71 偏好确定性抽取（轻量模型，%s=用户消息） | 1.0 |
| intent_classify | F85 意图分类 LLM 兜底（%s=用户消息） | 1.0 |
| itinerary_user_input | 行程生成用户输入组装（%s/%d×6） | 1.0 |
| profile_history_compact | F? 画像历史行程压缩（%d=%s 上限字符） | 1.0 |
| session_summary | F55/B1 会话摘要（%d=%s tokens、%s=对话） | 1.0 |
| session_summary_validate | F58/B1.2 摘要语义保真校验（%s=%s 原对话/摘要） | 1.0 |
| rag_judge_relevance | M4-5a 注入相关性 Judge（%s/%s/%s=消息/会话知识段/候选景点段） | 1.0 |

占位符模板由调用方 `.formatted(...)` 填充；非占位符模板原样注入。
