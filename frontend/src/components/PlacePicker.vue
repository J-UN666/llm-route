<template>
  <view class="place-picker" :class="{ 'place-picker-error': !!errorText }">
    <!-- 字段标题 -->
    <view class="pp-label">
      <text v-if="tagText" class="pp-tag" :class="tagTheme === 'end' ? 'pp-tag-end' : 'pp-tag-start'">
        {{ tagText }}
      </text>
      <text class="pp-label-text">{{ label }}</text>
      <text v-if="required" class="pp-required">*</text>
      <text class="pp-scope">{{ scope === 'CITY' ? '城市' : '校内' }}</text>
      <text v-if="selected" class="pp-selected-badge">已确认</text>
    </view>

    <!-- 输入框：输入变化即清除已选 ID，300ms 防抖后搜索 -->
    <view class="pp-input-wrap">
      <input
        class="pp-input"
        :value="inputText"
        :focus="focusFlag"
        type="text"
        :maxlength="40"
        :placeholder="placeholder"
        placeholder-class="pp-ph"
        confirm-type="search"
        @input="onInput"
        @focus="onFocus"
        @blur="onBlur"
        @confirm="onConfirm"
      />
      <view v-if="inputText" class="pp-clear" @tap.stop="onClearTap">
        <text class="pp-clear-text">×</text>
      </view>
    </view>

    <text v-if="errorText" class="pp-error-text">{{ errorText }}</text>

    <!-- 已选候选：显示确认后的稳定 ID 与核实信息 -->
    <view v-if="selected" class="pp-selected-box">
      <view class="pp-selected-title-row">
        <text class="pp-selected-name">{{ selected.name }}</text>
        <text class="pp-selected-kind">{{ kindLabel(selected) }}</text>
      </view>
      <text class="pp-selected-meta">{{ categoryLabel(selected.category) }}</text>
      <text class="pp-selected-meta">
        {{ verificationLabel(selected.verificationStatus) }}
      </text>
      <text v-if="!selected.routingReady" class="pp-selected-warn">
        该地点待核实，不能提交：{{ selected.unavailableReason || '缺少可用性核实信息' }}
      </text>
    </view>

    <!-- 候选面板 -->
    <view v-if="panelOpen" class="pp-panel">
      <view class="pp-panel-head">
        <text class="pp-panel-title">{{ panelTitle }}</text>
        <text v-if="inputText.trim()" class="pp-panel-keyword">“{{ inputText.trim() }}”</text>
      </view>

      <view v-if="searching" class="pp-panel-loading">
        <view class="pp-spinner"></view>
        <text>正在搜索{{ scope === 'CITY' ? '城市目的地' : '校园地点' }}…</text>
      </view>

      <view v-else-if="searchError" class="pp-panel-error">
        <text class="pp-error-title">地点搜索失败</text>
        <text class="pp-error-detail">{{ searchError }}</text>
        <view class="pp-error-retry" @tap.stop="retrySearch">
          <text>重试搜索</text>
        </view>
      </view>

      <view v-else-if="!inputText.trim()" class="pp-panel-empty">
        <text class="pp-empty-text">输入关键词后显示候选地点。</text>
        <text class="pp-empty-sub">起点从校园内部地点中选择；终点从成都市区目的地中选择。</text>
      </view>

      <view v-else-if="items.length === 0" class="pp-panel-empty">
        <text class="pp-empty-text">没有找到“{{ inputText.trim() }}”相关地点。</text>
        <text class="pp-empty-sub">可换用全称、别名或更短的关键词。</text>
      </view>

      <view v-else class="pp-list">
        <view
          v-for="(item, index) in items"
          :key="item.id"
          class="pp-candidate"
          :class="{ 'pp-candidate-disabled': !item.routingReady }"
          @tap.stop="onCandidateTap(item)"
        >
          <view class="pp-candidate-main">
            <view class="pp-candidate-title-row">
              <text class="pp-candidate-name">{{ item.name }}</text>
              <text v-if="duplicateCount(item.name) > 1" class="pp-candidate-dup">
                同名 {{ duplicateIndex(index) }}/{{ duplicateCount(item.name) }}
              </text>
              <text class="pp-candidate-kind">{{ kindLabel(item) }}</text>
              <text class="pp-candidate-status" :class="item.routingReady ? 'pp-ok' : 'pp-warn'">
                {{ item.routingReady ? '可用' : '待核实' }}
              </text>
            </view>
            <text class="pp-candidate-meta">
              {{ categoryLabel(item.category) }} · {{ verificationLabel(item.verificationStatus) }}
            </text>
            <text v-if="item.address" class="pp-candidate-meta">{{ item.address }}</text>
            <text class="pp-candidate-meta">
              {{ positionBrief(item) }}
            </text>
            <text v-if="!item.routingReady" class="pp-candidate-reason">
              不可用：{{ item.unavailableReason || '缺少经核实的入口与可走路段' }}
            </text>
          </view>
          <view class="pp-candidate-action">
            <text>{{ item.routingReady ? '选择' : '查看' }}</text>
          </view>
        </view>
      </view>

      <text v-if="hasDuplicateName" class="pp-panel-note">
        有多个同名地点，请核对类别和位置后确认。
      </text>
    </view>

    <!-- 选择确认层：待核实地点只展示原因，不提供“确认选择” -->
    <view v-if="confirming" class="pp-mask" @tap="cancelConfirm">
      <view class="pp-sheet" @tap.stop>
        <view class="pp-sheet-head">
          <text class="pp-sheet-title">{{ confirming.routingReady ? '确认选择该地点' : '该地点暂不可用' }}</text>
          <text class="pp-sheet-close" @tap.stop="cancelConfirm">×</text>
        </view>
        <view class="pp-sheet-body">
          <view class="pp-row">
            <text class="pp-k">名称</text>
            <text class="pp-v">{{ confirming.name }}</text>
          </view>
          <view v-if="confirming.address" class="pp-row">
            <text class="pp-k">地址</text>
            <text class="pp-v">{{ confirming.address }}</text>
          </view>
          <view v-if="duplicateCount(confirming.name) > 1" class="pp-row">
            <text class="pp-k">地点编号</text>
            <text class="pp-v">{{ confirming.id }}</text>
          </view>
          <view class="pp-row">
            <text class="pp-k">类别</text>
            <text class="pp-v">{{ kindLabel(confirming) }} · {{ categoryLabel(confirming.category) }}</text>
          </view>
          <view class="pp-row">
            <text class="pp-k">核实状态</text>
            <text class="pp-v">{{ verificationLabel(confirming.verificationStatus) }}</text>
          </view>
          <view class="pp-row">
            <text class="pp-k">显示位置</text>
            <text class="pp-v">{{ positionBrief(confirming) }}</text>
          </view>
          <view class="pp-row">
            <text class="pp-k">算路位置</text>
            <text class="pp-v">{{ routingPositionBrief(confirming) }}</text>
          </view>

          <view v-if="duplicateCount(confirming.name) > 1" class="pp-dup-notice">
            <text>同名候选共 {{ duplicateCount(confirming.name) }} 个，请核对编号与位置，避免选错对象。</text>
          </view>

          <view v-if="!confirming.routingReady" class="pp-block-box">
            <text class="pp-block-title">不能作为已可用地点提交</text>
            <text class="pp-block-text">
              {{ confirming.unavailableReason || '该地点缺少经核实的入口与可走路段，暂不可算路。' }}
            </text>
          </view>
          <view v-else class="pp-confirm-hint">
            <text>修改搜索文字后，需要重新选择地点。</text>
          </view>
        </view>
        <view class="pp-sheet-actions">
          <button class="pp-btn pp-btn-cancel" @tap.stop="cancelConfirm">
            {{ confirming.routingReady ? '取消' : '知道了' }}
          </button>
          <button v-if="confirming.routingReady" class="pp-btn pp-btn-ok" @tap.stop="confirmSelection">
            确认选择
          </button>
        </view>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { categoryLabel } from '@/services/place-format';
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import type { GeoPosition, PlaceCandidate, PlaceScope } from '@/types/journey';
import { createDebouncedTask, searchPlaces, toJourneyUiError } from '@/services/api';

