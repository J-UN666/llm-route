<template>
  <view
    class="journey-detail"
    :class="{ 'journey-detail-empty': !model.hasPlan }"
    :data-plan-id="model.planId || ''"
    :data-segment-count="model.segments.length"
  >
    <view class="jd-head">
      <view class="jd-head-main">
        <text class="jd-title">分段明细</text>
        <text class="jd-count" v-if="model.hasPlan">{{ model.segments.length }} 段</text>
      </view>
      <text class="jd-plan-id">planId {{ model.planId || '未提供' }}</text>
    </view>

    <!-- 空计划：明确清空状态 -->
    <view v-if="!model.hasPlan" class="jd-empty">
      <text class="jd-empty-ico">◌</text>
      <text class="jd-empty-title">暂无分段明细</text>
      <text class="jd-empty-text">{{ model.emptyText }}</text>
      <text class="jd-empty-text">查询失败或没有可用方案时，详情与地图一起清空，不展示上一次方案的分段。</text>
    </view>

    <template v-else>
      <!-- 方案摘要：校园步行到校门，再换乘城市公交/地铁并到达 -->
      <view class="jd-summary">
        <view class="jd-summary-row">
          <text class="jd-summary-label">出校点</text>
          <text class="jd-summary-value">{{ gateText }}</text>
          <text class="jd-summary-note">{{ gateIdText }}</text>
        </view>
        <view class="jd-summary-row">
          <text class="jd-summary-label">出发 / 到达</text>
          <text class="jd-summary-value">{{ departureText }} → {{ arrivalText }}</text>
          <text v-if="crossDay" class="jd-summary-note jd-cross-day">跨日</text>
        </view>
        <view class="jd-summary-grid">
          <view class="jd-summary-cell">
            <text class="jd-cell-label">全程</text>
            <text class="jd-cell-value">{{ durationText }}</text>
          </view>
          <view class="jd-summary-cell">
            <text class="jd-cell-label">步行</text>
            <text class="jd-cell-value">{{ walkingText }}</text>
          </view>
          <view class="jd-summary-cell">
            <text class="jd-cell-label">换乘</text>
            <text class="jd-cell-value">{{ transferText }}</text>
          </view>
          <view class="jd-summary-cell">
            <text class="jd-cell-label">费用</text>
            <text class="jd-cell-value">{{ costText }}</text>
          </view>
        </view>
      </view>

      <view class="jd-list">
        <view
          v-for="segment in model.segments"
          :key="segment.sequence"
          class="jd-seg"
          :class="[`jd-seg-${segment.category}`, { 'jd-seg-missing': !segment.hasGeometry }]"
          :data-sequence="segment.sequence"
          :data-mode="segment.mode"
        >
          <view class="jd-seg-head">
            <view class="jd-seq" :style="{ backgroundColor: segment.color }">
              <text class="jd-seq-text">{{ segment.sequence }}</text>
            </view>
            <text class="jd-mode" :style="{ color: segment.color }">{{ segment.modeLabel }}</text>
            <text class="jd-category">{{ segment.categoryLabel }}</text>
            <text class="jd-seg-duration">{{ segment.durationText }}</text>
          </view>

          <text class="jd-desc">{{ segment.description }}</text>

          <view v-if="segment.lineText" class="jd-line">
            <text class="jd-line-text">{{ segment.lineText }}</text>
          </view>

          <view v-if="segment.stationText" class="jd-station">
            <text class="jd-station-text">{{ segment.stationText }}</text>
          </view>

          <view class="jd-metrics">
            <text class="jd-metric">已知距离 {{ segment.distanceText }}</text>
            <text class="jd-metric">已知耗时 {{ segment.durationText }}</text>
            <text class="jd-metric">源坐标系 {{ segment.sourceCoordinateSystem || '未提供' }}</text>
          </view>

          <view class="jd-trace" :class="{ 'jd-trace-missing': !segment.hasGeometry }">
            <text class="jd-trace-badge" :class="{ 'jd-trace-badge-missing': !segment.hasGeometry }">
              {{ segment.hasGeometry ? '有轨迹' : '无轨迹' }}
            </text>
            <text class="jd-trace-text">{{ segment.geometryText }}</text>
          </view>
          <text v-if="segment.traceMissingText" class="jd-trace-reason">{{ segment.traceMissingText }}</text>
        </view>
      </view>

      <view v-if="!model.sequenceComplete" class="jd-warn">
        ⚠ 分段序号不是从 1 连续递增，展示顺序按后端返回的 segments 数组，不自行重排。
      </view>
      <view v-if="model.missingGeometryCount > 0" class="jd-warn">
        ⚠ 共 {{ model.missingGeometryCount }} 段无可用几何：文字详情仍然完整展示，地图不为这些段绘制轨迹。
      </view>

      <view class="jd-foot">
        <text class="jd-foot-line">来源 {{ sourcesText }}</text>
        <text class="jd-foot-line jd-foot-note">
          分段顺序与数字均来自后端 JourneyPlan；地图与详情使用同一个 planId，不按卡片序号或重新查询取详情。
        </text>
      </view>
    </template>
  </view>
