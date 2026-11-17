/**
 * 路线详情纯函数：把后端 `JourneyPlan` 转为按 `sequence` 排列的分段视图模型。
 *
 * 约定：
 * - 只读取后端字段，不重算路线、距离或耗时；缺失值保持“未提供”，不用 0 冒充；
 * - 每段保留 mode、说明、站点/线路、已知距离与秒数、源坐标系；
 * - 几何缺失/坐标不足/坐标系不支持时仍返回完整文字详情，并标记“无轨迹”；
 * - 不引用 `uni`，便于离线断言。
 */

import type { JourneyPlan, JourneyPoint, JourneySegment } from '@/types/journey';
import {
  MAP_INTERNAL_COORDINATE_SYSTEM,
  formatKnownMeters,
  formatKnownSeconds,
  segmentStyle,
  type SegmentCategory
} from './journey-segment-meta';
import { resolveSegmentTrace } from './journey-map';

export interface JourneyDetailSegment {
  sequence: number;
  mode: string;
  modeLabel: string;
  category: SegmentCategory;
  categoryLabel: string;
  color: string;
  description: string;
  lineName: string | null;
  lineId: string | null;
  direction: string | null;
  lineText: string | null;
  boardingStation: string | null;
  alightingStation: string | null;
  stationText: string | null;
  fromName: string | null;
  toName: string | null;
  distanceText: string;
  durationText: string;
  /** 源几何坐标系标识，原样保留。 */
  sourceCoordinateSystem: string | null;
  /** 地图实际绘制坐标系。 */
  drawCoordinateSystem: string;
  hasGeometry: boolean;
  geometryPointCount: number;
  /** 例如“轨迹 24 个点 · 源坐标系 WGS84”。 */
  geometryText: string;
  /** 几何不可绘制时的“无轨迹：原因”；可绘制时为 null。 */
  traceMissingText: string | null;
}

export interface JourneyDetailModel {
  planId: string | null;
  hasPlan: boolean;
  hasSegments: boolean;
  /** plan 为 null 时给页面的明确空态文案。 */
  emptyText: string;
  segments: JourneyDetailSegment[];
  missingGeometryCount: number;
  sequenceComplete: boolean;
  gateId: string | null;
  gateName: string | null;
}

const EMPTY_TEXT = '无方案数据：当前没有选中的方案（查询失败或无可用方案），分段明细已清空。';

function textOrNull(value: string | null | undefined): string | null {
  if (typeof value !== 'string') return null;
  const trimmed = value.trim();
  return trimmed.length > 0 ? trimmed : null;
}

function pointName(point: JourneyPoint | null | undefined): string | null {
  return point ? textOrNull(point.name) : null;
}

function buildSegmentDescription(segment: JourneySegment, label: string): string {
  const description = textOrNull(segment.description);
  if (description) return description;
  const from = (segment.fromPoint && segment.fromPoint.name) || segment.boardingStation || '起点';
  const to = (segment.toPoint && segment.toPoint.name) || segment.alightingStation || '终点';
  return `${label}：${from} → ${to}`;
}

function buildLineText(segment: JourneySegment): string | null {
  const name = textOrNull(segment.lineName);
  const lineId = textOrNull(segment.lineId);
  const direction = textOrNull(segment.direction);
  const label = name || lineId;
  if (!label) return null;
  return direction ? `线路 ${label} · ${direction}` : `线路 ${label}`;
}

function buildStationText(segment: JourneySegment): string | null {
  const boarding = textOrNull(segment.boardingStation) || null;
  const alighting = textOrNull(segment.alightingStation) || null;
  if (boarding && alighting) return `上车站 ${boarding} → 下车站 ${alighting}`;
  if (boarding) return `上车站 ${boarding}`;
  if (alighting) return `下车站 ${alighting}`;
  return null;
}

function buildDetailSegment(segment: JourneySegment): JourneyDetailSegment {
  const style = segmentStyle(segment.mode);
  // 与地图共用同一套可绘制判定：坐标系不支持、几何缺失、点不足都统一为“无轨迹”，
  // 避免地图拒绝绘制而详情仍显示“有轨迹·地图按 GCJ-02 绘制”。
  const trace = resolveSegmentTrace(segment);
  const sourceCoordinateSystem = trace.sourceCoordinateSystem;
  const hasGeometry = trace.drawable;
  const traceMissingText = trace.reason ? `无轨迹：${trace.reason}` : null;
  const sourceSystemLabel = sourceCoordinateSystem || '未提供';
  const geometryText = hasGeometry
    ? `轨迹 ${trace.sourcePointCount} 个点 · 源坐标系 ${sourceSystemLabel} · 地图按 ${MAP_INTERNAL_COORDINATE_SYSTEM} 绘制`
    : `无轨迹 · 源坐标系 ${sourceSystemLabel}`;

  return {
    sequence: segment.sequence,
    mode: segment.mode,
    modeLabel: style.modeLabel,
    category: style.category,
    categoryLabel: style.categoryLabel,
    color: style.color,
    description: buildSegmentDescription(segment, style.modeLabel),
    lineName: textOrNull(segment.lineName),
    lineId: textOrNull(segment.lineId),
    direction: textOrNull(segment.direction),
    lineText: buildLineText(segment),
    boardingStation: textOrNull(segment.boardingStation),
    alightingStation: textOrNull(segment.alightingStation),
    stationText: buildStationText(segment),
    fromName: pointName(segment.fromPoint),
    toName: pointName(segment.toPoint),
    distanceText: formatKnownMeters(segment.distanceMeters),
    durationText: formatKnownSeconds(segment.durationSeconds),
    sourceCoordinateSystem,
    drawCoordinateSystem: MAP_INTERNAL_COORDINATE_SYSTEM,
    hasGeometry,
    geometryPointCount: trace.sourcePointCount,
    geometryText,
    traceMissingText
  };
}

/** 从方案构造详情模型；plan 为 null/undefined 时返回明确空态。 */
export function buildJourneyDetailModel(plan: JourneyPlan | null | undefined): JourneyDetailModel {
  if (!plan) {
    return {
      planId: null,
      hasPlan: false,
      hasSegments: false,
      emptyText: EMPTY_TEXT,
      segments: [],
      missingGeometryCount: 0,
      sequenceComplete: false,
      gateId: null,
      gateName: null
    };
  }

  const rawSegments = Array.isArray(plan.segments) ? plan.segments : [];
  const segments = rawSegments.map(buildDetailSegment);
  const sequenceComplete =
    segments.length > 0 && segments.every((segment, index) => segment.sequence === index + 1);

  return {
    planId: plan.planId || null,
    hasPlan: true,
    hasSegments: segments.length > 0,
    emptyText: segments.length === 0 ? '该方案没有分段数据（segments 为空）。' : '',
    segments,
    missingGeometryCount: segments.filter((segment) => !segment.hasGeometry).length,
    sequenceComplete,
    gateId: textOrNull(plan.gateId),
    gateName: plan.gateExitPoint ? textOrNull(plan.gateExitPoint.name) : null
  };
}
