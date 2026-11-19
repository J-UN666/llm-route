/**
 * 行程地图纯函数：坐标转换、Canvas 投影、视野拟合、图层模型与绘制。
 *
 * 坐标口径：
 * - 后端按段保留源坐标系（校园段 WGS84、城市段 GCJ-02），这里不覆盖源标识；
 * - 地图内部统一用 GCJ-02 作为绘制坐标系：WGS84 段先 `wgs84ToGcj02`，
 *   GCJ-02 段原样使用，最后统一做 Web Mercator 投影并映射到画布像素；
 * - 未知坐标系：该段不绘制轨迹，只保留源坐标系标识与“无轨迹”原因。
 *
 * 本文件不引用 `uni`，便于离线断言；Canvas 由 Vue 组件传入 2D 上下文。
 */

import type { JourneyPlan, JourneyPoint, JourneySegment } from '@/types/journey';
import {
  MAP_INTERNAL_COORDINATE_SYSTEM,
  segmentStyle,
  type SegmentCategory
} from './journey-segment-meta';

export { MAP_INTERNAL_COORDINATE_SYSTEM } from './journey-segment-meta';

export interface LonLat {
  longitude: number;
  latitude: number;
}

export type JourneyMapCoordinateSystem = 'WGS84' | 'GCJ-02' | 'unknown';

export interface JourneyMapDrawSegment {
  sequence: number;
  mode: string;
  modeLabel: string;
  category: SegmentCategory;
  color: string;
  dashed: boolean;
  lineWidth: number;
  /** 源几何的坐标系标识，原样保留（WGS84 / GCJ-02 / 未知）。 */
  sourceCoordinateSystem: string | null;
  /** 实际用于绘制的坐标系（统一为 GCJ-02）。 */
  drawCoordinateSystem: string;
  /** 已转换到内部绘制坐标系的点；不可绘制时为空数组。 */
  points: LonLat[];
  hasGeometry: boolean;
  sourcePointCount: number;
  /** 不可绘制时给详情/地图的“无轨迹”原因；可绘制时为 null。 */
  traceMissingReason: string | null;
}

export type JourneyMapMarkerRole = 'origin' | 'gate' | 'boarding' | 'alighting' | 'destination' | 'walk';
export type JourneyMapSection = 'campus' | 'city' | 'all';

export interface JourneyMapMarker {
  role: JourneyMapMarkerRole;
  /** 用于去重和测试的稳定键。 */
  key: string;
  label: string;
  sequence: number | null;
  color: string;
  sourceCoordinateSystem: string | null;
  points: LonLat;
}

export type JourneyMapState = 'empty' | 'noGeometry' | 'ready';

export interface JourneyMapModel {
  planId: string | null;
  hasPlan: boolean;
  state: JourneyMapState;
  segments: JourneyMapDrawSegment[];
  markers: JourneyMapMarker[];
  hasDrawableGeometry: boolean;
  drawableSegmentCount: number;
  missingGeometryCount: number;
  /** 缺坐标而未绘制的角色提示（起点/出校点/终点），供组件显示。 */
  missingMarkerNotes: string[];
  gateText: string;
  sources: string[];
  queriedAt: string | null;
  campusDataVersion: string | null;
  departureTime: string | null;
  arrivalTime: string | null;
}

export interface JourneyMapViewport {
  width: number;
  height: number;
  padding: number;
  /** 每 1 个 Web Mercator 世界单位对应多少像素，用于测试与说明。 */
  scale: number;
  centerX: number;
  centerY: number;
  project(longitude: number, latitude: number): [number, number];
}

export interface JourneyMapDrawReport {
  state: JourneyMapState;
  segmentsDrawn: number;
  dashedSegments: number;
  markersDrawn: number;
  markersWithLabels: number;
  viewport: { width: number; height: number; scale: number } | null;
}

/* ------------------------------------------------------------------ */
/* WGS84 → GCJ-02（与 web/chengdu-map.js、后端 CoordinateConverter 同源） */
/* ------------------------------------------------------------------ */

