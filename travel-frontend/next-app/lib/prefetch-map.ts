/**
 * FE-P1a（20260912 前端专项）：路由→预取键映射纯数据表 + 查询纯函数。
 * PrefetchProvider 空闲调度按当前 pathname 查询本表决定预取集（接线属 FE-P1b）。
 * 键格式与既有消费端一致（takePrefetch 契约零改动）：
 *   itinerary:{page}:{size} ｜ chat:sessions ｜ attractions:browse:{page}:{size} ｜
 *   chat:models ｜ attractions:cities（AW-8：与页面 useApiQuery cacheKey 同名合流）
 */

interface PrefetchRoute {
  /** pathname 匹配规则（usePathname 结果不含 query；/itinerary* 通配列表与详情页） */
  pattern: RegExp;
  /** 命中后应预取的键（不含当前路由自身的首屏键） */
  keys: readonly string[];
}

export const PREFETCH_ROUTES: readonly PrefetchRoute[] = [
  // AW-8：登录后默认落点 / 也预取（此前 keysFor('/')=[] → 首页停留期零预取，用户主诉根源）
  { pattern: /^\/$/, keys: ['itinerary:1:8', 'chat:sessions', 'attractions:browse:1:12', 'chat:models', 'attractions:cities'] },
  { pattern: /^\/chat$/, keys: ['itinerary:1:8', 'chat:sessions', 'attractions:browse:1:12'] },
  { pattern: /^\/itinerary(\/|$)/, keys: ['chat:sessions', 'attractions:browse:1:12'] },
  { pattern: /^\/attractions$/, keys: ['itinerary:1:8'] },
  // AW-8：/profile 计数依赖行程列表
  { pattern: /^\/profile$/, keys: ['itinerary:1:8', 'chat:sessions'] },
  // /admin/reliability（重窗口聚合）与 /share（令牌态）显式不预取——预取会放大后端负载
];

/** 返回 pathname 对应的预取键列表（副本，可安全变更）；未纳管路由返回空数组。 */
export function keysFor(pathname: string): string[] {
  for (const route of PREFETCH_ROUTES) {
    if (route.pattern.test(pathname)) return [...route.keys];
  }
  return [];
}
