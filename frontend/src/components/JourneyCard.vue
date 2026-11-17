<template>
  <view
    class="journey-card"
    :class="{ 'journey-card-selected': selected }"
    :data-plan-id="plan.planId"
    hover-class="journey-card-hover"
    :hover-stay-time="60"
    @tap="onSelect"
  >
    <view class="jc-head">
      <view class="jc-head-main">
        <text class="jc-index">方案 {{ index + 1 }}</text>
        <text v-if="selected" class="jc-badge">当前方案</text>
      </view>
      <text class="jc-gate">出校点 {{ gateText }}</text>
    </view>

    <view class="jc-hero">
      <view class="jc-hero-item">
        <text class="jc-hero-label">全程耗时</text>
        <text class="jc-hero-value">{{ durationText }}</text>
      </view>
      <view class="jc-hero-divider"></view>
      <view class="jc-hero-item">
        <text class="jc-hero-label">预计到达</text>
        <view class="jc-hero-arrival">
          <text class="jc-hero-value jc-hero-value-arrival">{{ arrivalText }}</text>
          <text v-if="crossDay" class="jc-cross-day">跨日</text>
        </view>
      </view>
    </view>

    <view class="jc-metrics">
      <view class="jc-metric">
        <text class="jc-metric-label">全程步行</text>
        <text class="jc-metric-value">{{ walkingText }}</text>
      </view>
      <view class="jc-metric">
        <text class="jc-metric-label">换乘</text>
        <text class="jc-metric-value">{{ transferText }}</text>
      </view>
      <view class="jc-metric">
        <text class="jc-metric-label">费用</text>
        <text class="jc-metric-value">{{ costText }}</text>
      </view>
    </view>

    <view class="jc-foot">
      <text class="jc-foot-line">来源 {{ sourcesText }}</text>
    </view>

    <view v-if="!selected" class="jc-tap-hint">
      <text class="jc-tap-hint-text">点击切换到此方案</text>
    </view>
  </view>
</template>

<script setup lang="ts">
/** 行程摘要卡片；点击后由页面统一更新选中方案。 */

import { computed } from 'vue';
import type { JourneyPlan } from '@/types/journey';
import {
  formatArrivalTime,
  formatCostValue,
  formatDurationMinutes,
  formatGateName,
  formatPlanSources,
  formatTransferValue,
  formatWalkingValue,
  isCrossDayArrival
} from '@/services/journey-format';

interface JourneyCardProps {
  /** 后端返回的实际候选方案，按 排序后的数组顺序展示。 */
  plan: JourneyPlan;
  /** 在本次候选数组中的下标，仅用于“方案 N”标题。 */
  index: number;
  /** 是否为页面当前选中的方案。 */
  selected?: boolean;
}

const props = withDefaults(defineProps<JourneyCardProps>(), {
  selected: false
});

const emit = defineEmits<{
  /** 点击卡片：把本卡对应的 planId 交给页面统一维护。 */
  (event: 'select', planId: string): void;
}>();

const durationText = computed(() => formatDurationMinutes(props.plan.totalDurationSeconds));
const arrivalText = computed(() => formatArrivalTime(props.plan.arrivalTime, props.plan.departureTime));
const crossDay = computed(() => isCrossDayArrival(props.plan.arrivalTime, props.plan.departureTime));
const walkingText = computed(() => formatWalkingValue(props.plan.totalWalkingMeters));
const transferText = computed(() => formatTransferValue(props.plan.transferCount));
const costText = computed(() => formatCostValue(props.plan.costYuan));
const gateText = computed(() => formatGateName(props.plan));
const sourcesText = computed(() => formatPlanSources(props.plan.sources));

function onSelect(): void {
  emit('select', props.plan.planId);
}
</script>

<style scoped>
.journey-card {
  position: relative;
  margin-top: 20rpx;
  padding: 22rpx 24rpx 18rpx;
  border-radius: 18rpx;
  border: 2rpx solid #e6ecf3;
  background: linear-gradient(180deg, #f7fbff 0%, #ffffff 62%);
  box-shadow: 0 4rpx 14rpx rgba(16, 54, 92, 0.05);
}

.journey-card-selected {
  border-color: #1d6fbf;
  background: linear-gradient(180deg, #eef7ff 0%, #ffffff 58%);
  box-shadow: 0 8rpx 24rpx rgba(29, 111, 191, 0.16);
}

.journey-card-hover {
  opacity: 0.92;
}

.jc-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12rpx;
}

.jc-head-main {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10rpx;
}

.jc-index {
  font-size: 28rpx;
  font-weight: 700;
  color: #131a24;
  line-height: 1.3;
}

.jc-badge {
  padding: 3rpx 14rpx;
  border-radius: 999rpx;
  border: 1rpx solid #c7ddf6;
  background: #e2efff;
  color: #17518c;
  font-size: 18rpx;
  font-weight: 600;
  line-height: 1.5;
}

.jc-gate {
  flex-shrink: 0;
  max-width: 46%;
  padding: 4rpx 12rpx;
  border-radius: 8rpx;
  background: #f0f5fb;
  color: #5b6b7d;
  font-size: 20rpx;
  line-height: 1.4;
}

.jc-hero {
  display: flex;
  align-items: stretch;
  margin-top: 18rpx;
  padding: 16rpx 18rpx;
  border-radius: 14rpx;
  border: 1rpx solid #e3edf8;
  background: #f4f8fd;
}

.jc-hero-item {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 6rpx;
}

.jc-hero-divider {
  width: 1rpx;
  margin: 0 18rpx;
  background: #dbe6f3;
}

.jc-hero-label {
  font-size: 19rpx;
  color: #7c8a9c;
  line-height: 1.4;
}

.jc-hero-value {
  color: #17518c;
  font-size: 34rpx;
  font-weight: 700;
  line-height: 1.25;
}

.jc-hero-value-arrival {
  color: #131a24;
  font-size: 28rpx;
}

.jc-hero-arrival {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8rpx;
}

.jc-cross-day {
  padding: 2rpx 8rpx;
  border-radius: 6rpx;
  background: #fff4e0;
  color: #9a5b00;
  font-size: 17rpx;
  line-height: 1.5;
}

.jc-metrics {
  display: flex;
  gap: 10rpx;
  margin-top: 14rpx;
}

.jc-metric {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4rpx;
  padding: 12rpx 14rpx;
  border-radius: 12rpx;
  border: 1rpx solid #e9eff6;
  background: #ffffff;
}

.jc-metric-label {
  font-size: 18rpx;
  color: #7c8a9c;
  line-height: 1.4;
}

.jc-metric-value {
  color: #1b2a3a;
  font-size: 23rpx;
  font-weight: 600;
  line-height: 1.35;
  word-break: break-all;
}

.jc-foot {
  display: flex;
  flex-direction: column;
  gap: 4rpx;
  margin-top: 14rpx;
  padding-top: 12rpx;
  border-top: 1rpx dashed #e2e9f2;
}

.jc-foot-line {
  color: #8a97a7;
  font-size: 18rpx;
  line-height: 1.5;
  word-break: break-all;
}

.jc-tap-hint {
  margin-top: 8rpx;
  text-align: right;
}

.jc-tap-hint-text {
  color: #1d6fbf;
  font-size: 18rpx;
  line-height: 1.5;
}
</style>