const AXIS = 6378245.0;
const EE = 0.00669342162296594323;
const PI = Math.PI;

function transformLat(x: number, y: number): number {
  let ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x));
  ret += ((20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0) / 3.0;
  ret += ((20.0 * Math.sin(y * PI) + 40.0 * Math.sin((y / 3.0) * PI)) * 2.0) / 3.0;
  ret += ((160.0 * Math.sin((y / 12.0) * PI) + 320 * Math.sin((y * PI) / 30.0)) * 2.0) / 3.0;
  return ret;
}

function transformLng(x: number, y: number): number {
  let ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x));
  ret += ((20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0) / 3.0;
  ret += ((20.0 * Math.sin(x * PI) + 40.0 * Math.sin((x / 3.0) * PI)) * 2.0) / 3.0;
  ret += ((150.0 * Math.sin((x / 12.0) * PI) + 300.0 * Math.sin((x / 30.0) * PI)) * 2.0) / 3.0;
  return ret;
}

function outOfChina(longitude: number, latitude: number): boolean {
  return !(longitude > 73.66 && longitude < 135.05 && latitude > 3.86 && latitude < 53.55);
}

/**
 * WGS84 → GCJ-02。中国范围外按后端约定原值返回；
 * 调用方仍需通过 `coordinateSystemKind` 保留源坐标系标识。
 */
export function wgs84ToGcj02(longitude: number, latitude: number): [number, number] {
  if (!Number.isFinite(longitude) || !Number.isFinite(latitude)) {
    return [longitude, latitude];
  }
  if (outOfChina(longitude, latitude)) {
    return [longitude, latitude];
  }
  let dLat = transformLat(longitude - 105.0, latitude - 35.0);
  let dLng = transformLng(longitude - 105.0, latitude - 35.0);
  const radLat = (latitude / 180.0) * PI;
  let magic = Math.sin(radLat);
  magic = 1 - EE * magic * magic;
  const sqrtMagic = Math.sqrt(magic);
  dLat = (dLat * 180.0) / (((AXIS * (1 - EE)) / (magic * sqrtMagic)) * PI);
  dLng = (dLng * 180.0) / ((AXIS / sqrtMagic) * Math.cos(radLat) * PI);
  return [longitude + dLng, latitude + dLat];
}

/** 与后端一致的坐标系识别；未知标识不做猜测。 */
export function coordinateSystemKind(coordinateSystem: string | null | undefined): JourneyMapCoordinateSystem {
  const value = (coordinateSystem || '').trim().toUpperCase().replace(/\s+/g, '');
  if (value === 'WGS84' || value === 'WGS-84') return 'WGS84';
  if (value === 'GCJ-02' || value === 'GCJ02') return 'GCJ-02';
  return 'unknown';
}

function isValidCoordinate(longitude: number, latitude: number): boolean {
  return (
    Number.isFinite(longitude) &&
    Number.isFinite(latitude) &&
    longitude >= -180 &&
    longitude <= 180 &&
    latitude >= -90 &&
    latitude <= 90
  );
}

/** 把源坐标点转换到内部绘制坐标系；未知坐标系返回 null（不猜）。 */
export function toInternalPoint(
  longitude: number,
  latitude: number,
  coordinateSystem: string | null | undefined
): LonLat | null {
  if (!isValidCoordinate(longitude, latitude)) return null;
  const kind = coordinateSystemKind(coordinateSystem);
  if (kind === 'unknown') return null;
  if (kind === 'WGS84') {
    const [lng, lat] = wgs84ToGcj02(longitude, latitude);
    return isValidCoordinate(lng, lat) ? { longitude: lng, latitude: lat } : null;
  }
  return { longitude, latitude };
}

/* ------------------------------------------------------------------ */
/* 方案 → 地图模型                                                      */
/* ------------------------------------------------------------------ */