</template>

<script setup lang="ts">
/**
 * W1-11 路线详情：按 `segments.sequence` 展示校园步行、出校、城市步行、公交/地铁、换乘与到达。
 *
 * - 输入只有页面当前 `selectedPlan`（可能为 null），组件不查询、不重算路线；
 * - 每段展示 mode、说明、站点/线路、已知距离与秒数、源坐标系；
 * - 几何缺失时文字详情照常展示，并明确标记“无轨迹”；
 * - plan 为 null（失败/空方案）时渲染清空状态，不保留上一次方案。
 */

import { computed } from 'vue';
import type { JourneyPlan } from '@/types/journey';
import { buildJourneyDetailModel } from '@/services/journey-detail';
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

interface JourneyDetailProps {
  /** 页面当前选中的方案；失败或无方案时为 null。 */
  plan: JourneyPlan | null;
}

const props = defineProps<JourneyDetailProps>();

const model = computed(() => buildJourneyDetailModel(props.plan));

const gateText = computed(() => (props.plan ? formatGateName(props.plan) : '未提供'));
const gateIdText = computed(() => {
  const gateId = props.plan?.gateId;
  return gateId ? `gateId ${gateId}` : 'gateId 未提供';
});
const durationText = computed(() =>
  props.plan ? formatDurationMinutes(props.plan.totalDurationSeconds) : '未提供'
);
const walkingText = computed(() =>
  props.plan ? formatWalkingValue(props.plan.totalWalkingMeters) : '未提供'
);
const transferText = computed(() =>
  props.plan ? formatTransferValue(props.plan.transferCount) : '未提供'
);
const costText = computed(() => (props.plan ? formatCostValue(props.plan.costYuan) : '未提供'));
const departureText = computed(() => {
  if (!props.plan) return '未提供';
  return formatArrivalTime(props.plan.departureTime, props.plan.departureTime);
});
const arrivalText = computed(() =>
  props.plan ? formatArrivalTime(props.plan.arrivalTime, props.plan.departureTime) : '未提供'
);
const crossDay = computed(() =>
  props.plan ? isCrossDayArrival(props.plan.arrivalTime, props.plan.departureTime) : false
);
const sourcesText = computed(() => (props.plan ? formatPlanSources(props.plan.sources) : '未提供'));
</script>

<style scoped>
.journey-detail {
  margin-top: 22rpx;
  padding: 22rpx 24rpx;
  border-radius: 18rpx;
  border: 1rpx solid #e3ecf6;
  background: linear-gradient(180deg, #f7fbff 0%, #ffffff 46%);
  box-shadow: 0 4rpx 14rpx rgba(16, 54, 92, 0.05);
}

.journey-detail-empty {
  background: #fbfcfd;
}

.jd-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 8rpx;
}

.jd-head-main {
  display: flex;
  align-items: center;
  gap: 12rpx;
}

.jd-title {
  color: #131a24;
  font-size: 27rpx;
  font-weight: 700;
}

.jd-count {
  padding: 2rpx 10rpx;
  border-radius: 8rpx;
  background: #eaf3fd;
  color: #1d6fbf;
  font-size: 19rpx;
}

.jd-plan-id {
  color: #8a97a7;
  font-size: 19rpx;
  line-height: 1.5;
  word-break: break-all;
}

.jd-empty {
  display: flex;
  flex-direction: column;
  gap: 8rpx;
  margin-top: 16rpx;
  padding: 18rpx;
  border-radius: 14rpx;
  border: 1rpx dashed #d6e1ee;
  background: #ffffff;
}

.jd-empty-ico {
  color: #9fb0c2;
  font-size: 30rpx;
  line-height: 1;
}

.jd-empty-title {
  color: #1b2a3a;
  font-size: 24rpx;
  font-weight: 600;
}

.jd-empty-text {
  color: #7c8a9c;
  font-size: 20rpx;
  line-height: 1.6;
}

.jd-summary {
  margin-top: 16rpx;
  padding: 16rpx 18rpx;
  border-radius: 14rpx;
  border: 1rpx solid #e3edf8;
  background: #f4f8fd;
}

.jd-summary-row {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 10rpx;
  padding: 4rpx 0;
}

