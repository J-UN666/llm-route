<template>
  <view class="page">
    <!-- 顶部品牌区：深色导航 + 项目名（对齐网页版顶栏） -->
    <view class="hero" :style="{ paddingTop: statusBarHeight + 'px' }">
      <view class="brand-row">
        <view class="brand-mark">
          <text class="brand-mark-text">交</text>
        </view>
        <view class="brand-text">
          <text class="brand-title">交大智行</text>
          <text class="brand-sub">校园 · 城市一体化出行规划</text>
        </view>
      </view>
      <view class="hero-meta">
        <text class="hero-campus">西南交通大学 · 犀浦校区</text>
        <view class="status-pill">
          <view class="status-dot"></view>
          <text class="status-text">校园出行</text>
        </view>
      </view>
    </view>

    <view class="content">
      <view class="notice">
        <text class="notice-ico">ⓘ</text>
        <text class="notice-text">
          选择校园起点和成都目的地，查询公交、地铁路线。
        </text>
      </view>

      <!-- 规划输入卡 -->
      <navigator url="/pages/assistant/index" class="assistant-entry" hover-class="assistant-entry-pressed">
        <view>
          <text class="assistant-entry-title">自然语言问路</text>
          <text class="assistant-entry-example">试试说「从图书馆去天府广场，少走路」</text>
        </view>
        <text aria-hidden="true">›</text>
      </navigator>

      <view class="card">
        <view class="card-head">
          <view class="card-title">
            <text class="card-ico">◎</text>
            <text>规划路线</text>
          </view>
        </view>

        <view class="card-body">
          <!-- 起点：只允许校园内部已核实地点 -->
          <view id="field-origin">
            <PlacePicker
              ref="originPickerRef"
              v-model="originText"
              class="origin-picker"
              scope="CAMPUS"
              label="校园起点"
              tag-text="起"
              tag-theme="start"
              placeholder="搜索校内起点，如 图书馆 / 天佑斋1号楼"
              :error-text="fieldErrors.origin"
              @select="onOriginSelect"
              @clear="onOriginClear"
            />
          </view>

          <view class="scope-note">
            <text class="scope-note-text">目前支持从犀浦校区前往成都市区。</text>
          </view>

          <!-- 终点：只允许城市目的地 -->
          <view id="field-destination">
            <PlacePicker
              ref="destinationPickerRef"
              v-model="destinationText"
              class="destination-picker"
              scope="CITY"
              label="城市终点"
              tag-text="终"
              tag-theme="end"
              placeholder="搜索城市目的地，如 天府广场 / 成都东客站"
              :error-text="fieldErrors.destination"
              @select="onDestinationSelect"
              @clear="onDestinationClear"
            />
          </view>

          <!-- 出发日期时间：北京时间 +08:00 -->
          <view id="field-departure" class="field">
            <view class="field-label">
              <text class="tag tag-time">时</text>
              <text>出发日期时间</text>
              <text class="required-mark">*</text>
              <text class="tz-badge">北京时间 UTC+08:00</text>
            </view>
            <view class="time-row">
              <picker class="date-picker" mode="date" :value="departureDate || todayText" @change="onDateChange">
                <view class="picker-box" :class="{ 'picker-box-error': !!fieldErrors.departure }">
                  <text :class="departureDate ? 'picker-value' : 'picker-placeholder'">
                    {{ departureDate || '选择日期' }}
                  </text>
                </view>
              </picker>
              <picker class="time-picker" mode="time" :value="departureTime || '12:00'" start="00:00" end="23:59" @change="onTimeChange">
                <view class="picker-box" :class="{ 'picker-box-error': !!fieldErrors.departure }">
                  <text :class="departureTime ? 'picker-value' : 'picker-placeholder'">
                    {{ departureTime || '选择时间' }}
                  </text>
                </view>
              </picker>
              <view class="now-btn" @tap="fillDepartureNow">
                <text class="now-btn-text">现在</text>
              </view>
            </view>
            <text v-if="fieldErrors.departure" class="field-error">{{ fieldErrors.departure }}</text>
            <text v-else class="field-hint">
              使用北京时间；点击“现在”可快速填写。
            </text>
          </view>

          <!-- 偏好：统一枚举 -->
          <view class="field pref-field">
            <view class="field-label">
              <text class="tag tag-pref">好</text>
              <text>出行偏好</text>
            </view>
            <view class="pref-row">
              <view
                v-for="option in JOURNEY_PREFERENCES"
                :key="option.value"
                class="pref"
                :class="{ 'pref-active': preference === option.value }"
                @tap="onPreferenceChange(option.value)"
              >
                <text class="pref-label">{{ option.label }}</text>
                <text class="pref-hint">{{ option.hint }}</text>
              </view>
            </view>
          </view>

          <button class="btn-primary" :class="{ 'btn-loading': loading }" :disabled="loading" @tap="onSubmit">
            {{ loading ? '正在查询…' : '查询路线' }}
          </button>
        </view>
      </view>

      <!-- 查询结果卡：加载 / 成功 / 无方案 / 失败 -->
      <view class="card">
        <view class="card-head">
          <view class="card-title">
            <text class="card-ico">◈</text>
            <text>规划结果</text>
          </view>
          <text class="badge-soft" :class="statusBadgeClass">{{ statusBadgeText }}</text>
        </view>

        <!-- 未查询 -->
        <view v-if="journeyState.status === 'idle'" class="result-empty">
          <text class="empty-ico">🧭</text>
          <text class="empty-title">尚未开始规划</text>
          <text class="empty-text">选择校园起点、城市终点并设置出发时间后，点击“查询路线”。</text>
        </view>

        <!-- 加载中 -->
        <view v-else-if="journeyState.status === 'loading'" class="result-loading">
          <view class="loading-spinner"></view>
          <text class="loading-title">正在查询全程方案…</text>
          <text class="loading-sub">正在获取公共交通方案，请稍候。</text>
          <view v-if="submittedSummary" class="loading-summary">
            <text class="summary-line">{{ submittedSummary.originName }} → {{ submittedSummary.destinationName }}</text>
            <text class="summary-line">{{ submittedSummary.departureTime }} · {{ submittedSummary.preferenceLabel }}</text>
          </view>
        </view>

        <!-- 成功 -->
        <view v-else-if="journeyState.status === 'success'" class="result-success">
          <view class="result-banner result-banner-success">
            <text class="banner-title">已返回 {{ journeyState.plans.length }} 条方案</text>
          </view>
          <view v-if="submittedSummary" class="query-summary-box">
            <text class="summary-label">本次查询条件</text>
            <text class="summary-line">{{ submittedSummary.originName }} → {{ submittedSummary.destinationName }}</text>
            <text class="summary-line">{{ submittedSummary.departureTime }} · {{ submittedSummary.preferenceLabel }}</text>
          </view>
          <text v-if="isResultStale" class="stale-note">查询条件已修改：以下结果仍对应上次提交的条件，重新查询后更新。</text>

          <view class="response-meta">
            <text class="meta-item">来源 {{ journeyState.response?.provider || '未提供' }}</text>
            <text class="meta-item">查询于 {{ formatBeijingDateTime(journeyState.response?.queriedAt) }}</text>
          </view>

          <view v-if="responseWarnings.length > 0" class="warning-box">
            <text v-for="(warning, index) in responseWarnings" :key="index" class="warning-line">⚠ {{ warning }}</text>
          </view>

          <view class="plan-list">
            <JourneyCard
              v-for="(plan, index) in journeyState.plans"
              :key="plan.planId"
              :plan="plan"
              :index="index"
              :selected="journeyState.selectedPlanId === plan.planId"
              @select="selectPlan"
            />
          </view>

          <view class="selection-strip">
            <view class="selection-strip-main">
              <text class="selection-strip-label">当前选中</text>
              <text class="selection-strip-id">{{ selectedPlan ? '方案 ' + (selectedPlanIndex + 1) : '未选中' }}</text>
            </view>
            <text v-if="selectedPlan" class="selection-strip-hint">
              下方展示该方案的路线详情和地图。
            </text>
            <text v-else class="selection-strip-hint">点击上方方案卡切换选中项。</text>
          </view>

        </view>

        <!-- 无方案 -->
        <view v-else-if="journeyState.status === 'noRoute'" class="result-empty">
          <text class="empty-ico">🛣️</text>
          <text class="empty-title">当前条件下没有可用方案</text>
          <text class="empty-text">
            可尝试更换出发时间或目的地后重新查询。
          </text>
          <view v-if="submittedSummary" class="query-summary-box query-summary-box-flat">
            <text class="summary-line">{{ submittedSummary.originName }} → {{ submittedSummary.destinationName }}</text>
            <text class="summary-line">{{ submittedSummary.departureTime }} · {{ submittedSummary.preferenceLabel }}</text>
          </view>
          <text v-if="isResultStale" class="stale-note">查询条件已修改，重新查询后更新结果。</text>
        </view>

        <!-- 失败 -->
        <view v-else class="result-error">
          <view class="result-banner result-banner-error">
            <text class="banner-title">查询失败</text>
            <text class="banner-sub">{{ journeyState.error?.hint }}</text>
          </view>
          <view class="error-meta">
            <text v-if="journeyState.error?.requestId" class="meta-item">问题编号 {{ journeyState.error.requestId }}</text>
          </view>
          <text v-if="journeyState.error?.detail" class="error-detail">服务端信息：{{ journeyState.error?.detail }}</text>
          <text v-if="isResultStale" class="stale-note">查询条件已修改，点击重试会按当前表单条件重新查询。</text>
          <button class="retry-btn" @tap="onRetry">重试查询</button>
        </view>

        <!-- 分段明细与地图：与方案卡共用同一个 selectedPlan；失败/无方案时 plan 为 null，图层清空 -->
        <view v-if="showJourneyArtifacts" class="journey-artifacts">
          <JourneyDetail :plan="selectedPlan" />
          <JourneyMap :plan="selectedPlan" />
        </view>
      </view>

      <text class="foot-note">预计时间仅供参考，请留意实际运营情况。</text>
    </view>
  </view>
