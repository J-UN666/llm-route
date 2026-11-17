/**
 * 方案选中规则的纯函数。
 *
 * 页面只保存 `plans` 与 `selectedPlanId`，所有“当前方案”都从这两个字段计算，
 * 不缓存 JourneyPlan 对象副本，避免切换/重新查询后卡片数字与详情、地图脱节。
 */

import type { JourneyPlan } from '@/types/journey';

/** 成功响应默认选中第一条；空候选返回 null，由页面显示无方案。 */
export function defaultSelectedPlanId(plans: JourneyPlan[]): string | null {
  return plans.length > 0 ? plans[0].planId : null;
}

/** 按 ID 从当前候选计算选中方案；ID 不存在（旧结果残留）时返回 null。 */
export function resolveSelectedPlan(
  plans: JourneyPlan[],
  selectedPlanId: string | null | undefined
): JourneyPlan | null {
  if (!selectedPlanId) return null;
  return plans.find((plan) => plan.planId === selectedPlanId) || null;
}

/**
 * 切换选中：只接受当前候选中的 planId，返回规范化后的选中 ID；
 * 不属于本次候选的事件返回 null，调用方保留原选中，不产生悬空引用。
 */
export function selectExistingPlanId(plans: JourneyPlan[], planId: string): string | null {
  if (!planId) return null;
  return plans.some((plan) => plan.planId === planId) ? planId : null;
}
