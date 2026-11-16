/**
 * 行程方案展示格式化。数字全部来自后端 JourneyPlan 字段，缺失时明确显示“未提供”，
 * 不用 0 或估算值冒充。W1-07 的成功态简略预览与 W1-10 的方案卡可共用这些函数。
 */

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

/** 步行距离：879.07 米 → “879 米”；1807 米 → “1.8 公里”。 */
export function formatWalkingDistance(meters: number | null | undefined): string {
  if (meters === null || meters === undefined || Number.isNaN(meters)) return '步行距离未提供';
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

/** 费用：5 → “5.00 元”；null → “费用未提供”。 */
export function formatCost(costYuan: number | null | undefined): string {
  if (costYuan === null || costYuan === undefined || Number.isNaN(costYuan)) return '费用未提供';
  return `${costYuan.toFixed(2)} 元`;
}

/** 出校点名称：优先 gateExitPoint.name，其次 gateId。 */
export function formatGateName(plan: JourneyPlan | null | undefined): string {
  if (!plan) return '出校点未提供';
  if (plan.gateExitPoint && plan.gateExitPoint.name) return plan.gateExitPoint.name;
  return plan.gateId || '出校点未提供';
}

/** 取整等待（如高德发车时刻取整）：0 → “无需等待”。 */
export function formatRoundingWait(seconds: number | null | undefined): string {
  if (seconds === null || seconds === undefined || Number.isNaN(seconds)) return '等待时间未提供';
  const value = Math.max(0, Math.round(seconds));
  if (value === 0) return '无需等待';
  if (value < 60) return `约 ${value} 秒等待`;
  return `约 ${Math.round(value / 60)} 分钟等待`;
}