/** 地点输入与确认。防抖搜索并丢弃旧响应，修改文本时清除选择；待核实地点只能查看。 */

const props = withDefaults(
  defineProps<{
    modelValue: string;
    scope: PlaceScope;
    label: string;
    tagText?: string;
    tagTheme?: 'start' | 'end';
    placeholder?: string;
    errorText?: string;
    required?: boolean;
  }>(),
  {
    tagText: '',
    tagTheme: 'start',
    placeholder: '输入关键词搜索地点',
    errorText: '',
    required: true
  }
);

const emit = defineEmits<{
  (event: 'update:modelValue', value: string): void;
  (event: 'select', place: PlaceCandidate): void;
  (event: 'clear'): void;
}>();

const inputText = ref(props.modelValue);
const selected = ref<PlaceCandidate | null>(null);
const items = ref<PlaceCandidate[]>([]);
const searching = ref(false);
const searchError = ref('');
const panelOpen = ref(false);
const confirming = ref<PlaceCandidate | null>(null);
const focusFlag = ref(false);

const searchVersion = ref(0);
/* 当前候选列表对应的关键词：输入变化后旧列表立即失效，避免防抖期间确认旧候选 */
const resultsKeyword = ref('');
const searchDebouncer = createDebouncedTask(300);
let blurTimer: ReturnType<typeof setTimeout> | null = null;

