<template>
  <view
    class="journey-map"
    :class="{ 'journey-map-empty': !model.hasPlan }"
    :data-plan-id="model.planId || ''"
    :data-canvas-state="model.state"
    :data-dpr="dpr"
    :data-canvas-css="canvasCssText"
    :data-canvas-buffer="canvasBufferText"
    :data-draw-segments="drawReport ? drawReport.segmentsDrawn : 0"
    :data-draw-markers="drawReport ? drawReport.markersDrawn : 0"
  >
    <view class="jm-head">
      <view class="jm-head-main">
        <text class="jm-title">方案地图</text>
      </view>
    </view>

    <!-- 空计划：失败/无方案时明确清空，不保留上一次方案的线条、站点或校门 -->
    <view v-if="!model.hasPlan" class="jm-empty">
      <text class="jm-empty-ico">▱</text>
      <text class="jm-empty-title">地图已清空</text>
      <text class="jm-empty-text">
        当前没有可显示的路线。
      </text>
      <text class="jm-empty-text">查询并选择方案后显示地图。</text>
    </view>

    <template v-else>
      <view class="jm-canvas-wrap">
        <canvas id="journey-map-canvas" type="2d" class="jm-canvas"></canvas>
        <view v-if="model.state === 'noGeometry'" class="jm-overlay-badge">
          <text class="jm-overlay-text">无轨迹 · 仅显示已知端点</text>
        </view>
        <view v-if="drawError" class="jm-overlay-error">
          <text class="jm-overlay-text">{{ drawError }}</text>
        </view>
      </view>

      <view class="jm-legend">
        <view v-for="item in legendItems" :key="item.category" class="jm-legend-item">
          <view class="jm-legend-line" :style="legendLineStyle(item)"></view>
          <text class="jm-legend-label">{{ item.label }}</text>
        </view>
      </view>

      <view class="jm-markers">
        <text class="jm-markers-label">途经点</text>
        <text class="jm-markers-text">{{ markerSummary }}</text>
      </view>

      <view class="jm-meta">
        <text class="jm-meta-line">来源 {{ sourcesText }}</text>
        <text class="jm-meta-line">查询时间 {{ queriedAtText }}</text>
        <text class="jm-meta-line">出校点 {{ model.gateText }}</text>
        <text v-if="model.hasDrawableGeometry" class="jm-meta-line">
          轨迹 {{ model.drawableSegmentCount }}/{{ model.segments.length }} 段 · 标记 {{ model.markers.length }} 个
        </text>
        <text v-else class="jm-meta-line jm-meta-warn">
          该方案 {{ model.segments.length }} 段均无可用轨迹 · 标记 {{ model.markers.length }} 个
        </text>
        <text v-if="model.missingGeometryCount > 0" class="jm-meta-line jm-meta-warn">
          其中 {{ model.missingGeometryCount }} 段缺轨迹：文字详情仍完整，地图不为这些段连线。
        </text>
      </view>

    </template>
  </view>
</template>

<script setup lang="ts">
/** 绘制当前行程。WGS84 段转换为 GCJ-02 后统一投影；缺失轨迹仅展示已有部分。 */