</template>

<script setup lang="ts">
/** 查询表单、异步状态和当前选中方案。条件变化时作废旧请求，避免旧响应覆盖新结果。 */

import { computed, onBeforeUnmount, reactive, ref } from 'vue';
import PlacePicker from '@/components/PlacePicker.vue';
import JourneyCard from '@/components/JourneyCard.vue';
import JourneyDetail from '@/components/JourneyDetail.vue';
import JourneyMap from '@/components/JourneyMap.vue';
import type {
  JourneyPlan,
  JourneyPreference,
  JourneyQueryState,
  JourneyRequest,
  PlaceCandidate
} from '@/types/journey';
import { JOURNEY_PREFERENCES, createIdleJourneyState, preferenceLabel } from '@/types/journey';
import {
  LatestRequestTracker,
  buildDepartureTime,
  formatBeijingDateTime,
  getBeijingNow,
  planJourney,
  toJourneyUiError
} from '@/services/api';
import {
  defaultSelectedPlanId,
  resolveSelectedPlan,
  selectExistingPlanId
} from '@/services/journey-selection';

const statusBarHeight = ref(20);
try {
  const windowInfo = uni.getWindowInfo ? uni.getWindowInfo() : uni.getSystemInfoSync();
  if (windowInfo && typeof windowInfo.statusBarHeight === 'number') {
    statusBarHeight.value = windowInfo.statusBarHeight;
  }
} catch (error) {
  console.warn('[交大智行] 读取状态栏高度失败，使用默认值 20px', error);
}