watch(
  () => props.modelValue,
  (value) => {
    if (value === inputText.value) return;
    inputText.value = value;
    if (selected.value && value !== selected.value.name) {
      selected.value = null;
      emit('clear');
    }
    if (value.trim() !== resultsKeyword.value) {
      items.value = [];
      resultsKeyword.value = '';
      confirming.value = null;
      searching.value = false;
    }
  }
);

const panelTitle = computed(() => {
  if (props.scope === 'CITY') return '城市目的地候选';
  return '校园地点候选';
});

const hasDuplicateName = computed(() => {
  const names = new Set<string>();
  for (const item of items.value) {
    if (names.has(item.name)) return true;
    names.add(item.name);
  }
  return false;
});

function duplicateCount(name: string): number {
  return items.value.filter((item) => item.name === name).length;
}

function duplicateIndex(index: number): number {
  const item = items.value[index];
  if (!item) return 1;
  let position = 0;
  for (let i = 0; i <= index; i += 1) {
    if (items.value[i] && items.value[i].name === item.name) position += 1;
  }
  return position;
}

/* ---------------- 搜索与版本控制 ---------------- */

function runSearch(keyword: string, version: number): void {
  searching.value = true;
  searchError.value = '';
  panelOpen.value = true;
  searchPlaces(keyword, props.scope)
    .then((list) => {
      if (version !== searchVersion.value) {
        console.info('[PlacePicker] 丢弃过期的地点搜索响应', { keyword, version, latest: searchVersion.value });
        return;
      }
      items.value = list;
      resultsKeyword.value = keyword;
    })
    .catch((error) => {
      if (version !== searchVersion.value) {
        console.info('[PlacePicker] 丢弃过期的地点搜索错误', { keyword, version, latest: searchVersion.value });
        return;
      }
      items.value = [];
      resultsKeyword.value = '';
      searchError.value = toJourneyUiError(error).hint;
      console.warn('[PlacePicker] 地点搜索失败', error);
    })
    .finally(() => {
      if (version === searchVersion.value) searching.value = false;
    });
}

function scheduleSearch(immediate: boolean): void {
  const keyword = inputText.value.trim();
  searchVersion.value += 1;
  const version = searchVersion.value;
  searchDebouncer.cancel();
  searchError.value = '';
  // 关键词一变，旧候选立即失效：面板不再展示、也不允许确认上一关键词的结果
  if (keyword !== resultsKeyword.value) {
    items.value = [];
    resultsKeyword.value = '';
    confirming.value = null;
  }
  if (!keyword || selected.value) {
    items.value = [];
    searching.value = false;
    return;
  }
  // 防抖等待期也进入加载态，避免旧候选在 300ms 内仍可被点击
  searching.value = true;
  if (immediate) {
    runSearch(keyword, version);
    return;
  }
  searchDebouncer.schedule(() => {
    runSearch(keyword, version);
  });
}

function retrySearch(): void {
  scheduleSearch(true);
}

/* ---------------- 输入交互 ---------------- */

interface PickerInputEvent {
  detail?: { value?: string };
}

