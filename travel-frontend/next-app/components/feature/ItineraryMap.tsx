'use client';

import { useEffect, useMemo, useRef, useState } from 'react';
import L from 'leaflet';
import 'leaflet/dist/leaflet.css';
import type {
  DailyWeather,
  DayMapRoute,
  DayPlan,
  MapPoint,
  MapRouteResponse,
  MapRouteSegment,
} from '@/types';
import { itineraryApi } from '@/lib/api';
import {
  buildFallbackCurve,
  formatRouteDuration,
  isRealRouteSource,
  modeLabel,
  poiTypeColor,
  poiTypeLabel,
  toLatLng,
} from '@/lib/itinerary-map-utils';

const DAY_COLORS = ['#dc2626', '#2563eb', '#16a34a', '#d97706', '#7c3aed', '#0891b2'];
const EMPTY_PLAN_LIST: DayPlan[] = [];
const EMPTY_ROUTE_DAYS: DayMapRoute[] = [];

interface TileSource {
  name: string;
  url: string;
  options: L.TileLayerOptions;
}

const TILE_SOURCES: TileSource[] = [
  {
    name: '高德',
    url: 'https://webrd0{s}.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}',
    options: {
      subdomains: ['1', '2', '3', '4'],
      maxZoom: 18,
      attribution: '© 高德地图',
    },
  },
  {
    name: 'OpenStreetMap',
    url: 'https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',
    options: {
      maxZoom: 18,
      attribution: '&copy; OpenStreetMap contributors',
    },
  },
];

/**
 * M11-2/M12：行程地图。
 *
 * - 底图优先高德栅格瓦片（无需 Key，当前网络可达），失败自动回退 OSM；
 * - 有 itineraryId 时请求 8081 map-routes：真实路网实线 / 示意曲线虚线 / 住宿锚点；
 * - 只渲染有 lat/lng 的景点；无坐标展示降级说明。
 */