/* ------------------ 表单状态 ------------------ */

const originText = ref('');
const destinationText = ref('');
const originPlace = ref<PlaceCandidate | null>(null);
const destinationPlace = ref<PlaceCandidate | null>(null);
const departureDate = ref('');
const departureTime = ref('');
const preference = ref<JourneyPreference>('RECOMMENDED');

const originPickerRef = ref<InstanceType<typeof PlacePicker> | null>(null);
const destinationPickerRef = ref<InstanceType<typeof PlacePicker> | null>(null);

const fieldErrors = reactive({
  origin: '',
  destination: '',
  departure: ''
});

const todayText = computed(() => getBeijingNow().date);

/* ------------------ 统一结果状态 ------------------ */

const journeyState = reactive<JourneyQueryState>(createIdleJourneyState());
const requestTracker = new LatestRequestTracker();

interface SubmittedSummary {
  originName: string;
  destinationName: string;
  departureTime: string;
  preferenceLabel: string;
}

const submittedSummary = ref<SubmittedSummary | null>(null);

const loading = computed(() => journeyState.status === 'loading');
const responseWarnings = computed(() => (journeyState.response && journeyState.response.warnings) || []);
const selectedPlan = computed<JourneyPlan | null>(() =>
  resolveSelectedPlan(journeyState.plans, journeyState.selectedPlanId)
);

const selectedPlanIndex = computed(() =>
  journeyState.plans.findIndex((plan) => plan.planId === journeyState.selectedPlanId)
);

/**
 * 结果区是否展示分段明细与地图：
 * 成功、无方案、失败三种已查询状态都展示，且都读取同一个 selectedPlan；
 * 失败/无方案时 selectedPlan 为 null，组件渲染清空状态并清除上一次图层。
 */
const showJourneyArtifacts = computed(
  () =>
    journeyState.status === 'success' ||
    journeyState.status === 'noRoute' ||
    journeyState.status === 'error'
);

const STATUS_BADGES: Record<JourneyQueryState['status'], { text: string; className: string }> = {
  idle: { text: '待查询', className: 'badge-idle' },
  loading: { text: '查询中', className: 'badge-loading' },
  success: { text: '成功', className: 'badge-success' },
  noRoute: { text: '无方案', className: 'badge-warn' },
  error: { text: '失败', className: 'badge-error' }
};