function onInput(event: Event): void {
  // uni-app 小程序 input 事件是 { detail: { value } }，Vue 模板类型按 DOM Event 约束，这里做窄化转换。
  const pickerEvent = event as unknown as PickerInputEvent;
  const value =
    pickerEvent && pickerEvent.detail && typeof pickerEvent.detail.value === 'string'
      ? pickerEvent.detail.value
      : '';
  inputText.value = value;
  emit('update:modelValue', value);
  panelOpen.value = true;
  if (selected.value) {
    selected.value = null;
    emit('clear');
  }
  scheduleSearch(false);
}

function onFocus(): void {
  if (blurTimer !== null) clearTimeout(blurTimer);
  blurTimer = null;
  panelOpen.value = true;
  if (!selected.value && inputText.value.trim() && items.value.length === 0) {
    scheduleSearch(true);
  }
}

function onBlur(): void {
  focusFlag.value = false;
  if (blurTimer !== null) clearTimeout(blurTimer);
  blurTimer = setTimeout(() => {
    blurTimer = null;
    if (!confirming.value) panelOpen.value = false;
  }, 220);
}

function onConfirm(): void {
  if (inputText.value.trim()) scheduleSearch(true);
}

function onClearTap(): void {
  inputText.value = '';
  emit('update:modelValue', '');
  if (selected.value) {
    selected.value = null;
  }
  emit('clear');
  items.value = [];
  resultsKeyword.value = '';
  confirming.value = null;
  searchError.value = '';
  searching.value = false;
  searchVersion.value += 1;
  searchDebouncer.cancel();
  panelOpen.value = true;
  focusFlag.value = true;
  setTimeout(() => {
    focusFlag.value = false;
  }, 300);
}

/* ---------------- 选择确认 ---------------- */

function isConfirmable(place: PlaceCandidate): boolean {
  const keyword = inputText.value.trim();
  if (!keyword || resultsKeyword.value !== keyword) return false;
  return items.value.some((item) => item.id === place.id);
}

function onCandidateTap(place: PlaceCandidate): void {
  if (!isConfirmable(place)) {
    // 旧关键词的候选不允许再进入确认流程
    console.warn('[PlacePicker] 拒绝确认与当前关键词不匹配的旧候选', {
      id: place.id,
      name: place.name,
      keyword: inputText.value.trim(),
      resultsKeyword: resultsKeyword.value
    });
    return;
  }
  if (blurTimer !== null) clearTimeout(blurTimer);
  blurTimer = null;
  confirming.value = place;
}

function cancelConfirm(): void {
  confirming.value = null;
}

function confirmSelection(): void {
  const place = confirming.value;
  if (!place) return;
  if (!isConfirmable(place)) {
    console.warn('[PlacePicker] 拒绝确认与当前关键词不匹配的旧候选', {
      id: place.id,
      name: place.name,
      keyword: inputText.value.trim(),
      resultsKeyword: resultsKeyword.value
    });
    confirming.value = null;
    return;
  }
  if (!place.routingReady) {
    // 双重保护：待核实地点绝不允许进入已选状态
    console.warn('[PlacePicker] 拒绝选择待核实地点', place.id, place.name);
    confirming.value = null;
    return;
  }
  selected.value = place;
  inputText.value = place.name;
  emit('update:modelValue', place.name);
  emit('select', place);
  confirming.value = null;
  panelOpen.value = false;
  items.value = [];
  resultsKeyword.value = '';
  searchError.value = '';
  searchVersion.value += 1;
  searchDebouncer.cancel();
  if (typeof uni.hideKeyboard === 'function') {
    uni.hideKeyboard();
  }
  console.info('[PlacePicker] 已确认地点', {
    id: place.id,
    name: place.name,
    scope: props.scope,
    verificationStatus: place.verificationStatus
  });
}

/* ---------------- 对外方法（交换起终点 / 校验定位 / 重置） ---------------- */

function applySelection(place: PlaceCandidate | null): void {
  searchVersion.value += 1;
  searchDebouncer.cancel();
  searching.value = false;
  searchError.value = '';
  items.value = [];
  resultsKeyword.value = '';
  panelOpen.value = false;
  confirming.value = null;
  if (place) {
    selected.value = place;
    inputText.value = place.name;
    emit('update:modelValue', place.name);
    emit('select', place);
    return;
  }
  const hadSelection = selected.value !== null;
  const hadText = inputText.value.length > 0;
  selected.value = null;
  inputText.value = '';
  if (hadSelection || hadText) {
    emit('update:modelValue', '');
    emit('clear');
  }
}

function reset(): void {
  applySelection(null);
}

