import type { MapPoint } from '@/types';

/** Leaflet 折线坐标元组（lat, lng）。 */
export type LatLngTuple = [number, number];

/** 后端高德点（lng,lat）→ Leaflet（lat,lng）。 */
export function toLatLng(p: MapPoint): LatLngTuple {
  return [p.lat, p.lng];
}

/** 后端 polyline（MapPoint[]）→ Leaflet 坐标数组；null/空返回 []。 */
export function polylineToLatLng(points?: MapPoint[] | null): LatLngTuple[] {
  return (points ?? []).map(toLatLng);
}

/** 球面距离（米），用于降级段文案与曲线弯曲量。 */
export function haversineMeters(a: MapPoint, b: MapPoint): number {
  const R = 6_371_000;
  const rad = (v: number) => (v * Math.PI) / 180;
  const dLat = rad(b.lat - a.lat);
  const dLng = rad(b.lng - a.lng);
  const h =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(rad(a.lat)) * Math.cos(rad(b.lat)) * Math.sin(dLng / 2) ** 2;
  return 2 * R * Math.asin(Math.min(1, Math.sqrt(h)));
}

/**
 * 真实路线不可用时的示意曲线（二次贝塞尔采样的确定性实现）。
 * 返回 Leaflet（lat,lng）序列，供虚线渲染区分“示意/真实”。
 */
export function buildFallbackCurve(
  a: MapPoint,
  b: MapPoint,
  samples = 24,
): LatLngTuple[] {
  if (samples < 2) {
    return [toLatLng(a), toLatLng(b)];
  }
  const dx = b.lng - a.lng;
  const dy = b.lat - a.lat;
  const chord = Math.max(Math.hypot(dx, dy), 1e-9);
  // 单位法向量（经纬度平面）；弯曲量取弦长 12%，中点为最大偏移
  const nx = -dy / chord;
  const ny = dx / chord;
  const bend = chord * 0.12;
  const result: LatLngTuple[] = [];
  for (let i = 0; i <= samples; i++) {
    const t = i / samples;
    const lat = a.lat + dy * t + Math.sin(Math.PI * t) * bend * ny;
    const lng = a.lng + dx * t + Math.sin(Math.PI * t) * bend * nx;
    result.push([lat, lng]);
  }
  return result;
}

/** 是否真实路网来源（可画实线）。 */
export function isRealRouteSource(source?: string | null): boolean {
  return source === 'AMAP' || source === 'CACHE';
}

/** 路线耗时格式化：秒 → “约 X 分钟”。 */
export function formatRouteDuration(seconds?: number | null): string {
  if (!seconds || seconds <= 0) {
    return '';
  }
  const minutes = Math.max(1, Math.round(seconds / 60));
  return `约 ${minutes} 分钟`;
}

/** M15-2：POI 六类配色（CULTURE 紫 / NATURE 绿 / FOOD 橙 / SHOPPING 金 / FAMILY 粉 / LEISURE 青）。 */
export const POI_TYPE_COLORS: Record<string, string> = {
  CULTURE: '#7c3aed',
  NATURE: '#16a34a',
  FOOD: '#ea580c',
  SHOPPING: '#d97706',
  FAMILY: '#db2777',
  LEISURE: '#0891b2',
};

/** 未知/缺失类型 → 中性灰。 */
export const POI_UNKNOWN_COLOR = '#64748b';

/** M15-2：景点类型颜色（大小写不敏感，未知回中性灰）。 */
export function poiTypeColor(type?: string | null): string {
  if (!type) return POI_UNKNOWN_COLOR;
  return POI_TYPE_COLORS[type.toUpperCase()] ?? POI_UNKNOWN_COLOR;
}

/** M15-2：类型显示标签（未知显示“其他”）。 */
export function poiTypeLabel(type?: string | null): string {
  if (!type) return '其他';
  return (
    {
      CULTURE: '文化',
      NATURE: '自然',
      FOOD: '美食',
      SHOPPING: '购物',
      FAMILY: '亲子',
      LEISURE: '休闲',
    } as Record<string, string>
  )[type.toUpperCase()] ?? '其他';
}

/** M15-2：交通方式图标与文案。 */
export function modeLabel(mode?: string | null): string {
  if (mode === 'WALKING') return '🚶 步行';
  if (mode === 'DRIVING') return '🚗 驾车';
  return '🚌 暂不支持';
}