const MARKER_STYLES: Record<JourneyMapMarkerRole, { color: string; prefix: string }> = {
  origin: { color: '#1b5e20', prefix: '起点' },
  gate: { color: '#d84315', prefix: '出校点' },
  boarding: { color: '#1565c0', prefix: '上车站' },
  alighting: { color: '#00838f', prefix: '下车站' },
  walk: { color: '#a75d10', prefix: '步行接驳' },
  destination: { color: '#b71c1c', prefix: '终点' }
};

interface ParsedSourcePoint {
  longitude: number;
  latitude: number;
}

function parseGeometryCoordinates(segment: JourneySegment): { valid: ParsedSourcePoint[]; invalid: number } {
  const coordinates = segment.geometry ? segment.geometry.coordinates : null;
  if (!Array.isArray(coordinates)) {
    return { valid: [], invalid: 0 };
  }
  const valid: ParsedSourcePoint[] = [];
  let invalid = 0;
  for (const pair of coordinates) {
    if (!Array.isArray(pair) || pair.length < 2) {
      invalid += 1;
      continue;
    }
    const [longitude, latitude] = pair;
    if (typeof longitude !== 'number' || typeof latitude !== 'number' || !isValidCoordinate(longitude, latitude)) {
      invalid += 1;
      continue;
    }
    valid.push({ longitude, latitude });
  }
  return { valid, invalid };
}

function segmentSourceCoordinateSystem(segment: JourneySegment): string | null {
  return segment.geometry?.coordinateSystem || segment.coordinateSystem || null;
}

/** 分段轨迹可用性判定结果；详情与地图共用，避免同一段出现“有轨迹/无轨迹”不一致。 */
export type SegmentTraceState =
  | 'drawable'
  | 'missing-geometry'
  | 'invalid-coordinates'
  | 'unsupported-coordinate-system'
  | 'conversion-missing';

export interface SegmentTraceAvailability {
  state: SegmentTraceState;
  drawable: boolean;
  sourceCoordinateSystem: string | null;
  /** 源几何中的有效坐标点数（未转换）。 */
  sourcePointCount: number;
  /** 内部 GCJ-02 坐标系下可绘制的点数。 */
  internalPointCount: number;
  /** 转换到内部绘制坐标系后的点；不可绘制时为空数组。 */
  points: LonLat[];
  /** 不可绘制原因（不含“无轨迹：”前缀）；可绘制时为 null。 */
  reason: string | null;
}

/**
 * 判定一段轨迹能否按内部 GCJ-02 坐标系绘制。
 * 详情与地图都通过该函数得出结论：缺失几何、有效点不足、坐标系不支持、
 * 转换后可用点不足时统一为无轨迹，避免地图拒绝绘制而详情仍显示“有轨迹”。
 */
export function resolveSegmentTrace(segment: JourneySegment): SegmentTraceAvailability {
  const sourceCoordinateSystem = segmentSourceCoordinateSystem(segment);
  const parsed = parseGeometryCoordinates(segment);
  const sourcePointCount = parsed.valid.length;
  const base = {
    sourceCoordinateSystem,
    sourcePointCount,
    internalPointCount: 0
  };

  if (!segment.geometry || !Array.isArray(segment.geometry.coordinates)) {
    return { ...base, state: 'missing-geometry', drawable: false, points: [], reason: '接口未返回该段几何' };
  }
  if (sourcePointCount < 2) {
    return {
      ...base,
      state: 'invalid-coordinates',
      drawable: false,
      points: [],
      reason: sourcePointCount === 0 ? '几何没有有效坐标点' : `几何坐标不足（有效 ${sourcePointCount} 点）`
    };
  }
  if (coordinateSystemKind(sourceCoordinateSystem) === 'unknown') {
    return {
      ...base,
      state: 'unsupported-coordinate-system',
      drawable: false,
      points: [],
      reason: `坐标系 ${sourceCoordinateSystem || '未提供'} 暂不支持绘制`
    };
  }

  const points = parsed.valid
    .map((point) => toInternalPoint(point.longitude, point.latitude, sourceCoordinateSystem))
    .filter((point): point is LonLat => point !== null);
  if (points.length < 2) {
    return {
      ...base,
      state: 'conversion-missing',
      drawable: false,
      points: [],
      reason: '坐标转换后可用点不足 2 个'
    };
  }
  return {
    ...base,
    state: 'drawable',
    drawable: true,
    points,
    internalPointCount: points.length,
    reason: null
  };
}