function focus(): void {
  panelOpen.value = true;
  focusFlag.value = false;
  setTimeout(() => {
    focusFlag.value = true;
  }, 30);
}

onBeforeUnmount(() => {
  searchDebouncer.cancel();
  if (blurTimer !== null) clearTimeout(blurTimer);
});

defineExpose({ applySelection, reset, focus });

/* ---------------- 展示辅助 ---------------- */

const KIND_LABELS: Record<string, string> = {
  CAMPUS: '校园地点',
  GATE: '校门',
  CITY: '城市目的地'
};

const VERIFICATION_LABELS: Record<string, string> = {
  APPLICATION_VERIFIED: '应用已核实',
  DISPLAY_POSITION_VERIFIED: '展示位置已核实',
  AMAP_POI_ENTRANCE: '高德入口',
  AMAP_POI_LOCATION: '高德地点标注点',
  SOURCE_VERIFIED: '来源已核实',
  UNVERIFIED: '来源未核验',
  PARTIALLY_VERIFIED: '部分核实'
};

function kindLabel(place: PlaceCandidate): string {
  return KIND_LABELS[place.kind] || place.kind;
}

function verificationLabel(status: string): string {
  return VERIFICATION_LABELS[status] || status || '未提供';
}

function coordinateText(position: GeoPosition | null): string {
  if (!position || position.longitude === null || position.latitude === null) return '坐标未提供';
  return `${position.coordinateSystem} ${position.longitude.toFixed(5)},${position.latitude.toFixed(5)}`;
}

function positionBrief(place: PlaceCandidate): string {
  const position = place.displayPosition;
  if (!position) return '显示位置未提供';
  return `点 ${position.pointId} · ${coordinateText(position)}`;
}

function routingPositionBrief(place: PlaceCandidate): string {
  const position = place.routingPosition;
  if (!position) return '未提供（该地点不可用于算路）';
  return `点 ${position.pointId} · ${coordinateText(position)}`;
}
</script>

<style>
.place-picker {
  padding: 2rpx 0 6rpx;
}

.pp-label {
  display: flex;
  align-items: center;
  gap: 10rpx;
  margin-bottom: 12rpx;
}

.pp-tag {
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

.pp-tag-start {
  background: #1f9d6b;
}

.pp-tag-end {
  background: #d64545;
}

.pp-label-text {
  font-size: 25rpx;
  font-weight: 600;
  color: #33404f;
  line-height: 1.4;
}

.pp-required {
  color: #d64545;
  font-size: 24rpx;
  line-height: 1;
}

.pp-scope {
  font-size: 19rpx;
  color: #7c8a9c;
  background: #eef2f7;
  border-radius: 40rpx;
  padding: 2rpx 12rpx;
  line-height: 1.5;
}

.pp-selected-badge {
  margin-left: auto;
  font-size: 19rpx;
  font-weight: 600;
  color: #157347;
  background: #e2f5ea;
  border-radius: 40rpx;
  padding: 2rpx 12rpx;
  line-height: 1.5;
}

.pp-input-wrap {
  position: relative;
  display: flex;
  align-items: center;
}

.pp-input {
  flex: 1;
  height: 84rpx;
  box-sizing: border-box;
  padding: 0 70rpx 0 22rpx;
  border: 2rpx solid #e2e8f0;
  border-radius: 16rpx;
  background: #ffffff;
  color: #131a24;
  font-size: 27rpx;
}

.place-picker-error .pp-input {
  border-color: #e2a2a2;
  background: #fffafa;
}

.pp-ph {
  color: #a8b4c2;
}

.pp-clear {
  position: absolute;
  right: 8rpx;
  top: 0;
  bottom: 0;
  width: 60rpx;
  display: flex;
  align-items: center;
  justify-content: center;
}

.pp-clear-text {
  font-size: 34rpx;
  color: #9aa8b8;
  line-height: 1;
}

.pp-error-text {
  display: block;
  margin-top: 8rpx;
  font-size: 22rpx;
  color: #c0392b;
  line-height: 1.5;
}

.pp-selected-box {
  margin-top: 12rpx;
  padding: 16rpx 20rpx;
  border-radius: 14rpx;
  background: #f2f8ff;
  border: 1rpx solid #d8e8f8;
}

.pp-selected-title-row {
  display: flex;
  align-items: center;
  gap: 10rpx;
}

.pp-selected-name {
  font-size: 26rpx;
  font-weight: 600;
  color: #17518c;
  line-height: 1.4;
}

.pp-selected-kind {
  font-size: 19rpx;
  color: #17518c;
  background: #e3f0fc;
  padding: 1rpx 10rpx;
  border-radius: 8rpx;
  line-height: 1.5;
}

.pp-selected-meta {
  display: block;
  margin-top: 5rpx;
  font-size: 21rpx;
  color: #5b6a7d;
  line-height: 1.5;
  word-break: break-all;
}

.pp-selected-warn {
  display: block;
  margin-top: 8rpx;
  font-size: 21rpx;
  color: #b45309;
  line-height: 1.5;
}

/* ---------- 候选面板 ---------- */
.pp-panel {
  margin-top: 12rpx;
  border-radius: 16rpx;
  background: #ffffff;
  border: 1rpx solid #e6ecf3;
  box-shadow: 0 8rpx 26rpx rgba(16, 32, 56, 0.08);
  overflow: hidden;
}

.pp-panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12rpx;
  padding: 16rpx 20rpx;
  background: #f7f9fc;
  border-bottom: 1rpx solid #edf1f6;
}