const statusBadgeText = computed(() => STATUS_BADGES[journeyState.status].text);
const statusBadgeClass = computed(() => STATUS_BADGES[journeyState.status].className);

/** 当前表单能生成的查询键；用于提示“结果对应旧条件”。 */
const currentFormKey = computed(() => {
  const departureIso = buildDepartureTime(departureDate.value, departureTime.value);
  if (!originPlace.value || !destinationPlace.value || !departureIso) return '';
  return `${originPlace.value.id}|${destinationPlace.value.id}|${departureIso}|${preference.value}`;
});

const lastRequestKey = computed(() => {
  const request = journeyState.lastRequest;
  if (!request) return '';
  return `${request.originId}|${request.destinationId}|${request.departureTime}|${request.preference}`;
});

const isResultStale = computed(
  () =>
    (journeyState.status === 'success' || journeyState.status === 'noRoute' || journeyState.status === 'error') &&
    lastRequestKey.value.length > 0 &&
    currentFormKey.value !== lastRequestKey.value
);

/* ------------------ 地点选择事件 ------------------ */

function onOriginSelect(place: PlaceCandidate): void {
  originPlace.value = place;
  fieldErrors.origin = '';
  handleConditionsChanged();
}

function onOriginClear(): void {
  originPlace.value = null;
  handleConditionsChanged();
}

function onDestinationSelect(place: PlaceCandidate): void {
  destinationPlace.value = place;
  fieldErrors.destination = '';
  handleConditionsChanged();
}

function onDestinationClear(): void {
  destinationPlace.value = null;
  handleConditionsChanged();
}

/* ------------------ 日期时间 / 偏好 ------------------ */

interface PickerChangeEvent {
  detail?: { value?: string };
}

function onDateChange(event: PickerChangeEvent): void {
  const value = event && event.detail && typeof event.detail.value === 'string' ? event.detail.value : '';
  departureDate.value = value;
  fieldErrors.departure = '';
  handleConditionsChanged();
}

function onTimeChange(event: PickerChangeEvent): void {
  const value = event && event.detail && typeof event.detail.value === 'string' ? event.detail.value : '';
  departureTime.value = value;
  fieldErrors.departure = '';
  handleConditionsChanged();
}

function fillDepartureNow(): void {
  const now = getBeijingNow();
  departureDate.value = now.date;
  departureTime.value = now.time;
  fieldErrors.departure = '';
  handleConditionsChanged();
}

function onPreferenceChange(value: JourneyPreference): void {
  if (preference.value === value) return;
  preference.value = value;
  handleConditionsChanged();
}

/**
 * 条件修改：作废在途请求，避免旧响应晚到后覆盖当前状态。
 * 已展示的结果不立即清空，仅通过 isResultStale 提示“结果对应上次条件”。
 */
function handleConditionsChanged(): void {
  requestTracker.invalidate();
  if (journeyState.status === 'loading') {
    journeyState.status = 'idle';
    submittedSummary.value = null;
  }
}

/* ------------------ 校验与提交 ------------------ */

function validateForm(): JourneyRequest | null {
  fieldErrors.origin = '';
  fieldErrors.destination = '';
  fieldErrors.departure = '';

  let firstInvalid: 'origin' | 'destination' | 'departure' | null = null;

  if (!originPlace.value) {
    fieldErrors.origin = '请选择校园起点：输入关键词后从候选中确认一个可用地点。';
    firstInvalid = 'origin';
  } else if (!originPlace.value.routingReady) {
    fieldErrors.origin = '所选起点尚未通过可用性核实，不能提交，请重新选择。';
    firstInvalid = 'origin';
  }

  if (!destinationPlace.value) {
    fieldErrors.destination = '请选择城市终点：输入关键词后从候选中确认一个可用地点。';
    if (!firstInvalid) firstInvalid = 'destination';
  } else if (!destinationPlace.value.routingReady) {
    fieldErrors.destination = '所选终点尚未通过可用性核实，不能提交，请重新选择。';
    if (!firstInvalid) firstInvalid = 'destination';
  }

  const departureIso = buildDepartureTime(departureDate.value, departureTime.value);
  if (!departureIso) {
    fieldErrors.departure = '请选择合法的出发日期和时间（北京时间）。';
    if (!firstInvalid) firstInvalid = 'departure';
  }

  if (firstInvalid) {
    if (firstInvalid === 'origin') originPickerRef.value?.focus();
    if (firstInvalid === 'destination') destinationPickerRef.value?.focus();
    if (firstInvalid === 'departure' && typeof uni.pageScrollTo === 'function') {
      uni.pageScrollTo({ selector: '#field-departure', duration: 200 });
    }
    uni.showToast({ title: '请先补全查询条件', icon: 'none' });
    return null;
  }

  return {
    originId: originPlace.value!.id,
    destinationId: destinationPlace.value!.id,
    departureTime: departureIso!,
    preference: preference.value
  };
}