function buildDrawSegment(segment: JourneySegment): JourneyMapDrawSegment {
  const style = segmentStyle(segment.mode);
  const trace = resolveSegmentTrace(segment);

  return {
    sequence: segment.sequence,
    mode: segment.mode,
    modeLabel: style.modeLabel,
    category: style.category,
    color: style.color,
    dashed: style.dashed,
    lineWidth: style.lineWidth,
    sourceCoordinateSystem: trace.sourceCoordinateSystem,
    drawCoordinateSystem: MAP_INTERNAL_COORDINATE_SYSTEM,
    points: trace.drawable ? trace.points : [],
    hasGeometry: trace.drawable,
    sourcePointCount: trace.sourcePointCount,
    traceMissingReason: trace.reason
  };
}

const MARKER_MISSING_NOTES: Array<{ role: JourneyMapMarkerRole; text: string }> = [
  { role: 'origin', text: '起点坐标未提供' },
  { role: 'gate', text: '出校点坐标未提供' },
  { role: 'destination', text: '终点坐标未提供' }
];

function buildMissingMarkerNotes(markers: JourneyMapMarker[], section: JourneyMapSection): string[] {
  const roles = new Set(markers.map((marker) => marker.role));
  return MARKER_MISSING_NOTES.filter((item) =>
    !roles.has(item.role) && !(section === 'campus' && item.role === 'destination')
      && !(section === 'city' && item.role === 'origin')
  ).map((item) => item.text);
}

function buildMarker(
  role: JourneyMapMarkerRole,
  label: string,
  sequence: number | null,
  point: JourneyPoint | null,
  sourceCoordinateSystem: string | null
): JourneyMapMarker | null {
  if (!point || typeof point.longitude !== 'number' || typeof point.latitude !== 'number') {
    return null;
  }
  const internal = toInternalPoint(
    point.longitude,
    point.latitude,
    point.coordinateSystem || sourceCoordinateSystem
  );
  if (!internal) return null;
  const style = MARKER_STYLES[role];
  const displayLabel = label.trim().length > 0 ? label.trim() : style.prefix;
  return {
    role,
    key: `${role}:${displayLabel}:${internal.longitude.toFixed(6)}:${internal.latitude.toFixed(6)}`,
    label: `${style.prefix}·${displayLabel}`,
    sequence,
    color: style.color,
    sourceCoordinateSystem: point.coordinateSystem || sourceCoordinateSystem,
    points: internal
  };
}

function pointFromGeometry(segment: JourneyMapDrawSegment, atStart: boolean): JourneyMapMarker['points'] | null {
  if (segment.points.length < 2) return null;
  return atStart ? segment.points[0] : segment.points[segment.points.length - 1];
}

function pushMarker(markers: JourneyMapMarker[], marker: JourneyMapMarker | null): void {
  if (marker && !markers.some((existing) => existing.key === marker.key)) {
    markers.push(marker);
  }
}

