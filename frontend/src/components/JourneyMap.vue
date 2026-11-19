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
      <view class="jm-sections">
        <button v-for="tab in sections" :key="tab.id" :class="{ 'jm-active': section === tab.id }"
          @tap="section = tab.id">{{ tab.label }}</button>
      </view>
      <template v-if="showNative">
        <map :id="mapId" :key="mapId" class="jm-map"
          :longitude="center.longitude" :latitude="center.latitude" :scale="scale"
          :include-points="fitPoints" :polyline="polylines" :markers="nativeMarkers"
          :enable-scroll="gestures" :enable-zoom="gestures" :show-location="false"
          @updated="mapRendered" @error="fallback('底图加载失败')" />
        <view class="jm-controls">
          <button aria-label="放大地图" @tap="zoom(1)">＋</button>
          <button aria-label="缩小地图" @tap="zoom(-1)">−</button>
          <button @tap="recenter">回到本段</button>
          <button @tap="gestures = !gestures">{{ gestures ? '退出地图手势' : '开启地图手势' }}</button>
          <button @tap="fallback('轨迹示意图')">示意图</button>
        </view>
      </template>
      <view v-else class="jm-canvas-wrap">
        <canvas id="journey-map-canvas" type="2d" class="jm-canvas"></canvas>
        <view v-if="model.state === 'noGeometry'" class="jm-overlay-badge">
          <text class="jm-overlay-text">无轨迹 · 仅显示已知端点</text>
        </view>
        <view v-if="drawError" class="jm-overlay-error">
          <text class="jm-overlay-text">{{ drawError }}</text>
        </view>
      </view>
      <view v-if="nativeAvailable && !showNative && fallbackReason" class="jm-controls">
        <text class="jm-meta-line">{{ fallbackReason }} · 正在显示轨迹示意</text>
        <button v-if="online && fitPoints.length" @tap="retryMap">打开底图</button>
      </view>

      <view class="jm-legend">
        <view v-for="item in legendItems" :key="item.categoryLabel" class="jm-legend-item">
          <view class="jm-legend-line" :style="legendLineStyle(item)"></view>
          <text class="jm-legend-label">{{ item.categoryLabel }}</text>
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
          当前视图无可用轨迹，显示已知点位。
        </text>
        <text v-if="model.missingGeometryCount > 0" class="jm-meta-line jm-meta-warn">
          {{ model.missingGeometryCount }} 段缺少轨迹，可继续查看分段说明。
        </text>
      </view>

    </template>
  </view>
</template>

<script setup lang="ts">
/** 绘制当前行程。WGS84 段转换为 GCJ-02 后统一投影；缺失轨迹仅展示已有部分。 */