.jd-summary-label {
  color: #7c8a9c;
  font-size: 20rpx;
}

.jd-summary-value {
  color: #17518c;
  font-size: 23rpx;
  font-weight: 600;
  word-break: break-all;
}

.jd-summary-note {
  color: #8a97a7;
  font-size: 18rpx;
}

.jd-cross-day {
  padding: 2rpx 8rpx;
  border-radius: 6rpx;
  background: #fff4e0;
  color: #9a5b00;
}

.jd-summary-grid {
  display: flex;
  gap: 10rpx;
  margin-top: 12rpx;
}

.jd-summary-cell {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4rpx;
  padding: 10rpx 12rpx;
  border-radius: 10rpx;
  border: 1rpx solid #e9eff6;
  background: #ffffff;
}

.jd-cell-label {
  color: #7c8a9c;
  font-size: 18rpx;
}

.jd-cell-value {
  color: #1b2a3a;
  font-size: 21rpx;
  font-weight: 600;
  word-break: break-all;
}

.jd-list {
  margin-top: 18rpx;
  display: flex;
  flex-direction: column;
  gap: 14rpx;
}

.jd-seg {
  padding: 16rpx 18rpx;
  border-radius: 14rpx;
  border: 1rpx solid #e9eff6;
  border-left-width: 8rpx;
  background: #ffffff;
}

.jd-seg-campus {
  border-left-color: #2e7d32;
}

.jd-seg-city-walk {
  border-left-color: #f57f17;
}

.jd-seg-ride {
  border-left-color: #1d6fbf;
}

.jd-seg-other {
  border-left-color: #6d4c41;
}

.jd-seg-missing {
  border-left-style: dashed;
  background: #fdfdfd;
}

.jd-seg-head {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10rpx;
}

.jd-seq {
  width: 34rpx;
  height: 34rpx;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.jd-seq-text {
  color: #ffffff;
  font-size: 19rpx;
  font-weight: 700;
  line-height: 1;
}

.jd-mode {
  font-size: 24rpx;
  font-weight: 700;
}

.jd-category {
  padding: 2rpx 8rpx;
  border-radius: 6rpx;
  background: #f1f5fa;
  color: #5d6b7c;
  font-size: 17rpx;
}

.jd-seg-duration {
  margin-left: auto;
  color: #5d6b7c;
  font-size: 19rpx;
}

.jd-desc {
  display: block;
  margin-top: 10rpx;
  color: #1b2a3a;
  font-size: 22rpx;
  line-height: 1.55;
}

.jd-line,
.jd-station {
  margin-top: 8rpx;
  padding: 8rpx 12rpx;
  border-radius: 10rpx;
  background: #f4f8fd;
}

.jd-line-text {
  color: #17518c;
  font-size: 20rpx;
  line-height: 1.5;
  font-weight: 600;
}

.jd-station-text {
  color: #1b2a3a;
  font-size: 20rpx;
  line-height: 1.5;
}

.jd-metrics {
  display: flex;
  flex-wrap: wrap;
  gap: 8rpx 16rpx;
  margin-top: 10rpx;
}

.jd-metric {
  color: #7c8a9c;
  font-size: 18rpx;
  line-height: 1.5;
}

.jd-trace {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 10rpx;
  margin-top: 10rpx;
  padding-top: 10rpx;
  border-top: 1rpx dashed #e2e9f2;
}

.jd-trace-badge {
  padding: 2rpx 8rpx;
  border-radius: 6rpx;
  background: #e8f5e9;
  color: #2e7d32;
  font-size: 17rpx;
  flex-shrink: 0;
}

.jd-trace-badge-missing {
  background: #fff4e0;
  color: #9a5b00;
}

.jd-trace-text {
  color: #8a97a7;
  font-size: 18rpx;
  line-height: 1.5;
  word-break: break-all;
}

.jd-trace-reason {
  display: block;
  margin-top: 6rpx;
  color: #9a5b00;
  font-size: 18rpx;
  line-height: 1.5;
}

.jd-warn {
  margin-top: 12rpx;
  padding: 10rpx 12rpx;
  border-radius: 10rpx;
  background: #fff8eb;
  color: #8a5a00;
  font-size: 19rpx;
  line-height: 1.55;
}

.jd-foot {
  margin-top: 16rpx;
  padding-top: 12rpx;
  border-top: 1rpx dashed #e2e9f2;
  display: flex;
  flex-direction: column;
  gap: 6rpx;
}

.jd-foot-line {
  color: #8a97a7;
  font-size: 18rpx;
  line-height: 1.5;
  word-break: break-all;
}

.jd-foot-note {
  color: #9fb0c2;
}
</style>