function onSubmit(): void {
  if (loading.value) {
    return;
  }
  const request = validateForm();
  if (!request) return;
  void executeQuery(request);
}

async function executeQuery(request: JourneyRequest): Promise<void> {
  const token = requestTracker.begin();
  const submittedAt = new Date().toISOString();
  const originName = originPlace.value ? originPlace.value.name : request.originId;
  const destinationName = destinationPlace.value ? destinationPlace.value.name : request.destinationId;
  submittedSummary.value = {
    originName,
    destinationName,
    departureTime: request.departureTime,
    preferenceLabel: preferenceLabel(request.preference)
  };

  journeyState.requestToken = token;
  journeyState.responseRequestId = null;
  journeyState.status = 'loading';
  journeyState.lastRequest = request;
  journeyState.submittedAt = submittedAt;
  journeyState.response = null;
  journeyState.plans = [];
  journeyState.selectedPlanId = null;
  journeyState.error = null;

  try {
    const response = await planJourney(request);
    if (!requestTracker.isCurrent(token)) {
      return;
    }
    applyResponse(response);
  } catch (error) {
    if (!requestTracker.isCurrent(token)) {
      return;
    }
    applyFailure(token, error);
  }
}

function applyResponse(response: Awaited<ReturnType<typeof planJourney>>): void {
  journeyState.response = response;
  journeyState.responseRequestId = response.requestId;

  if (response.status === 'NO_ROUTE' || response.plans.length === 0) {
    journeyState.status = 'noRoute';
    journeyState.plans = [];
    journeyState.selectedPlanId = null;
    return;
  }

  journeyState.status = 'success';
  journeyState.plans = response.plans;
  journeyState.selectedPlanId = defaultSelectedPlanId(response.plans);
}

function applyFailure(token: number, error: unknown): void {
  const uiError = toJourneyUiError(error);
  journeyState.status = 'error';
  journeyState.error = uiError;
  journeyState.response = null;
  journeyState.responseRequestId = uiError.requestId;
  journeyState.plans = [];
  journeyState.selectedPlanId = null;
  console.warn('[交大智行] 查询失败', { token, error: uiError });
}

function onRetry(): void {
  if (loading.value) return;
  const request = validateForm();
  if (!request) return;
  void executeQuery(request);
}

/* ------------------ 方案选中 ------------------ */

/**
 * 卡片点击回调：只接受属于当前候选的 planId，选中项写回 journeyState；
 * selectedPlan 由 computed 按 ID 重新计算，卡片、详情与地图输入始终同源。
 * 切换选中不重新发起查询。
 */
function selectPlan(planId: string): void {
  const nextPlanId = selectExistingPlanId(journeyState.plans, planId);
  if (!nextPlanId) {
    console.warn('[交大智行] 忽略不属于当前候选的选中事件', { planId });
    return;
  }
  if (journeyState.selectedPlanId === nextPlanId) return;
  journeyState.selectedPlanId = nextPlanId;
}

onBeforeUnmount(() => {
  requestTracker.invalidate();
});

</script>

<style>
.page {
  min-height: 100vh;
  background: #f5f7fa;
  padding-bottom: calc(20rpx + env(safe-area-inset-bottom));
  box-sizing: border-box;
}