export function ItineraryMap({
  dayPlans,
  itineraryId,
}: {
  dayPlans?: DayPlan[];
  itineraryId?: number | null;
}) {
  const [containerEl, setContainerEl] = useState<HTMLDivElement | null>(null);
  const [mapRoutes, setMapRoutes] = useState<MapRouteResponse | null>(null);
  const [routeLoading, setRouteLoading] = useState(false);
  const [routeError, setRouteError] = useState(false);
  const [activeDay, setActiveDay] = useState<number | null>(null);
  const [playing, setPlaying] = useState(false);
  const highlightLayers = useRef<{ day: number; layer: L.Path }[]>([]);

  const planList = dayPlans ?? EMPTY_PLAN_LIST;
  const routeDays = useMemo(
    () => mapRoutes?.days ?? EMPTY_ROUTE_DAYS,
    [mapRoutes],
  );
  const weatherList = mapRoutes?.weather ?? [];
  const usedTypes = useMemo(
    () =>
      [...new Set(
        planList
          .flatMap((day) => day.attractions ?? [])
          .map((a) => a.type)
          .filter((t): t is string => Boolean(t)),
      )],
    [planList],
  );
  const hasPoints = planList.some((day) =>
    (day.attractions ?? []).some(
      (a) => typeof a.latitude === 'number' && typeof a.longitude === 'number',
    ),
  );
  const dayNumbers = useMemo(() => planList.map((day) => day.day), [planList]);
  const prefersReducedMotion =
    typeof window !== 'undefined'
    && window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;

  useEffect(() => {
    if (itineraryId == null) {
      setMapRoutes(null);
      return undefined;
    }
    let cancelled = false;
    setMapRoutes(null);
    setRouteLoading(true);
    setRouteError(false);
    itineraryApi
      .mapRoutes(itineraryId)
      .then((res) => {
        if (cancelled) return;
        setMapRoutes(res.data.data ?? null);
        setRouteLoading(false);
      })
      .catch(() => {
        if (cancelled) return;
        setRouteError(true);
        setRouteLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [itineraryId]);

  // M15-2：按天播放高亮；prefers-reduced-motion 直接全量展示（不进入轮播）
  useEffect(() => {
    if (!playing || dayNumbers.length === 0) return undefined;
    if (window.matchMedia?.('(prefers-reduced-motion: reduce)').matches) {
      setActiveDay(null);
      setPlaying(false);
      return undefined;
    }
    let idx = 0;
    setActiveDay(dayNumbers[0]);
    const timer = window.setInterval(() => {
      idx += 1;
      if (idx >= dayNumbers.length) {
        idx = 0;
      }
      setActiveDay(dayNumbers[idx]);
    }, 1800);
    return () => window.clearInterval(timer);
  }, [playing, dayNumbers]);

  useEffect(() => {
    const el = containerEl;
    if (!el || !hasPoints) return undefined;

    const map = L.map(el, { scrollWheelZoom: false });
    let sourceIndex = 0;
    let tileErrors = 0;

    const createTile = (idx: number): L.TileLayer => {
      const source = TILE_SOURCES[idx];
      const layer = L.tileLayer(source.url, source.options).addTo(map);
      layer.on('tileerror', () => {
        tileErrors += 1;
        if (tileErrors >= 6 && sourceIndex < TILE_SOURCES.length - 1) {
          tileErrors = 0;
          sourceIndex += 1;
          layer.remove();
          createTile(sourceIndex);
        }
      });
      return layer;
    };
    createTile(sourceIndex);

    const latLngs: L.LatLngExpression[] = [];
    highlightLayers.current = [];

    planList.forEach((day, dayIdx) => {
      const dayRoute: DayMapRoute | undefined = routeDays.find((r) => r.day === day.day)
        ?? routeDays[dayIdx];
      const color = DAY_COLORS[dayIdx % DAY_COLORS.length];
      const dayPoints = (day.attractions ?? [])
        .filter((a) => typeof a.latitude === 'number' && typeof a.longitude === 'number')
        .map((a) => L.latLng(a.latitude as number, a.longitude as number));
      latLngs.push(...dayPoints);

      // 真实路网/示意路线段
      (dayRoute?.segments ?? []).forEach((segment) => {
        const path = drawSegment(map, segment, color, day.day);
        if (path) {
          highlightLayers.current.push({ day: day.day, layer: path });
        }
      });

      // 景点标记
      dayPoints.forEach((latlng, idx) => {
        const visit = (day.attractions ?? [])[idx];
        const pointColor = poiTypeColor(visit?.type);
        const marker = L.circleMarker(latlng, {
          radius: 7,
          color: pointColor,
          fillColor: pointColor,
          fillOpacity: 0.85,
        })
          .addTo(map)
          .bindTooltip(
            `第${day.day}天 · ${visit?.name ?? ''} · ${poiTypeLabel(visit?.type)}`,
          );
        highlightLayers.current.push({ day: day.day, layer: marker });
      });

      // 住宿锚点
      const hotel = dayRoute?.hotel;
      if (hotel?.point && hotel.source !== 'NO_TEXT') {
        const p = hotel.point;
        const hotelLatLng = L.latLng(p.lat, p.lng);
        latLngs.push(hotelLatLng);
        const icon = L.divIcon({
          className: '',
          html: '<div style="width:30px;height:30px;display:flex;align-items:center;justify-content:center;font-size:18px;background:rgba(255,255,255,.9);border:2px solid #7c3aed;border-radius:9999px;box-shadow:0 1px 4px rgba(0,0,0,.2)">🏨</div>',
          iconSize: [30, 30],
          iconAnchor: [15, 15],
        });
        L.marker(hotelLatLng, { icon })
          .addTo(map)
          .bindTooltip(
            `第${day.day}晚住宿${hotel.text ? `：${hotel.text}` : ''}`,
            { direction: 'top' },
          );
      }
    });

    if (latLngs.length > 1) {
      map.fitBounds(L.latLngBounds(latLngs), { padding: [32, 32] });
    } else if (latLngs.length === 1) {
      map.setView(latLngs[0], 14);
    }

    return () => {
      map.remove();
    };
    // points/route 变化都会重建图层；依赖 routeDays 与 planList 引用即可
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [containerEl, planList, routeDays, hasPoints]);

  // M15-2：按天高亮——非当前日路线/标点降透明；停止播放时全量显示
  useEffect(() => {
    for (const item of highlightLayers.current) {
      const active = activeDay === null || activeDay === item.day;
      if (item.layer instanceof L.CircleMarker) {
        item.layer.setStyle({
          opacity: active ? 1 : 0.15,
          fillOpacity: active ? 0.85 : 0.15,
        });
      } else {
        item.layer.setStyle({
          opacity: active ? 0.9 : 0.1,
        });
      }
    }
  }, [activeDay, routeDays, planList, hasPoints]);

  if (!hasPoints) {
    return (
      <div className="rounded-xl border border-line bg-surface p-6 text-center text-sm text-ink-faint">
        暂无可用坐标：部分景点库内缺少经纬度，可在地图上以文字列表查看
      </div>
    );
  }

  return (
    <div>
      <div className="mb-2 flex flex-wrap items-center gap-3 text-xs">
        <button
          type="button"
          onClick={() => {
            if (playing) {
              setPlaying(false);
              setActiveDay(null);
            } else {
              setActiveDay(dayNumbers[0] ?? null);
              setPlaying(true);
            }
          }}
          disabled={prefersReducedMotion}
          title={prefersReducedMotion ? '系统已开启减弱动态效果，直接展示全部日期' : undefined}
          className="rounded-lg border border-line bg-surface px-2.5 py-1 font-medium text-ink-strong"
        >
          {playing ? '⏸ 停止播放' : '▶ 按天播放'}
        </button>
        {planList.map((day, idx) => {
          const weather = weatherList[idx];
          return (
            <span key={day.day} className="inline-flex items-center gap-1.5">
              <span
                className="h-2.5 w-2.5 rounded-full"
                style={{ backgroundColor: DAY_COLORS[idx % DAY_COLORS.length] }}
              />
              第 {day.day} 天
              {weather && <WeatherBadge weather={weather} />}
            </span>
          );
        })}
        <span className="inline-flex items-center gap-1.5 text-ink-faint">
          <span className="flex h-3.5 w-3.5 items-center justify-center text-[10px]">🏨</span>
          推荐住宿区域
        </span>
        {usedTypes.map((type) => (
          <span key={type} className="inline-flex items-center gap-1.5 text-ink-faint">
            <span
              className="h-2.5 w-2.5 rounded-full border border-white/50"
              style={{ backgroundColor: poiTypeColor(type) }}
            />
            {poiTypeLabel(type)}
          </span>
        ))}
      </div>
      <div className="relative">
        <div
          ref={setContainerEl}
          className="h-[380px] w-full rounded-xl border border-line"
        />
        {routeLoading && (
          <div className="absolute left-3 top-3 rounded-lg bg-surface/90 px-3 py-1.5 text-xs text-ink-strong shadow">
            真实路网加载中…
          </div>
        )}
        {routeError && (
          <div className="absolute left-3 top-3 rounded-lg bg-surface/90 px-3 py-1.5 text-xs text-amber-600 shadow">
            路线服务暂不可用，当前为示意连线
          </div>
        )}
      </div>
      {routeDays.some((d) =>
        d.segments.some((s) => !isRealRouteSource(s.source)),
      ) && (
        <p className="mt-1 text-xs text-ink-faint">
          虚线为示意路线（未获取真实路网），实线为高德真实路线
        </p>
      )}
    </div>
  );
}

function WeatherBadge({ weather }: { weather: DailyWeather }) {
  const temp =
    weather.tempMin != null || weather.tempMax != null
      ? ` ${fmtTemp(weather.tempMin)}~${fmtTemp(weather.tempMax)}℃`
      : '';
  return (
    <span className="text-ink-faint">
      {weatherIcon(weather.weatherText)} {weather.weatherText ?? '未知'}
      {temp}
    </span>
  );
}

function weatherIcon(text?: string): string {
  if (!text) return '';
  if (text.includes('晴')) return '☀️';
  if (text.includes('雷')) return '⛈️';
  if (text.includes('雪')) return '🌨️';
  if (text.includes('雨') || text.includes('毛毛')) return '🌧️';
  if (text.includes('雾')) return '🌫️';
  if (text.includes('阴')) return '☁️';
  if (text.includes('云')) return '⛅';
  return '';
}

function fmtTemp(v?: number): string {
  if (v == null) return '-';
  return String(Math.round(v));
}

function drawSegment(
  map: L.Map,
  segment: MapRouteSegment,
  color: string,
  day: number,
): L.Polyline | null {
  const from: MapPoint = { lng: segment.fromLng, lat: segment.fromLat };
  const to: MapPoint = { lng: segment.toLng, lat: segment.toLat };
  const label = `${segment.fromName} → ${segment.toName}`;
  const duration = formatRouteDuration(segment.durationSeconds);

  if (isRealRouteSource(segment.source) && segment.polyline && segment.polyline.length > 0) {
    const line = L.polyline(segment.polyline.map(toLatLng), {
      color,
      weight: 4,
      opacity: 0.9,
      lineCap: 'round',
    })
      .addTo(map)
      .bindTooltip(`第${day}天 · ${label}${duration ? ` · ${duration}` : ''}`);
    drawModeBadge(map, segment, segment.polyline.map(toLatLng));
    return line;
  }

  const curve = buildFallbackCurve(from, to);
  const line = L.polyline(curve, {
    color,
    weight: 2,
    opacity: 0.55,
    dashArray: '6 8',
  })
    .addTo(map)
    .bindTooltip(`第${day}天 · ${label}（示意路线）`);
  drawModeBadge(map, segment, curve);
  return line;
}

/** M15-2：路线中点叠加交通方式小图标（步行 🚶 / 驾车 🚗）。 */
function drawModeBadge(
  map: L.Map,
  segment: MapRouteSegment,
  points: L.LatLngExpression[],
) {
  if (segment.mode !== 'WALKING' && segment.mode !== 'DRIVING') return;
  if (!points || points.length === 0) return;
  const mid = points[Math.floor(points.length / 2)];
  const latlng = Array.isArray(mid)
    ? L.latLng(mid[0], mid[1])
    : (mid as L.LatLng);
  const icon = L.divIcon({
    className: '',
    html: `<div style="width:22px;height:22px;display:flex;align-items:center;justify-content:center;font-size:13px;background:rgba(255,255,255,.94);border:1px solid rgba(0,0,0,.18);border-radius:9999px;box-shadow:0 1px 3px rgba(0,0,0,.18)">${
      segment.mode === 'WALKING' ? '🚶' : '🚗'
    }</div>`,
    iconSize: [22, 22],
    iconAnchor: [11, 11],
  });
  L.marker(latlng, { icon, interactive: false })
    .addTo(map)
    .bindTooltip(modeLabel(segment.mode), { direction: 'top' });
}