import { computed, getCurrentInstance, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import type { JourneyPlan } from '@/types/journey';
import { formatBeijingDateTime } from '@/services/api';
import { formatPlanSources } from '@/services/journey-format';
import {
  drawJourneyMap,
  fitJourneyMapViewport,
  buildJourneyMapModel,
  type JourneyMapDrawReport
} from '@/services/journey-map';
import type { SegmentCategory } from '@/services/journey-segment-meta';

interface JourneyMapProps {
  /** 页面当前选中的方案；失败或无方案时为 null，此时清空图层。 */
  plan: JourneyPlan | null;
}

const props = defineProps<JourneyMapProps>();

const instance = getCurrentInstance();
const model = computed(() => buildJourneyMapModel(props.plan));

const canvasCssText = ref('—');
const canvasBufferText = ref('—');
const dpr = ref(1);
const drawError = ref('');
const drawReport = ref<JourneyMapDrawReport | null>(null);

let drawToken = 0;

const sourcesText = computed(() => (model.value.sources.length > 0 ? formatPlanSources(model.value.sources) : '未提供'));
const queriedAtText = computed(() =>
  model.value.queriedAt ? formatBeijingDateTime(model.value.queriedAt) : '未提供'
);

const legendItems = computed(() => {
  const present = new Set<SegmentCategory>(model.value.segments.map((segment) => segment.category));
  const items: Array<{ category: SegmentCategory; label: string; color: string; dashed: boolean }> = [
    { category: 'campus', label: '校园步行', color: '#2e7d32', dashed: false },
    { category: 'city-walk', label: '城市步行', color: '#f57f17', dashed: true },
    { category: 'ride', label: '公交·地铁', color: '#1d6fbf', dashed: false },
    { category: 'other', label: '其他交通', color: '#6d4c41', dashed: true }
  ];
  return items.filter((item) => present.has(item.category));
});

function legendLineStyle(item: { color: string; dashed: boolean }): Record<string, string> {
  return {
    backgroundColor: item.color,
    borderStyle: item.dashed ? 'dashed' : 'solid'
  };
}

const markerSummary = computed(() => {
  // 缺坐标的角色提示由地图模型统一给出（与“是否绘制标记”的判定同源）。
  const notes = model.value.missingMarkerNotes;
  const base =
    model.value.markers.length === 0
      ? '无可用坐标点位'
      : model.value.markers.map((marker) => marker.label).join(' · ');
  return notes.length > 0 ? `${base}（${notes.join('、')}）` : base;
});

function resetDrawState(): void {
  canvasCssText.value = '—';
  canvasBufferText.value = '—';
  drawReport.value = null;
  drawError.value = '';
}

function readPixelRatio(): number {
  try {
    const info = uni.getWindowInfo ? uni.getWindowInfo() : uni.getSystemInfoSync();
    const ratio = info && typeof info.pixelRatio === 'number' ? info.pixelRatio : 1;
    return Math.min(3, Math.max(1, ratio));
  } catch (error) {
    console.warn('[交大智行] 读取设备像素比失败，按 1 处理', error);
    return 1;
  }
}

/**
 * 取组件作用域内的 Canvas 2D 节点。
 * 说明：小程序里页面级 SelectorQuery 无法选中自定义组件内部的节点，必须 `in(组件实例)`；
 * uni 的 createSelectorQuery 需要在组件代理带上 $scope，这里显式取原生 scope 兜底，
 * 并统一用 exec 回调读取节点，避免不同基础库下节点回调时机差异。
 */
function queryCanvasNode(): Promise<any> {
  return new Promise((resolve) => {
    try {
      const wxApi = (globalThis as any).wx;
      const query: any =
        wxApi && typeof wxApi.createSelectorQuery === 'function'
          ? wxApi.createSelectorQuery()
          : (uni.createSelectorQuery() as any);
      const proxy: any = instance && instance.proxy;
      const scope = proxy && proxy.$scope ? proxy.$scope : proxy;
      if (scope) {
        query.in(scope);
      }
      query.select('#journey-map-canvas').fields({ node: true, size: true });
      query.exec((result: any) => {
        resolve(result && result[0] ? result[0] : null);
      });
    } catch (error) {
      console.warn('[交大智行] Canvas 节点查询失败', error);
      resolve(null);
    }
  });
}

function paintCanvas(currentModel: ReturnType<typeof buildJourneyMapModel>, field: any): void {
  const canvas = field.node as any;
  const ctx = canvas.getContext ? (canvas.getContext('2d') as CanvasRenderingContext2D | null) : null;
  if (!ctx) {
    drawError.value = '当前环境不支持 Canvas 2D 上下文';
    return;
  }

  const cssWidth = Math.round(Number(field.width) || 0);
  const cssHeight = Math.round(Number(field.height) || 0);
  if (cssWidth <= 0 || cssHeight <= 0) {
    drawError.value = `Canvas 尺寸无效（${cssWidth}×${cssHeight}）`;
    return;
  }

  const pixelRatio = readPixelRatio();
  dpr.value = pixelRatio;
  canvas.width = Math.round(cssWidth * pixelRatio);
  canvas.height = Math.round(cssHeight * pixelRatio);
  ctx.setTransform(1, 0, 0, 1, 0, 0);
  ctx.scale(pixelRatio, pixelRatio);

  canvasCssText.value = `${cssWidth}×${cssHeight}`;
  canvasBufferText.value = `${canvas.width}×${canvas.height}`;

  try {
    const viewport = fitJourneyMapViewport(currentModel, cssWidth, cssHeight, 26);
    const report = drawJourneyMap(ctx, currentModel, viewport);
    drawReport.value = report;
    drawError.value = '';
  } catch (error) {
    drawReport.value = null;
    drawError.value = '地图绘制失败，请切换方案或重新查询';
    console.warn('[交大智行] 地图绘制失败', error);
  }
}

async function drawCurrentPlan(): Promise<void> {
  const token = ++drawToken;
  const currentModel = model.value;
  if (!currentModel.hasPlan) {
    resetDrawState();
    return;
  }

  await nextTick();
  if (token !== drawToken) return;

  for (let attempt = 0; attempt < 3; attempt += 1) {
    const field = await queryCanvasNode();
    if (token !== drawToken) return;
    if (field && field.node) {
      paintCanvas(currentModel, field);
      return;
    }
    await new Promise((resolve) => setTimeout(resolve, 180));
    if (token !== drawToken) return;
  }

  drawError.value = '未取到 Canvas 节点，无法绘制轨迹';
}

function scheduleDraw(): void {
  void drawCurrentPlan();
}

watch(
  () => props.plan,
  (plan) => {
    if (!plan) {
      drawToken += 1;
      resetDrawState();
      return;
    }
    scheduleDraw();
  },
  { flush: 'post' }
);

onMounted(() => {
  scheduleDraw();
});

onBeforeUnmount(() => {
  drawToken += 1;
});
</script>

<style scoped>
.journey-map {
  margin-top: 22rpx;
  padding: 22rpx 24rpx;
  border-radius: 18rpx;
  border: 1rpx solid #e3ecf6;
  background: linear-gradient(180deg, #f7fbff 0%, #ffffff 40%);
  box-shadow: 0 4rpx 14rpx rgba(16, 54, 92, 0.05);
}

.journey-map-empty {
  background: #fbfcfd;
}

.jm-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 8rpx;
}

.jm-head-main {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12rpx;
}

.jm-title {
  color: #131a24;
  font-size: 27rpx;
  font-weight: 700;
}

.jm-empty {
  display: flex;
  flex-direction: column;
  gap: 8rpx;
  margin-top: 16rpx;
  padding: 18rpx;
  border-radius: 14rpx;
  border: 1rpx dashed #d6e1ee;
  background: #ffffff;
}

.jm-empty-ico {
  color: #9fb0c2;
  font-size: 30rpx;
  line-height: 1;
}

.jm-empty-title {
  color: #1b2a3a;
  font-size: 24rpx;
  font-weight: 600;
}

.jm-empty-text {
  color: #7c8a9c;
  font-size: 20rpx;
  line-height: 1.6;
}

.jm-canvas-wrap {
  position: relative;
  margin-top: 16rpx;
  border-radius: 14rpx;
  overflow: hidden;
  border: 1rpx solid #e3ecf6;
  background: #f4f8fd;
}

.jm-canvas {
  display: block;
  width: 100%;
  height: 480rpx;
}

.jm-overlay-badge {
  position: absolute;
  top: 12rpx;
  left: 50%;
  transform: translateX(-50%);
  padding: 6rpx 16rpx;
  border-radius: 20rpx;
  background: rgba(255, 248, 235, 0.94);
  border: 1rpx solid #f0d9a8;
}

.jm-overlay-error {
  position: absolute;
  top: 12rpx;
  left: 50%;
  transform: translateX(-50%);
  padding: 6rpx 16rpx;
  border-radius: 20rpx;
  background: rgba(255, 235, 235, 0.96);
  border: 1rpx solid #f0c0c0;
}

.jm-overlay-text {
  color: #8a5a00;
  font-size: 18rpx;
  line-height: 1.4;
  white-space: nowrap;
}

.jm-overlay-error .jm-overlay-text {
  color: #a12626;
}

.jm-legend {
  display: flex;
  flex-wrap: wrap;
  gap: 8rpx 20rpx;
  margin-top: 12rpx;
}

.jm-legend-item {
  display: flex;
  align-items: center;
  gap: 8rpx;
}

.jm-legend-line {
  width: 30rpx;
  height: 4rpx;
  border-radius: 2rpx;
  border-width: 0;
}

.jm-legend-label {
  color: #5d6b7c;
  font-size: 18rpx;
}

.jm-markers {
  display: flex;
  flex-direction: column;
  gap: 4rpx;
  margin-top: 12rpx;
  padding: 10rpx 12rpx;
  border-radius: 10rpx;
  background: #f4f8fd;
}

.jm-markers-label {
  color: #7c8a9c;
  font-size: 18rpx;
}

.jm-markers-text {
  color: #1b2a3a;
  font-size: 19rpx;
  line-height: 1.55;
  word-break: break-all;
}

.jm-meta {
  display: flex;
  flex-direction: column;
  gap: 4rpx;
  margin-top: 12rpx;
}

.jm-meta-line {
  color: #8a97a7;
  font-size: 18rpx;
  line-height: 1.5;
  word-break: break-all;
}

.jm-meta-warn {
  color: #9a5b00;
}

</style>
