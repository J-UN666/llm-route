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
          <text class="status-text">结构化行程服务</text>
        </view>
      </view>
    </view>

    <view class="content">
      <view class="notice">
        <text class="notice-ico">ⓘ</text>
        <text class="notice-text">
          当前接入：地点搜索走 W1-04 Java 接口；行程查询按 W1-08 约定提交 POST /api/journeys，页面覆盖加载、成功、无方案与失败四种状态，成功结果以候选方案卡展示（最多三条，点击卡片切换当前方案）。
        </text>
      </view>

      <!-- 规划输入卡 -->
      <view class="card">
        <view class="card-head">
          <view class="card-title">
            <text class="card-ico">◎</text>
            <text>规划路线</text>
          </view>
          <text class="badge-soft">W1-07</text>
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
            <text class="scope-note-text">起点限定为校园内部地点；终点限定为成都市区目的地，不支持反向查询。</text>
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
              提交时生成带 +08:00 的 ISO 8601；“现在”填入北京时间下一分钟。
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
            <text class="field-hint">提交值：{{ preference }}（统一枚举，排序规则由 W1-09 后端实现）。</text>
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
          <text class="empty-note">查询成功后这里将展示最多三条实际候选方案卡，可点击卡片切换当前方案。</text>
        </view>

        <!-- 加载中 -->
        <view v-else-if="journeyState.status === 'loading'" class="result-loading">
          <view class="loading-spinner"></view>
          <text class="loading-title">正在查询全程方案…</text>
          <text class="loading-sub">请求 #{{ journeyState.requestToken }} 已在途，loading 期间按钮已禁用，重复点击不会重复提交。</text>
          <view v-if="submittedSummary" class="loading-summary">
            <text class="summary-line">{{ submittedSummary.originName }} → {{ submittedSummary.destinationName }}</text>
            <text class="summary-line">{{ submittedSummary.departureTime }} · {{ submittedSummary.preferenceLabel }}</text>
          </view>
        </view>

        <!-- 成功 -->
        <view v-else-if="journeyState.status === 'success'" class="result-success">
          <view class="result-banner result-banner-success">
            <text class="banner-title">已返回 {{ journeyState.plans.length }} 条方案</text>
            <text class="banner-sub">requestId {{ journeyState.responseRequestId || '未提供' }}</text>
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
            <text class="meta-item">地点数据版本 {{ journeyState.response?.campusDataVersion || '未提供' }}</text>
            <text class="meta-item">后端状态 {{ journeyState.response?.status || '未提供' }}</text>
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
              <text class="selection-strip-id">{{ selectedPlan ? selectedPlan.planId : '未选中' }}</text>
            </view>
            <text v-if="selectedPlan" class="selection-strip-hint">
              方案 {{ selectedPlanIndex + 1 }} · 卡片数字来自该 plan；分段明细与地图由 W1-11 读取同一个 selectedPlan。
            </text>
            <text v-else class="selection-strip-hint">点击上方方案卡切换选中项。</text>
          </view>

          <text class="handoff-note">
            W1-10 交接：本页维护 plans 与 selectedPlanId，selectedPlan 按 ID 从当前 plans 计算，成功默认第一条；点击卡片发出的 select(planId) 只更新选中项，不触发重查。
          </text>
        </view>

        <!-- 无方案 -->
        <view v-else-if="journeyState.status === 'noRoute'" class="result-empty">
          <text class="empty-ico">🛣️</text>
          <text class="empty-title">当前条件下没有可用方案</text>
          <text class="empty-text">
            后端返回 NO_ROUTE（requestId {{ journeyState.responseRequestId || '未提供' }}）。可尝试更换出发时间或终点后重新查询。
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
            <text class="meta-item">code {{ journeyState.error?.code || 'UNKNOWN_ERROR' }}</text>
            <text class="meta-item">HTTP {{ journeyState.error?.httpStatus ?? '未提供' }}</text>
            <text class="meta-item">requestId {{ journeyState.error?.requestId || '未提供' }}</text>
            <text class="meta-item">{{ journeyState.error?.retryable ? '标记为可重试' : '该错误通常需要调整条件后重试' }}</text>
          </view>
          <text v-if="journeyState.error?.detail" class="error-detail">服务端信息：{{ journeyState.error?.detail }}</text>
          <text v-if="isResultStale" class="stale-note">查询条件已修改，点击重试会按当前表单条件重新查询。</text>
          <button class="retry-btn" @tap="onRetry">重试查询</button>
          <text class="handoff-note">失败后按钮已恢复；错误码映射在 services/api.ts，未知 code 回退服务端 message。</text>
        </view>
      </view>

      <text class="foot-note">交大智行 · W1-10 全程方案卡 · 后端监听地址按测试环境配置</text>
      <text class="foot-note">API 基址：{{ API_BASE_URL }}（{{ apiBaseUrlSourceLabel }}）</text>
      <text class="foot-note foot-note-gap">交大智行 · 地点数据版本以接口返回为准</text>
    </view>
  </view>