import { computed, getCurrentInstance, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import type { JourneyPlan } from '@/types/journey';
import { DEMO_MODE, formatBeijingDateTime } from '@/services/api';
import { formatPlanSources } from '@/services/journey-format';
import {
  drawJourneyMap,
  fitJourneyMapViewport,
  buildJourneyMapModel,
  type JourneyMapDrawReport,
  type JourneyMapSection,
  type LonLat
} from '@/services/journey-map';
import { segmentStyle } from '@/services/journey-segment-meta';

interface JourneyMapProps {
  /** 页面当前选中的方案；失败或无方案时为 null，此时清空图层。 */
  plan: JourneyPlan | null;
}

const props = defineProps<JourneyMapProps>();

const instance = getCurrentInstance();
const sections: Array<{ id: JourneyMapSection; label: string }> = [
  { id: 'campus', label: '① 校内到校门' },
  { id: 'city', label: '② 校门到目的地' },
  { id: 'all', label: '全程' }
];
const section = ref<JourneyMapSection>('campus');
const model = computed(() => buildJourneyMapModel(props.plan, section.value));
let nativeAvailable = false;
// #ifdef MP-WEIXIN
nativeAvailable = !DEMO_MODE;
// #endif
const online = ref(true);
const fallbackReason = ref('');
const gestures = ref(false);
const scale = ref(16);
const revision = ref(0);
const mapId = computed(() => `journey-native-map-${revision.value}`);
let renderTimer: ReturnType<typeof setTimeout> | undefined;

const polylines = computed(() => model.value.segments.filter((segment) => segment.hasGeometry).map((segment) => ({
  points: segment.points, color: segment.color, width: segment.lineWidth, dottedLine: segment.dashed
})));

const fitPoints = computed<LonLat[]>(() => {
  const points = [...model.value.markers.map((marker) => marker.points),
    ...polylines.value.flatMap((line) => line.points)];
  if (points.length < 2) return points;
  let west = Infinity, south = Infinity, east = -Infinity, north = -Infinity;
  for (const point of points) {
    west = Math.min(west, point.longitude);
    east = Math.max(east, point.longitude);
    south = Math.min(south, point.latitude);
    north = Math.max(north, point.latitude);
  }
  const southwest = { longitude: west, latitude: south };
  return west === east && south === north ? [southwest] : [southwest, { longitude: east, latitude: north }];
});

const center = computed(() => {
  const a = fitPoints.value[0] || { longitude: 103.987, latitude: 30.766 };
  const b = fitPoints.value[1] || a;
  return { longitude: (a.longitude + b.longitude) / 2, latitude: (a.latitude + b.latitude) / 2 };
});

const nativeMarkers = computed(() => {
  const grouped = new Map<string, typeof model.value.markers>();
  for (const marker of model.value.markers) {
    const { longitude, latitude } = marker.points;
    const position = `${longitude.toFixed(5)},${latitude.toFixed(5)}`;
    const previous = grouped.get(position) || [];
    grouped.set(position, [...previous, marker]);
  }
  return Array.from(grouped.values(), (group, id) => ({
    ...group[0].points, id: id + 1, iconPath: '/static/route-pin.png', width: 16, height: 16,
    anchor: { x: 0.5, y: 0.5 },
    callout: {
      content: group.map((marker) => marker.label).join('\n'), color: group[0].color,
      bgColor: '#ffffff', borderRadius: 5, padding: 5, fontSize: 11,
      display: group.some((marker) => ['origin', 'gate', 'destination'].includes(marker.role)) ? 'ALWAYS' : 'BYCLICK'
    }
  }));
});

const showNative = computed(() => nativeAvailable && online.value && !fallbackReason.value && fitPoints.value.length > 0);

function clearRenderTimer(): void {
  clearTimeout(renderTimer);
  renderTimer = undefined;
}

function mapRendered(event: { target?: { id?: string } }): void {
  if (!event.target?.id || event.target.id === mapId.value) clearRenderTimer();
}

function fallback(reason: string): void {
  gestures.value = false;
  fallbackReason.value = reason;
}

function networkChanged(status: { isConnected: boolean }): void {
  online.value = status.isConnected;
  if (!status.isConnected) fallback('网络已断开');
}

function resetView(): void {
  scale.value = 16;
  revision.value += 1;
}

function recenter(): void {
  const points = fitPoints.value;
  const context = uni.createMapContext(mapId.value, instance?.proxy);
  if (points.length > 1) {
    context.includePoints({ points, padding: [28, 28, 28, 28] });
  } else if (points[0]) {
    scale.value = 16;
    context.moveToLocation(points[0]);
  }
}

function retryMap(): void {
  fallbackReason.value = '';
  resetView();
}

function zoom(delta: number): void {
  const id = mapId.value;
  uni.createMapContext(id, instance?.proxy).getScale({
    success: async ({ scale: current }) => {
      if (!showNative.value || id !== mapId.value) return;
      const next = Math.max(5, Math.min(20, Math.round(current) + delta));
      if (next === scale.value && current !== next) {
        scale.value = current;
        await nextTick();
      }
      if (showNative.value && id === mapId.value) scale.value = next;
    },
    fail: () => {
      if (id === mapId.value) fallback('无法操作底图');
    }
  });
}

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

const legendItems = computed(() => Array.from(new Map(model.value.segments.map((segment) => {
  const style = segmentStyle(segment.mode);
  return [style.categoryLabel, style];
})).values()));

function legendLineStyle(item: { color: string; dashed: boolean }): Record<string, string> {
  return {
    borderTop: `4rpx ${item.dashed ? 'dashed' : 'solid'} ${item.color}`
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
  if (showNative.value || !currentModel.hasPlan) {
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

function renderMap(): void {
  clearRenderTimer();
  void drawCurrentPlan();
  if (showNative.value) {
    renderTimer = setTimeout(() => fallback('底图加载超时'), 8000);
  }
}

watch(() => props.plan, () => { section.value = 'campus'; gestures.value = false; });
watch([() => props.plan, section], resetView);
watch([model, showNative, mapId], renderMap, { flush: 'post' });

onMounted(() => {
  renderMap();
  if (nativeAvailable) {
    uni.getNetworkType({ success: (result) => networkChanged({ isConnected: result.networkType !== 'none' }) });
    uni.onNetworkStatusChange(networkChanged);
  }
});

onBeforeUnmount(() => {
  drawToken += 1;
  clearRenderTimer();
  if (nativeAvailable) uni.offNetworkStatusChange?.(networkChanged);
});
</script>

<style scoped>
.jm-sections, .jm-controls { display: flex; flex-wrap: wrap; align-items: center; gap: 8rpx; margin: 14rpx 0; }
.jm-sections button, .jm-controls button { margin: 0; padding: 4rpx 12rpx; font-size: 23rpx; color: #153c65; }
.jm-sections .jm-active { background: #17395c; color: #fff; }
.jm-map { display: block; width: 100%; height: 430rpx; border-radius: 12rpx; }
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