.pp-panel-title {
  font-size: 22rpx;
  font-weight: 600;
  color: #33404f;
}

.pp-panel-keyword {
  font-size: 21rpx;
  color: #7c8a9c;
  max-width: 360rpx;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.pp-panel-loading {
  display: flex;
  align-items: center;
  gap: 14rpx;
  padding: 34rpx 24rpx;
  font-size: 22rpx;
  color: #5b6a7d;
}

.pp-spinner {
  width: 28rpx;
  height: 28rpx;
  border: 3rpx solid #d7e4f2;
  border-top-color: #1d6fbf;
  border-radius: 50%;
  animation: pp-rotate 0.8s linear infinite;
}

@keyframes pp-rotate {
  from {
    transform: rotate(0deg);
  }
  to {
    transform: rotate(360deg);
  }
}

.pp-panel-error {
  padding: 24rpx;
}

.pp-error-title {
  display: block;
  font-size: 24rpx;
  font-weight: 600;
  color: #c0392b;
  line-height: 1.5;
}

.pp-error-detail {
  display: block;
  margin-top: 6rpx;
  font-size: 21rpx;
  color: #7c8a9c;
  line-height: 1.6;
}

.pp-error-retry {
  display: inline-flex;
  margin-top: 16rpx;
  padding: 8rpx 24rpx;
  border-radius: 40rpx;
  background: #eef5fc;
  color: #17518c;
  font-size: 22rpx;
  line-height: 1.5;
}

.pp-panel-empty {
  padding: 28rpx 24rpx;
}

.pp-empty-text {
  display: block;
  font-size: 23rpx;
  color: #33404f;
  line-height: 1.6;
}

.pp-empty-sub {
  display: block;
  margin-top: 6rpx;
  font-size: 21rpx;
  color: #7c8a9c;
  line-height: 1.6;
}

.pp-list {
  max-height: 620rpx;
  overflow-y: auto;
}

.pp-candidate {
  display: flex;
  align-items: flex-start;
  gap: 14rpx;
  padding: 18rpx 20rpx;
  border-bottom: 1rpx solid #f0f3f7;
}

.pp-candidate:last-child {
  border-bottom: none;
}

.pp-candidate-disabled {
  background: #fcfcfd;
}

.pp-candidate-main {
  flex: 1;
  min-width: 0;
}

.pp-candidate-title-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8rpx;
}

.pp-candidate-name {
  font-size: 26rpx;
  font-weight: 600;
  color: #131a24;
  line-height: 1.4;
}

.pp-candidate-dup {
  font-size: 18rpx;
  font-weight: 600;
  color: #9a5b00;
  background: #fff3dd;
  border-radius: 6rpx;
  padding: 1rpx 8rpx;
  line-height: 1.5;
}

.pp-candidate-kind {
  font-size: 18rpx;
  color: #5b6a7d;
  background: #eef2f7;
  border-radius: 6rpx;
  padding: 1rpx 8rpx;
  line-height: 1.5;
}

.pp-candidate-status {
  font-size: 18rpx;
  border-radius: 6rpx;
  padding: 1rpx 8rpx;
  line-height: 1.5;
}