</template>

<script setup lang="ts">
/**
 * 交大智行首页：普通行程查询表单（W1-07）+ 候选方案卡（W1-10）。
 *
 * 结果状态（见 `@/types/journey` 的 JourneyQueryState）：
 * - journeyState.status：idle/loading/success/noRoute/error；
 * - journeyState.plans：成功时后端已排序、去重、最多三条的 JourneyPlan[]；
 * - journeyState.selectedPlanId：成功后默认 plans[0].planId，点击卡片时写回；
 * - journeyState.requestToken / responseRequestId：客户端请求标识 / 后端 requestId；
 * - journeyState.error：失败时本地化错误（code/hint/detail/retryable/requestId）。
 *
 * 方案选中：selectedPlan 始终按 selectedPlanId 从当前 plans 计算（见
 * `@/services/journey-selection`），不缓存方案对象；失败/无方案时 plans 与
 * selectedPlanId 一起清空，W1-11 读取的 selectedPlan 自然变为 null。
 *
 * 过期响应处理：每个请求用 LatestRequestTracker 取令牌；条件修改时 invalidate()，
 * 响应到达后再用 isCurrent() 校验，过期响应直接丢弃，不覆盖当前显示。
 */

import { computed, onBeforeUnmount, reactive, ref } from 'vue';
import { onLoad } from '@dcloudio/uni-app';
import PlacePicker from '@/components/PlacePicker.vue';
import JourneyCard from '@/components/JourneyCard.vue';
import type {
  JourneyPlan,
  JourneyPreference,
  JourneyQueryState,
  JourneyRequest,
  PlaceCandidate
} from '@/types/journey';
import { JOURNEY_PREFERENCES, createIdleJourneyState, preferenceLabel } from '@/types/journey';
import {
  API_BASE_URL,
  API_BASE_URL_SOURCE,
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

/* ------------------ 统一结果状态（W1-10 交接点） ------------------ */

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

const STATUS_BADGES: Record<JourneyQueryState['status'], { text: string; className: string }> = {
  idle: { text: '待查询', className: 'badge-idle' },
  loading: { text: '查询中', className: 'badge-loading' },
  success: { text: '成功', className: 'badge-success' },
  noRoute: { text: '无方案', className: 'badge-warn' },
  error: { text: '失败', className: 'badge-error' }
};

const statusBadgeText = computed(() => STATUS_BADGES[journeyState.status].text);
const statusBadgeClass = computed(() => STATUS_BADGES[journeyState.status].className);

const apiBaseUrlSourceLabel = computed(() =>
  API_BASE_URL_SOURCE === 'env' ? '来自环境变量 VITE_API_BASE_URL' : '未配置环境变量，使用默认地址'
);

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
  handleConditionsChanged('origin');
  console.info('[W1-07] 已选起点', { id: place.id, name: place.name });
}

function onOriginClear(): void {
  originPlace.value = null;
  handleConditionsChanged('origin');
}

function onDestinationSelect(place: PlaceCandidate): void {
  destinationPlace.value = place;
  fieldErrors.destination = '';
  handleConditionsChanged('destination');
  console.info('[W1-07] 已选终点', { id: place.id, name: place.name });
}

function onDestinationClear(): void {
  destinationPlace.value = null;
  handleConditionsChanged('destination');
}

/* ------------------ 日期时间 / 偏好 ------------------ */

interface PickerChangeEvent {
  detail?: { value?: string };
}

function onDateChange(event: PickerChangeEvent): void {
  const value = event && event.detail && typeof event.detail.value === 'string' ? event.detail.value : '';
  departureDate.value = value;
  fieldErrors.departure = '';
  handleConditionsChanged('departureDate');
  console.info('[W1-07] 已选出发日期', value);
}

function onTimeChange(event: PickerChangeEvent): void {
  const value = event && event.detail && typeof event.detail.value === 'string' ? event.detail.value : '';
  departureTime.value = value;
  fieldErrors.departure = '';
  handleConditionsChanged('departureTime');
  console.info('[W1-07] 已选出发时间', value);
}

function fillDepartureNow(): void {
  const now = getBeijingNow();
  departureDate.value = now.date;
  departureTime.value = now.time;
  fieldErrors.departure = '';
  handleConditionsChanged('fillNow');
  console.info('[W1-07] 已填入北京时间下一分钟', now.iso);
}

function onPreferenceChange(value: JourneyPreference): void {
  if (preference.value === value) return;
  preference.value = value;
  handleConditionsChanged('preference');
  console.info('[W1-07] 已切换偏好', { preference: value, label: preferenceLabel(value) });
}

