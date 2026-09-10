// 重构 R2：兼容 re-export 壳。消费方 import 路径不变；新代码请直接 import '@/lib/api/<域>'。
export * from './api/http';
export * from './api/admin';
export * from './api/anchor';
export * from './api/attraction';
export * from './api/auth';
export * from './api/chat';
export * from './api/itinerary';
export * from './api/knowledge';
export * from './api/model';
export * from './api/planning';
export * from './api/share';
export * from './api/user';