/* ---------- 顶部品牌区 ---------- */
.hero {
  position: relative;
  padding: 24rpx 36rpx 104rpx;
  background: linear-gradient(102deg, #0b2545 0%, #123a63 52%, #164a7d 100%);
  color: #ffffff;
  box-sizing: border-box;
}

.brand-row {
  display: flex;
  align-items: center;
  gap: 22rpx;
}

.brand-mark {
  width: 76rpx;
  height: 76rpx;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 22rpx;
  background: rgba(255, 255, 255, 0.1);
  border: 1rpx solid rgba(255, 255, 255, 0.16);
}

.brand-mark-text {
  color: #9fd0ff;
  font-size: 36rpx;
  font-weight: 700;
  line-height: 1;
  letter-spacing: 2rpx;
}

.brand-text {
  display: flex;
  flex-direction: column;
}

.brand-title {
  font-size: 40rpx;
  font-weight: 700;
  letter-spacing: 3rpx;
  line-height: 1.25;
}

.brand-sub {
  margin-top: 4rpx;
  font-size: 22rpx;
  color: rgba(255, 255, 255, 0.58);
  letter-spacing: 1rpx;
  line-height: 1.4;
}

.hero-meta {
  margin-top: 34rpx;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16rpx;
}

.hero-campus {
  font-size: 24rpx;
  color: rgba(255, 255, 255, 0.72);
  line-height: 1.4;
}

.status-pill {
  display: flex;
  align-items: center;
  gap: 12rpx;
  padding: 8rpx 20rpx;
  border-radius: 40rpx;
  background: rgba(255, 255, 255, 0.08);
  border: 1rpx solid rgba(255, 255, 255, 0.12);
  flex-shrink: 0;
}

.status-dot {
  width: 12rpx;
  height: 12rpx;
  border-radius: 50%;
  background: #34d399;
  box-shadow: 0 0 0 5rpx rgba(52, 211, 153, 0.18);
}

.status-text {
  font-size: 22rpx;
  color: rgba(255, 255, 255, 0.82);
  line-height: 1.3;
}

/* ---------- 内容区 ---------- */
.content {
  position: relative;
  z-index: 2;
  margin-top: -76rpx;
  padding: 0 28rpx;
}

.notice {
  display: flex;
  align-items: flex-start;
  gap: 12rpx;
  padding: 20rpx 24rpx;
  border-radius: 18rpx;
  background: #fdf2e0;
  border: 1rpx solid #f3dcb8;
  margin-bottom: 24rpx;
}

.notice-ico {
  color: #e08a1e;
  font-size: 24rpx;
  line-height: 1.4;
  flex-shrink: 0;
}

.notice-text {
  flex: 1;
  font-size: 22rpx;
  color: #7c4a12;
  line-height: 1.6;
}

.card {
  background: #ffffff;
  border-radius: 24rpx;
  box-shadow: 0 2rpx 6rpx rgba(16, 32, 56, 0.06), 0 10rpx 32rpx rgba(16, 32, 56, 0.07);
  margin-bottom: 24rpx;
  overflow: hidden;
}

.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 26rpx 32rpx 18rpx;
  border-bottom: 1rpx solid #eef2f7;
}

.card-title {
  display: flex;
  align-items: center;
  gap: 10rpx;
  font-size: 30rpx;
  font-weight: 650;
  color: #131a24;
}

.card-ico {
  color: #1d6fbf;
  font-size: 26rpx;
}


.badge-idle {
  color: #5b6a7d;
  background: #eef2f7;
}

.badge-loading {
  color: #17518c;
  background: #e6f1fb;
}

.badge-success {
  color: #157347;
  background: #e2f5ea;
}

.badge-warn {
  color: #b45309;
  background: #fdf2e0;
}

.badge-error {
  color: #c0392b;
  background: #fdeaea;
}

.card-body {
  padding: 26rpx 32rpx 32rpx;
}

/* ---------- 起终点与通用字段 ---------- */
.scope-note {
  display: flex;
  padding: 10rpx 0 22rpx;
}

.scope-note-text {
  flex: 1;
  font-size: 21rpx;
  color: #7c8a9c;
  line-height: 1.6;
}

.field {
  margin-top: 26rpx;
}

.field-label {
  display: flex;
  align-items: center;
  gap: 10rpx;
  font-size: 24rpx;
  font-weight: 550;
  color: #5b6a7d;
  margin-bottom: 12rpx;
}

.tag {
  width: 34rpx;
  height: 34rpx;
  border-radius: 10rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #ffffff;
  font-size: 21rpx;
  font-weight: 700;
  line-height: 1;
  flex-shrink: 0;
}

.tag-time {
  background: #1d6fbf;
}

.tag-pref {
  background: #7c5cd6;
}

.required-mark {
  color: #d64545;
  font-size: 24rpx;
  line-height: 1;
}

.tz-badge {
  margin-left: auto;
  font-size: 18rpx;
  color: #7c8a9c;
  background: #f2f5f9;
  border-radius: 40rpx;
  padding: 2rpx 12rpx;
  line-height: 1.5;
}

.time-row {
  display: flex;
  align-items: center;
  gap: 10rpx;
}

.date-picker {
  flex: 1.35;
}

.time-picker {
  flex: 1;
}

.picker-box {
  height: 82rpx;
  padding: 0 20rpx;
  box-sizing: border-box;
  border: 2rpx solid #e2e8f0;
  border-radius: 16rpx;
  background: #ffffff;
  display: flex;
  align-items: center;
}

.picker-box-error {
  border-color: #e2a2a2;
  background: #fffafa;
}