/** 从方案构造地图模型；plan 为 null 或 undefined 时返回明确空模型。 */
export function buildJourneyMapModel(
  plan: JourneyPlan | null | undefined, section: JourneyMapSection = 'all'
): JourneyMapModel {
  if (!plan) {
    return {
      planId: null,
      hasPlan: false,
      state: 'empty',
      segments: [],
      markers: [],
      hasDrawableGeometry: false,
      drawableSegmentCount: 0,
      missingGeometryCount: 0,
      missingMarkerNotes: [],
      gateText: '未提供',
      sources: [],
      queriedAt: null,
      campusDataVersion: null,
      departureTime: null,
      arrivalTime: null
    };
  }

  const rawSegments = Array.isArray(plan.segments) ? [...plan.segments].sort((a, b) => a.sequence - b.sequence) : [];
  const segments = rawSegments.map(buildDrawSegment);
  const markers: JourneyMapMarker[] = [];

  const campusIndex = segments.findIndex((segment) => segment.category === 'campus');
  const firstSegment = rawSegments[campusIndex] || null;
  const lastIndex = segments.length - 1;
  const lastSegment = segments[lastIndex]?.category !== 'campus' ? rawSegments[lastIndex] : null;

  // 起点：校园段 fromPoint；只有首段（校园接驳段）几何可作兜底。
  // 首段坐标也缺失时不要拿后续城市段的首点冒充校园起点，宁可不画并提示“起点坐标未提供”。
  let originMarker = buildMarker(
    'origin',
    (firstSegment && firstSegment.fromPoint && firstSegment.fromPoint.name) || '校园起点',
    1,
    firstSegment ? firstSegment.fromPoint : null,
    firstSegment ? segmentSourceCoordinateSystem(firstSegment) : null
  );
  if (!originMarker && segments[campusIndex]) {
    const fallback = pointFromGeometry(segments[campusIndex], true);
    if (fallback) {
      originMarker = {
        role: 'origin',
        key: `origin:校园起点:${fallback.longitude.toFixed(6)}:${fallback.latitude.toFixed(6)}`,
        label: '起点·校园起点',
        sequence: 1,
        color: MARKER_STYLES.origin.color,
        sourceCoordinateSystem: segments[campusIndex].sourceCoordinateSystem,
        points: fallback
      };
    }
  }
  if (section !== 'city') pushMarker(markers, originMarker);

  // 出校点：plan.gateExitPoint；缺失时退回校园段 toPoint。
  const gatePoint = plan.gateExitPoint || (firstSegment ? firstSegment.toPoint : null);
  const gateName = (plan.gateExitPoint && plan.gateExitPoint.name) || plan.gateId || '未提供';
  pushMarker(
    markers,
    buildMarker('gate', gateName, null, gatePoint, firstSegment ? segmentSourceCoordinateSystem(firstSegment) : null)
  );

  // 上下车站：只取实际乘车段；点名优先 segment 字段，坐标优先 fromPoint/toPoint。
  let previousRide = false;
  for (const [index, segment] of rawSegments.entries()) {
    if (section === 'campus') break;
    const drawSegment = segments[index];
    if (drawSegment.category === 'city-walk' && !drawSegment.hasGeometry) {
      for (const point of [segment.fromPoint, segment.toPoint]) {
        if (point) {
          pushMarker(markers, buildMarker('walk', point.name || '已知步行端点', segment.sequence,
            point, segmentSourceCoordinateSystem(segment)));
        }
      }
    }
    if (drawSegment.category !== 'ride') continue;
    const fromName =
      segment.boardingStation ||
      (segment.fromPoint && segment.fromPoint.name) ||
      (segment.lineName ? `${segment.lineName} 上车站` : '未提供');
    const toName =
      segment.alightingStation ||
      (segment.toPoint && segment.toPoint.name) ||
      (segment.lineName ? `${segment.lineName} 下车站` : '未提供');

    let boarding = buildMarker('boarding', fromName, segment.sequence, segment.fromPoint, segmentSourceCoordinateSystem(segment));
    let alighting = buildMarker('alighting', toName, segment.sequence, segment.toPoint, segmentSourceCoordinateSystem(segment));
    if (!boarding || !alighting) {
      const startPoint = pointFromGeometry(drawSegment, true);
      const endPoint = pointFromGeometry(drawSegment, false);
      if (!boarding && startPoint) {
        boarding = {
          role: 'boarding',
          key: `boarding:${fromName}:${startPoint.longitude.toFixed(6)}:${startPoint.latitude.toFixed(6)}`,
          label: `上车站·${fromName}`,
          sequence: segment.sequence,
          color: MARKER_STYLES.boarding.color,
          sourceCoordinateSystem: drawSegment.sourceCoordinateSystem,
          points: startPoint
        };
      }
      if (!alighting && endPoint) {
        alighting = {
          role: 'alighting',
          key: `alighting:${toName}:${endPoint.longitude.toFixed(6)}:${endPoint.latitude.toFixed(6)}`,
          label: `下车站·${toName}`,
          sequence: segment.sequence,
          color: MARKER_STYLES.alighting.color,
          sourceCoordinateSystem: drawSegment.sourceCoordinateSystem,
          points: endPoint
        };
      }
    }
    if (boarding && previousRide) {
      boarding.label = `换乘·${fromName}`;
    }
    pushMarker(markers, boarding);
    pushMarker(markers, alighting);
    previousRide = true;
  }

  // 终点：只使用最后一段 toPoint 的已知坐标。没有坐标时不绘制终点标记，
  // 避免把终点错误地画在最后一个可绘制段的末点（例如缺城市几何时落到校门）。
  const destinationMarker = buildMarker(
    'destination',
    (lastSegment && lastSegment.toPoint && lastSegment.toPoint.name) || '城市终点',
    lastSegment ? lastSegment.sequence : null,
    lastSegment ? lastSegment.toPoint : null,
    lastSegment ? segmentSourceCoordinateSystem(lastSegment) : null
  );
  if (section !== 'campus') pushMarker(markers, destinationMarker);

  const visibleSegments = section === 'all' ? segments : segments.filter((segment) =>
    (segment.category === 'campus') === (section === 'campus')
  );
  const drawableSegmentCount = visibleSegments.filter((segment) => segment.hasGeometry).length;
  const missingGeometryCount = visibleSegments.length - drawableSegmentCount;
  const hasDrawableGeometry = drawableSegmentCount > 0;

  return {
    planId: plan.planId || null,
    hasPlan: true,
    state: hasDrawableGeometry ? 'ready' : 'noGeometry',
    segments: visibleSegments,
    markers,
    hasDrawableGeometry,
    drawableSegmentCount,
    missingGeometryCount,
    missingMarkerNotes: buildMissingMarkerNotes(markers, section),
    gateText: gateName,
    sources: Array.isArray(plan.sources) ? plan.sources.slice() : [],
    queriedAt: plan.queriedAt || null,
    campusDataVersion: plan.campusDataVersion || null,
    departureTime: plan.departureTime || null,
    arrivalTime: plan.arrivalTime || null
  };
}