/**
 * 条件修改：作废在途请求，避免旧响应晚到后覆盖当前状态。
 * 已展示的结果不立即清空，仅通过 isResultStale 提示“结果对应上次条件”。
 */
function handleConditionsChanged(source: string): void {
  const newToken = requestTracker.invalidate();
  if (journeyState.status === 'loading') {
    console.info('[W1-07] 条件已修改，作废在途请求', {
      cancelledToken: journeyState.requestToken,
      newToken,
      source
    });
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
    console.info('[W1-07] 表单校验未通过，未发起请求', { firstInvalid, fieldErrors });
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
    console.info('[W1-07] 查询进行中，忽略重复点击', { requestToken: journeyState.requestToken });
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

  console.info('[W1-07] 发起行程查询', { token, request, submittedAt });

  try {
    const response = await planJourney(request);
    if (!requestTracker.isCurrent(token)) {
      console.info('[W1-07] 丢弃过期响应', {
        token,
        latest: requestTracker.current,
        requestId: response.requestId,
        status: response.status
      });
      return;
    }
    applyResponse(token, response);
  } catch (error) {
    if (!requestTracker.isCurrent(token)) {
      console.info('[W1-07] 丢弃过期错误', { token, latest: requestTracker.current });
      return;
    }
    applyFailure(token, error);
  }
}

function applyResponse(token: number, response: Awaited<ReturnType<typeof planJourney>>): void {
  journeyState.response = response;
  journeyState.responseRequestId = response.requestId;

  if (response.status === 'NO_ROUTE' || response.plans.length === 0) {
    journeyState.status = 'noRoute';
    journeyState.plans = [];
    journeyState.selectedPlanId = null;
    console.info('[W1-07] 查询完成：无方案', { token, requestId: response.requestId });
    return;
  }

  journeyState.status = 'success';
  journeyState.plans = response.plans;
  journeyState.selectedPlanId = defaultSelectedPlanId(response.plans);
  console.info('[W1-10] 查询成功，默认选中第一条方案', {
    token,
    requestId: response.requestId,
    planCount: response.plans.length,
    selectedPlanId: journeyState.selectedPlanId,
    provider: response.provider
  });
}

function applyFailure(token: number, error: unknown): void {
  const uiError = toJourneyUiError(error);
  journeyState.status = 'error';
  journeyState.error = uiError;
  journeyState.response = null;
  journeyState.responseRequestId = uiError.requestId;
  journeyState.plans = [];
  journeyState.selectedPlanId = null;
  console.warn('[W1-07] 查询失败', { token, error: uiError });
}

function onRetry(): void {
  if (loading.value) return;
  const request = validateForm();
  if (!request) return;
  void executeQuery(request);
}

/* ------------------ 方案选中（W1-10） ------------------ */

/**
 * 卡片点击回调：只接受属于当前候选的 planId，选中项写回 journeyState；
 * selectedPlan 由 computed 按 ID 重新计算，卡片、详情与地图输入始终同源。
 * 切换选中不重新发起查询。
 */
function selectPlan(planId: string): void {
  const nextPlanId = selectExistingPlanId(journeyState.plans, planId);
  if (!nextPlanId) {
    console.warn('[W1-10] 忽略不属于当前候选的选中事件', { planId });
    return;
  }
  if (journeyState.selectedPlanId === nextPlanId) return;
  journeyState.selectedPlanId = nextPlanId;
  console.info('[W1-10] 已切换当前方案', {
    planId: nextPlanId,
    index: journeyState.plans.findIndex((plan) => plan.planId === nextPlanId),
    planCount: journeyState.plans.length
  });
}

onBeforeUnmount(() => {
  requestTracker.invalidate();
});

onLoad(() => {
  console.info('[交大智行] 首页加载完成（W1-10 方案卡）', {
    apiBaseUrl: API_BASE_URL,
    apiBaseUrlSource: API_BASE_URL_SOURCE,
    preferences: JOURNEY_PREFERENCES.map((item) => item.value)
  });
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

.badge-soft {
  font-size: 20rpx;
  font-weight: 600;
  color: #17518c;
  background: #e6f1fb;
  padding: 4rpx 16rpx;
  border-radius: 40rpx;
  line-height: 1.4;
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

.empty-note {
  margin-top: 12rpx;
  font-size: 21rpx;
  color: #a8b4c2;
  line-height: 1.6;
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

/* ---------- 候选方案卡与选中条（W1-10） ---------- */
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

.handoff-note {
  display: block;
  margin-top: 20rpx;
  font-size: 20rpx;
  color: #9aa8b8;
  line-height: 1.65;
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

.foot-note-gap {
  padding-bottom: 24rpx;
}
</style>