.picker-value {
  font-size: 26rpx;
  color: #131a24;
  line-height: 1.4;
}

.picker-placeholder {
  font-size: 24rpx;
  color: #a8b4c2;
  line-height: 1.4;
}

.now-btn {
  flex-shrink: 0;
  height: 82rpx;
  padding: 0 20rpx;
  box-sizing: border-box;
  border-radius: 16rpx;
  background: #eef5fc;
  border: 1rpx solid #d8e8f8;
  display: flex;
  align-items: center;
  justify-content: center;
}

.now-btn-text {
  font-size: 23rpx;
  color: #17518c;
  font-weight: 550;
  line-height: 1.3;
}

.field-error {
  display: block;
  margin-top: 10rpx;
  font-size: 22rpx;
  color: #c0392b;
  line-height: 1.5;
}

.field-hint {
  display: block;
  margin-top: 10rpx;
  font-size: 21rpx;
  color: #7c8a9c;
  line-height: 1.55;
}

/* ---------- 偏好 ---------- */
.pref-field {
  margin-top: 28rpx;
}

.pref-row {
  display: flex;
  gap: 10rpx;
}

.pref {
  flex: 1;
  padding: 16rpx 12rpx;
  border-radius: 16rpx;
  border: 2rpx solid #e8edf4;
  background: #fbfcfe;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6rpx;
}

.pref-active {
  border-color: #9cbce0;
  background: #eef5fc;
}

.pref-label {
  font-size: 24rpx;
  font-weight: 600;
  color: #33404f;
  line-height: 1.4;
}

.pref-active .pref-label {
  color: #17518c;
}

.pref-hint {
  font-size: 18rpx;
  color: #7c8a9c;
  line-height: 1.4;
  text-align: center;
}