.pp-ok {
  color: #157347;
  background: #e2f5ea;
}

.pp-warn {
  color: #b45309;
  background: #fdf2e0;
}

.pp-candidate-meta {
  display: block;
  margin-top: 6rpx;
  font-size: 20rpx;
  color: #7c8a9c;
  line-height: 1.5;
  word-break: break-all;
}

.pp-candidate-reason {
  display: block;
  margin-top: 8rpx;
  font-size: 20rpx;
  color: #b45309;
  line-height: 1.55;
}

.pp-candidate-action {
  flex-shrink: 0;
  padding: 4rpx 14rpx;
  border-radius: 40rpx;
  background: #eef5fc;
  color: #17518c;
  font-size: 20rpx;
  line-height: 1.6;
}

.pp-candidate-disabled .pp-candidate-action {
  background: #f1f3f6;
  color: #7c8a9c;
}

.pp-panel-note {
  display: block;
  padding: 14rpx 20rpx;
  background: #fffaf0;
  border-top: 1rpx solid #f6e7c8;
  font-size: 20rpx;
  color: #9a5b00;
  line-height: 1.6;
}

/* ---------- 确认层 ---------- */
.pp-mask {
  position: fixed;
  top: 0;
  right: 0;
  bottom: 0;
  left: 0;
  z-index: 900;
  background: rgba(10, 20, 35, 0.55);
  display: flex;
  align-items: flex-end;
}

.pp-sheet {
  width: 100%;
  max-height: 82vh;
  background: #ffffff;
  border-radius: 28rpx 28rpx 0 0;
  padding: 0 0 calc(18rpx + env(safe-area-inset-bottom));
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
}

.pp-sheet-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 28rpx 32rpx 16rpx;
  border-bottom: 1rpx solid #eef2f7;
}

.pp-sheet-title {
  font-size: 30rpx;
  font-weight: 650;
  color: #131a24;
}

.pp-sheet-close {
  font-size: 40rpx;
  color: #9aa8b8;
  line-height: 1;
  padding: 0 8rpx;
}

.pp-sheet-body {
  padding: 18rpx 32rpx 8rpx;
  overflow-y: auto;
}

.pp-row {
  display: flex;
  align-items: flex-start;
  gap: 18rpx;
  padding: 7rpx 0;
}

.pp-k {
  width: 120rpx;
  flex-shrink: 0;
  font-size: 22rpx;
  color: #7c8a9c;
  line-height: 1.6;
}

.pp-v {
  flex: 1;
  font-size: 22rpx;
  color: #33404f;
  line-height: 1.6;
  word-break: break-all;
}

.pp-dup-notice {
  margin-top: 16rpx;
  padding: 14rpx 18rpx;
  border-radius: 12rpx;
  background: #fffaf0;
  border: 1rpx solid #f6e7c8;
  font-size: 21rpx;
  color: #9a5b00;
  line-height: 1.6;
}

.pp-block-box {
  margin-top: 16rpx;
  padding: 16rpx 18rpx;
  border-radius: 12rpx;
  background: #fff5f5;
  border: 1rpx solid #f6d5d5;
}

.pp-block-title {
  display: block;
  font-size: 23rpx;
  font-weight: 600;
  color: #c0392b;
  line-height: 1.5;
}

.pp-block-text {
  display: block;
  margin-top: 6rpx;
  font-size: 21rpx;
  color: #8a4a42;
  line-height: 1.65;
}

.pp-confirm-hint {
  margin-top: 16rpx;
  font-size: 21rpx;
  color: #7c8a9c;
  line-height: 1.6;
}

.pp-sheet-actions {
  display: flex;
  gap: 16rpx;
  padding: 18rpx 32rpx 8rpx;
}

.pp-btn {
  flex: 1;
  height: 84rpx;
  line-height: 84rpx;
  margin: 0;
  padding: 0;
  border: none;
  border-radius: 16rpx;
  font-size: 27rpx;
  font-weight: 600;
  text-align: center;
}

.pp-btn::after {
  border: none;
}

.pp-btn-cancel {
  background: #eef2f7;
  color: #5b6a7d;
}

.pp-btn-ok {
  background: linear-gradient(180deg, #2b8ce0, #1d6fbf);
  color: #ffffff;
}
</style>