/* ------------------------------------------------------------------ */
/* 视野拟合与 Canvas 绘制                                               */
/* ------------------------------------------------------------------ */

const WORLD_METERS_AT_EQUATOR = 40075016.686;
/** 单点或极短线段时的最小视野（米）。 */
const MIN_VIEW_METERS = 120;

/** Web Mercator 世界坐标（[0,1] 区间）；纬度先钳制到墨卡托有效范围。 */
function projectToWorld(longitude: number, latitude: number): { x: number; y: number } {
  const clampedLat = Math.max(-85.05112878, Math.min(85.05112878, latitude));
  const x = (longitude + 180) / 360;
  const sin = Math.sin((clampedLat * PI) / 180);
  const y = 0.5 - Math.log((1 + sin) / (1 - sin)) / (4 * PI);
  return { x, y };
}

/**
 * 按方案全部可用点（轨迹 + 标记）拟合视野。
 * 画布宽高为 CSS 像素；返回的 `project` 直接给出画布像素坐标。
 */
export function fitJourneyMapViewport(
  model: JourneyMapModel,
  width: number,
  height: number,
  padding = 26
): JourneyMapViewport | null {
  if (!Number.isFinite(width) || !Number.isFinite(height) || width <= 0 || height <= 0) {
    return null;
  }
  const points: LonLat[] = [];
  for (const segment of model.segments) {
    points.push(...segment.points);
  }
  for (const marker of model.markers) {
    points.push(marker.points);
  }
  if (points.length === 0) return null;

  let minX = Infinity;
  let maxX = -Infinity;
  let minY = Infinity;
  let maxY = -Infinity;
  let minLat = Infinity;
  let maxLat = -Infinity;
  for (const point of points) {
    const world = projectToWorld(point.longitude, point.latitude);
    minX = Math.min(minX, world.x);
    maxX = Math.max(maxX, world.x);
    minY = Math.min(minY, world.y);
    maxY = Math.max(maxY, world.y);
    minLat = Math.min(minLat, point.latitude);
    maxLat = Math.max(maxLat, point.latitude);
  }
  if (!Number.isFinite(minX) || !Number.isFinite(minY)) return null;

  const centerLat = Number.isFinite(minLat + maxLat) ? (minLat + maxLat) / 2 : 30.66;
  const metersPerWorldUnit =
    WORLD_METERS_AT_EQUATOR * Math.max(0.1, Math.cos((Math.max(-80, Math.min(80, centerLat)) * PI) / 180));
  const minWorldSpan = MIN_VIEW_METERS / metersPerWorldUnit;

  const spanX = Math.max(maxX - minX, minWorldSpan);
  const spanY = Math.max(maxY - minY, minWorldSpan);
  const safePadding = Math.max(0, Math.min(padding, Math.min(width, height) / 4));
  const usableWidth = width - safePadding * 2;
  const usableHeight = height - safePadding * 2;
  const scale = Math.min(usableWidth / spanX, usableHeight / spanY);
  if (!Number.isFinite(scale) || scale <= 0) return null;

  const centerX = (minX + maxX) / 2;
  const centerY = (minY + maxY) / 2;

  return {
    width,
    height,
    padding: safePadding,
    scale,
    centerX,
    centerY,
    project(longitude: number, latitude: number): [number, number] {
      const world = projectToWorld(longitude, latitude);
      return [
        (world.x - centerX) * scale + width / 2,
        (centerY - world.y) * scale + height / 2
      ];
    }
  };
}