/* ---------- 查询按钮 ---------- */
.btn-primary {
  width: 100%;
  height: 92rpx;
  line-height: 92rpx;
  margin-top: 30rpx;
  padding: 0;
  border: none;
  border-radius: 18rpx;
  background: linear-gradient(180deg, #2b8ce0, #1d6fbf);
  color: #ffffff;
  font-size: 30rpx;
  font-weight: 600;
  text-align: center;
  box-shadow: 0 4rpx 12rpx rgba(29, 111, 191, 0.28), 0 10rpx 28rpx rgba(29, 111, 191, 0.22);
}

.btn-primary::after {
  border: none;
}

.btn-primary.btn-loading {
  background: linear-gradient(180deg, #8fb6da, #6f9bc4);
  box-shadow: 0 4rpx 12rpx rgba(29, 111, 191, 0.18);
  color: #f2f7fc;
}

/* ---------- 结果区 ---------- */
.result-empty {
  padding: 50rpx 32rpx 54rpx;
  display: flex;
  flex-direction: column;
  align-items: center;
}

.empty-ico {
  font-size: 62rpx;
  line-height: 1.2;
  opacity: 0.55;
}

.empty-title {
  margin-top: 16rpx;
  font-size: 28rpx;
  font-weight: 600;
  color: #33404f;
  line-height: 1.4;
}

.empty-text {
  margin-top: 10rpx;
  font-size: 23rpx;
  color: #7c8a9c;
  line-height: 1.8;
  text-align: center;
}


.result-loading {
  padding: 52rpx 34rpx 54rpx;
  display: flex;
  flex-direction: column;
  align-items: center;
}

.loading-spinner {
  width: 54rpx;
  height: 54rpx;
  border: 5rpx solid #dce9f6;
  border-top-color: #1d6fbf;
  border-radius: 50%;
  animation: jz-rotate 0.85s linear infinite;
}

@keyframes jz-rotate {
  from {
    transform: rotate(0deg);
  }
  to {
    transform: rotate(360deg);
  }
}

.loading-title {
  margin-top: 22rpx;
  font-size: 28rpx;
  font-weight: 600;
  color: #17518c;
  line-height: 1.4;
}

.loading-sub {
  margin-top: 10rpx;
  font-size: 21rpx;
  color: #7c8a9c;
  line-height: 1.6;
  text-align: center;
}

.loading-summary {
  margin-top: 20rpx;
  padding: 16rpx 24rpx;
  border-radius: 14rpx;
  background: #f5f9fd;
  display: flex;
  flex-direction: column;
  gap: 4rpx;
  width: 100%;
  box-sizing: border-box;
}

.result-success {
  padding: 24rpx 28rpx 28rpx;
}

.result-banner {
  padding: 18rpx 22rpx;
  border-radius: 16rpx;
  display: flex;
  flex-direction: column;
  gap: 6rpx;
}

.result-banner-success {
  background: #eaf8f0;
  border: 1rpx solid #c8ead7;
}

.result-banner-error {
  background: #fdeeee;
  border: 1rpx solid #f3cccc;
}

.banner-title {
  font-size: 27rpx;
  font-weight: 650;
  color: #157347;
  line-height: 1.4;
}

.result-banner-error .banner-title {
  color: #b3261e;
}

.banner-sub {
  font-size: 21rpx;
  color: #5b6a7d;
  line-height: 1.55;
  word-break: break-all;
}

.query-summary-box {
  margin-top: 16rpx;
  padding: 16rpx 20rpx;
  border-radius: 14rpx;
  background: #f7f9fc;
  border: 1rpx solid #edf1f6;
  display: flex;
  flex-direction: column;
  gap: 4rpx;
}

.query-summary-box-flat {
  width: 100%;
  box-sizing: border-box;
  margin-top: 22rpx;
}

.summary-label {
  font-size: 19rpx;
  color: #9aa8b8;
  line-height: 1.5;
}

.summary-line {
  font-size: 22rpx;
  color: #33404f;
  line-height: 1.55;
  word-break: break-all;
}

.stale-note {
  display: block;
  margin-top: 16rpx;
  padding: 12rpx 18rpx;
  border-radius: 12rpx;
  background: #fffaf0;
  border: 1rpx solid #f6e7c8;
  font-size: 21rpx;
  color: #9a5b00;
  line-height: 1.6;
}

.response-meta {
  margin-top: 18rpx;
  display: flex;
  flex-direction: column;
  gap: 4rpx;
}

.meta-item {
  font-size: 20rpx;
  color: #7c8a9c;
  line-height: 1.55;
  word-break: break-all;
}

.warning-box {
  margin-top: 14rpx;
  padding: 12rpx 18rpx;
  border-radius: 12rpx;
  background: #fdf2e0;
  border: 1rpx solid #f3dcb8;
}

.warning-line {
  display: block;
  font-size: 21rpx;
  color: #7c4a12;
  line-height: 1.55;
}

/* ---------- 候选方案卡与选中条 ---------- */
.plan-list {
  display: flex;
  flex-direction: column;
}

.selection-strip {
  margin-top: 20rpx;
  padding: 16rpx 20rpx;
  border-radius: 14rpx;
  background: #f2f7fd;
  border: 1rpx solid #dbe8f7;
  display: flex;
  flex-direction: column;
  gap: 6rpx;
}

.selection-strip-main {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10rpx;
}

.selection-strip-label {
  font-size: 19rpx;
  color: #5b6b7d;
  line-height: 1.5;
}

.selection-strip-id {
  font-size: 21rpx;
  font-weight: 600;
  color: #17518c;
  line-height: 1.5;
  word-break: break-all;
}

.selection-strip-hint {
  font-size: 19rpx;
  color: #7c8a9c;
  line-height: 1.55;
}

/* ---------- 分段明细与地图 ---------- */
.journey-artifacts {
  margin-top: 6rpx;
}

/* ---------- 失败 ---------- */
.result-error {
  padding: 24rpx 28rpx 30rpx;
}

.error-meta {
  margin-top: 16rpx;
  display: flex;
  flex-direction: column;
  gap: 4rpx;
}

.error-detail {
  display: block;
  margin-top: 12rpx;
  font-size: 21rpx;
  color: #7c4a12;
  line-height: 1.6;
  word-break: break-all;
}

.retry-btn {
  width: 100%;
  height: 84rpx;
  line-height: 84rpx;
  margin-top: 20rpx;
  padding: 0;
  border: none;
  border-radius: 16rpx;
  background: linear-gradient(180deg, #2b8ce0, #1d6fbf);
  color: #ffffff;
  font-size: 27rpx;
  font-weight: 600;
  text-align: center;
}

.retry-btn::after {
  border: none;
}

/* ---------- 页脚 ---------- */
.foot-note {
  display: block;
  padding: 6rpx 20rpx;
  text-align: center;
  font-size: 19rpx;
  color: #a8b4c2;
  line-height: 1.7;
  word-break: break-all;
}

.assistant-entry {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16rpx;
  margin-bottom: 24rpx;
  padding: 24rpx;
  border-radius: 20rpx;
  background: #e9f3fb;
  color: #163c59;
}
.assistant-entry-pressed { background: #dcecf8; }
.assistant-entry-title,
.assistant-entry-example { display: block; }
.assistant-entry-title { font-size: 30rpx; font-weight: 650; }
.assistant-entry-example { margin-top: 6rpx; font-size: 24rpx; }

</style>
