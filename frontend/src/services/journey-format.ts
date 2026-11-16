/**
 * 行程方案展示格式化。数字全部来自后端 JourneyPlan 字段，缺失时明确显示“未提供”，
 * 不用 0 或估算值冒充。W1-07 的成功态简略预览、W1-10 方案卡与 W1-11 详情共用这些函数，
 * 单位换算只发生在展示层，不写回接口字段。
 */

import { parseBeijingDateTime } from './api';
import type { JourneyPlan } from '@/types/journey';

/** 总耗时：4256 秒 → “约 1 小时 11 分钟”。 */
export function formatDuration(seconds: number | null | undefined): string {
  if (seconds === null || seconds === undefined || Number.isNaN(seconds)) return '耗时未提供';
  const totalSeconds = Math.max(0, Math.round(seconds));
  if (totalSeconds < 60) return '约 1 分钟';
  const totalMinutes = Math.round(totalSeconds / 60);
  if (totalMinutes < 60) return `约 ${totalMinutes} 分钟`;
  const hours = Math.floor(totalMinutes / 60);
  const minutes = totalMinutes % 60;
  return minutes > 0 ? `约 ${hours} 小时 ${minutes} 分钟` : `约 ${hours} 小时`;
}

/** W1-10 方案卡：耗时统一显示分钟，4976 秒 → “约 83 分钟”。 */
export function formatDurationMinutes(seconds: number | null | undefined): string {
  if (seconds === null || seconds === undefined || Number.isNaN(seconds)) return '未提供';
  const totalSeconds = Math.max(0, Math.round(seconds));
  const minutes = Math.max(1, Math.round(totalSeconds / 60));
  return `约 ${minutes} 分钟`;
}

/** 步行距离：879.07 米 → “879 米”；1807 米 → “1.8 公里”。 */
export function formatWalkingDistance(meters: number | null | undefined): string {
  if (meters === null || meters === undefined || Number.isNaN(meters)) return '步行距离未提供';
  const value = Math.max(0, meters);
  if (value < 1000) return `${Math.round(value)} 米`;
  return `${(value / 1000).toFixed(1)} 公里`;
}

/** W1-10 方案卡步行值：未知统一为“未提供”，不做 0 填充。 */
export function formatWalkingValue(meters: number | null | undefined): string {
  if (meters === null || meters === undefined || Number.isNaN(meters)) return '未提供';
  const value = Math.max(0, meters);
  if (value < 1000) return `${Math.round(value)} 米`;
  return `${(value / 1000).toFixed(1)} 公里`;
}

/** 换乘次数：0 → “无需换乘”。 */
export function formatTransferCount(count: number | null | undefined): string {
  if (count === null || count === undefined || Number.isNaN(count)) return '换乘次数未提供';
  if (count <= 0) return '无需换乘';
  return `${count} 次换乘`;
}

/** W1-10 方案卡换乘值：未知统一为“未提供”。 */
export function formatTransferValue(count: number | null | undefined): string {
  if (count === null || count === undefined || Number.isNaN(count)) return '未提供';
  if (count <= 0) return '无需换乘';
  return `${count} 次`;
}

/** 费用：5 → “5.00 元”；null → “费用未提供”。 */
export function formatCost(costYuan: number | null | undefined): string {
  if (costYuan === null || costYuan === undefined || Number.isNaN(costYuan)) return '费用未提供';
  return `${costYuan.toFixed(2)} 元`;
}

/** W1-10 方案卡费用值：null（未知）显示“未提供”，不显示 0 元。 */
export function formatCostValue(costYuan: number | null | undefined): string {
  if (costYuan === null || costYuan === undefined || Number.isNaN(costYuan)) return '未提供';
  return `${costYuan.toFixed(2)} 元`;
}

/** 出校点名称：优先 gateExitPoint.name，其次 gateId。 */
export function formatGateName(plan: JourneyPlan | null | undefined): string {
  if (!plan) return '出校点未提供';
  if (plan.gateExitPoint && plan.gateExitPoint.name) return plan.gateExitPoint.name;
  return plan.gateId || '出校点未提供';
}

/** 预计到达（北京时间）：同日 “10:22”；跨日 “11月17日 00:35”；无法解析时原样显示。 */
export function formatArrivalTime(
  arrivalIso: string | null | undefined,
  departureIso: string | null | undefined = null
): string {
  if (!arrivalIso) return '未提供';
  const arrival = parseBeijingDateTime(arrivalIso);
  if (!arrival) return arrivalIso;
  const departure = departureIso ? parseBeijingDateTime(departureIso) : null;
  if (departure && departure.date !== arrival.date) {
    return `${arrival.monthDay} ${arrival.clock}`;
  }
  return arrival.clock;
}

/** 到达是否跨日（用于卡片“跨日”标注）；缺少可比较时间时返回 false。 */
export function isCrossDayArrival(
  arrivalIso: string | null | undefined,
  departureIso: string | null | undefined
): boolean {
  const arrival = parseBeijingDateTime(arrivalIso);
  const departure = parseBeijingDateTime(departureIso);
  if (!arrival || !departure) return false;
  return arrival.date !== departure.date;
}

const SOURCE_LABELS: Record<string, string> = {
  AMAP_V3_TRANSIT_INTEGRATED: '高德地图'
};

/** 来源展示：campus_connector:<id> → 校园接驳数据；已知城市来源用中文名，其余保留原值。 */
export function formatPlanSources(sources: string[] | null | undefined): string {
  if (!sources || sources.length === 0) return '未提供';
  const labels = sources.map((source) => {
    if (!source) return '未提供';
    if (source.startsWith('campus_connector:')) return '校园接驳数据';
    return SOURCE_LABELS[source] || source;
  });
  return labels.join(' · ');
}

/** 取整等待（如高德发车时刻取整）：0 → “无需等待”。 */
export function formatRoundingWait(seconds: number | null | undefined): string {
  if (seconds === null || seconds === undefined || Number.isNaN(seconds)) return '等待时间未提供';
  const value = Math.max(0, Math.round(seconds));
  if (value === 0) return '无需等待';
  if (value < 60) return `约 ${value} 秒等待`;
  return `约 ${Math.round(value / 60)} 分钟等待`;
}