function clamp(value: number, min: number, max: number): number {
  return Math.max(min, Math.min(max, value));
}

function drawCircleMarker(
  ctx: CanvasRenderingContext2D,
  x: number,
  y: number,
  color: string,
  label: string,
  labelAbove: boolean,
  labelSlot: number,
  viewWidth: number,
  viewHeight: number
): { filled: boolean; labeled: boolean } {
  ctx.save();
  ctx.beginPath();
  ctx.arc(x, y, 6, 0, Math.PI * 2);
  ctx.fillStyle = '#ffffff';
  ctx.fill();
  ctx.beginPath();
  ctx.arc(x, y, 4, 0, Math.PI * 2);
  ctx.fillStyle = color;
  ctx.fill();
  ctx.lineWidth = 1.5;
  ctx.strokeStyle = '#ffffff';
  ctx.stroke();
  ctx.restore();

  const dir = labelAbove ? -1 : 1;
  const canvasWidth = viewWidth > 0 ? viewWidth : ctx.canvas ? ctx.canvas.width : 0;
  const canvasHeight = viewHeight > 0 ? viewHeight : ctx.canvas ? ctx.canvas.height : 0;
  const labelY = clamp(
    y + dir * (15 + labelSlot * 14),
    10,
    canvasHeight > 0 ? canvasHeight - 6 : y + dir * 15
  );
  ctx.save();
  ctx.font = '11px sans-serif';
  ctx.textAlign = 'center';
  ctx.textBaseline = 'middle';
  // 文字左右留白：按实际文本宽度把标签夹在画布内，避免长地名在边缘被截断。
  const measured = typeof ctx.measureText === 'function' ? ctx.measureText(label).width : label.length * 11;
  const halfWidth = measured / 2;
  const labelX =
    canvasWidth > 0 && halfWidth * 2 + 8 <= canvasWidth
      ? clamp(x, halfWidth + 4, canvasWidth - halfWidth - 4)
      : canvasWidth > 0
        ? canvasWidth / 2
        : x;
  if (typeof ctx.strokeText === 'function') {
    ctx.lineWidth = 3;
    ctx.strokeStyle = 'rgba(255,255,255,0.92)';
    ctx.strokeText(label, labelX, labelY);
  }
  ctx.fillStyle = '#1b2a3a';
  ctx.fillText(label, labelX, labelY);
  ctx.restore();
  return { filled: true, labeled: true };
}

/**
 * 在 2D 上下文绘制模型。`viewport` 为 null 时只铺背景并提示无可用坐标。
 * 上下文应已按 devicePixelRatio 做过 `scale(dpr, dpr)`，参数使用 CSS 像素。
 */
export function drawJourneyMap(
  ctx: CanvasRenderingContext2D,
  model: JourneyMapModel,
  viewport: JourneyMapViewport | null
): JourneyMapDrawReport {
  const width = viewport ? viewport.width : ctx.canvas ? ctx.canvas.width : 0;
  const height = viewport ? viewport.height : ctx.canvas ? ctx.canvas.height : 0;
  ctx.clearRect(0, 0, width, height);
  ctx.fillStyle = '#f4f8fd';
  ctx.fillRect(0, 0, width, height);

  if (!viewport) {
    ctx.save();
    ctx.fillStyle = '#7c8a9c';
    ctx.font = '13px sans-serif';
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.fillText('没有可用的轨迹坐标', width / 2, height / 2);
    ctx.restore();
    return {
      state: model.state,
      segmentsDrawn: 0,
      dashedSegments: 0,
      markersDrawn: 0,
      markersWithLabels: 0,
      viewport: null
    };
  }

  let segmentsDrawn = 0;
  let dashedSegments = 0;
  for (const segment of model.segments) {
    if (!segment.hasGeometry || segment.points.length < 2) continue;
    const first = viewport.project(segment.points[0].longitude, segment.points[0].latitude);
    ctx.save();
    ctx.beginPath();
    ctx.moveTo(first[0], first[1]);
    for (let index = 1; index < segment.points.length; index += 1) {
      const point = segment.points[index];
      const projected = viewport.project(point.longitude, point.latitude);
      ctx.lineTo(projected[0], projected[1]);
    }
    if (segment.dashed && typeof ctx.setLineDash === 'function') {
      ctx.setLineDash([7, 5]);
      dashedSegments += 1;
    } else if (typeof ctx.setLineDash === 'function') {
      ctx.setLineDash([]);
    }
    ctx.lineWidth = segment.lineWidth;
    ctx.lineCap = 'round';
    ctx.lineJoin = 'round';
    ctx.strokeStyle = segment.color;
    ctx.stroke();
    ctx.restore();
    segmentsDrawn += 1;
  }

  let markersDrawn = 0;
  let markersWithLabels = 0;
  const labelSlots = new Map<string, number>();
  for (const marker of model.markers) {
    const [x, y] = viewport.project(marker.points.longitude, marker.points.latitude);
    const above = marker.role === 'origin' || marker.role === 'boarding';
    const groupKey = `${above ? 'a' : 'b'}:${Math.round(x / 20)}:${Math.round(y / 20)}`;
    const slot = labelSlots.get(groupKey) || 0;
    labelSlots.set(groupKey, slot + 1);
    const result = drawCircleMarker(ctx, x, y, marker.color, marker.label, above, slot, viewport.width, viewport.height);
    if (result.filled) markersDrawn += 1;
    if (result.labeled) markersWithLabels += 1;
  }

  return {
    state: model.state,
    segmentsDrawn,
    dashedSegments,
    markersDrawn,
    markersWithLabels,
    viewport: { width: viewport.width, height: viewport.height, scale: viewport.scale }
  };
}
