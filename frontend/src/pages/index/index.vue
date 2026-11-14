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
          <text class="status-text">数据未接入</text>
        </view>
      </view>
    </view>

    <view class="content">
      <!-- 当前骨架说明，验收 C 可修改此段文案观察热更新 -->
      <view class="notice">
        <text class="notice-ico">ⓘ</text>
        <text class="notice-text">当前为 W1-03 首页界面壳：起终点输入与查询按钮仅占位，路线查询和地图将在后续任务接入。</text>
      </view>

      <!-- 规划输入卡 -->
      <view class="card">
        <view class="card-head">
          <view class="card-title">
            <text class="card-ico">◎</text>
            <text>规划路线</text>
          </view>
          <text class="badge-soft">占位</text>
        </view>

        <view class="card-body">
          <view class="field">
            <view class="field-label">
              <text class="tag tag-start">起</text>
              <text>出发地</text>
            </view>
            <input
              v-model="origin"
              class="combo-input"
              type="text"
              :maxlength="40"
              placeholder="搜索校内地点 / 校门"
              placeholder-class="ph"
              confirm-type="done"
            />
            <text class="field-hint">{{ originHint }}</text>
          </view>

          <view class="swap-row" @click="onSwap">
            <text class="link-btn">⇅ 交换起终点</text>
          </view>

          <view class="field">
            <view class="field-label">
              <text class="tag tag-end">终</text>
              <text>目的地</text>
            </view>
            <input
              v-model="destination"
              class="combo-input"
              type="text"
              :maxlength="40"
              placeholder="校内地点，或成都市区目的地"
              placeholder-class="ph"
              confirm-type="done"
            />
            <text class="field-hint">{{ destinationHint }}</text>
          </view>

          <view class="mode-row">
            <view class="mode mode-active">步行</view>
            <view class="mode mode-disabled">
              <text>骑行</text>
              <text class="mode-tag">未支持</text>
            </view>
            <view class="mode mode-disabled">
              <text>无障碍</text>
              <text class="mode-tag">未支持</text>
            </view>
          </view>
          <text class="mode-note">
            <text class="mode-note-ico">ⓘ</text>当前仅支持步行路线；骑行和无障碍尚无独立通行数据与算法，暂未支持。
          </text>

          <button
            class="btn-primary"
            :class="{ 'btn-idle': !canQuery }"
            @click="onQuery"
          >
            查询路线
          </button>
        </view>
      </view>

      <!-- 结果空态卡 -->
      <view class="card">
        <view class="card-head">
          <view class="card-title">
            <text class="card-ico">◈</text>
            <text>规划结果</text>
          </view>
          <text class="badge-soft">空</text>
        </view>
        <view class="result-empty">
          <text class="empty-ico">🧭</text>
          <text class="empty-title">尚未开始规划</text>
          <text class="empty-text">填写起点与终点后，这里将展示「校内步行 → 校门 → 城市公共交通」的完整路线。</text>
          <text class="empty-note">路线查询、地图与 Agent 过程将在后续任务接入。</text>
        </view>
      </view>

      <text class="foot-note">交大智行 · W1-03 首页壳 · 数据来源与版本待接入</text>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from "vue";
import { onLoad } from "@dcloudio/uni-app";

// 自定义导航栏需要自行让出状态栏高度（模拟器与真机使用系统状态栏高度）
// 使用 getWindowInfo，避免 getSystemInfoSync 的废弃告警；老基础库回退到 getSystemInfoSync
const statusBarHeight = ref(20);
try {
  const windowInfo = uni.getWindowInfo
    ? uni.getWindowInfo()
    : uni.getSystemInfoSync();
  if (windowInfo && typeof windowInfo.statusBarHeight === "number") {
    statusBarHeight.value = windowInfo.statusBarHeight;
  }
} catch (error) {
  console.warn("[交大智行] 读取状态栏高度失败，使用默认值 20px", error);
}

const origin = ref("");
const destination = ref("");

const canQuery = computed(
  () => origin.value.trim().length > 0 && destination.value.trim().length > 0
);

const originHint = computed(() => {
  const value = origin.value.trim();
  if (!value) {
    return "起点输入为占位，后续接入校内地点搜索。";
  }
  return `已输入：${value}（占位，尚未解析地点）`;
});

const destinationHint = computed(() => {
  const value = destination.value.trim();
  if (!value) {
    return "可输入天府广场 / 成都东客站 / 春熙路等目的地（占位）。";
  }
  return `已输入：${value}（占位，尚未解析地点）`;
});

function onSwap() {
  const temp = origin.value;
  origin.value = destination.value;
  destination.value = temp;
}

function onQuery() {
  if (!canQuery.value) {
    uni.showToast({
      title: "请先填写起点和终点",
      icon: "none",
    });
    return;
  }
  uni.showToast({
    title: "查询能力将在后续任务接入",
    icon: "none",
  });
}

onLoad(() => {
  console.log("[交大智行] 首页加载完成（W1-03 占位壳）");
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
  background: #fbbf24;
  box-shadow: 0 0 0 5rpx rgba(251, 191, 36, 0.18);
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

.card-body {
  padding: 26rpx 32rpx 32rpx;
}

/* ---------- 起终点输入 ---------- */
.field {
  margin-bottom: 8rpx;
}

.field-label {
  display: flex;
  align-items: center;
  gap: 12rpx;
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
}

.tag-start {
  background: #1f9d6b;
}

.tag-end {
  background: #d64545;
}

.combo-input {
  width: 100%;
  height: 84rpx;
  box-sizing: border-box;
  padding: 0 24rpx;
  border: 2rpx solid #e2e8f0;
  border-radius: 16rpx;
  background: #ffffff;
  color: #131a24;
  font-size: 28rpx;
}

.ph {
  color: #a8b4c2;
}

.field-hint {
  display: block;
  margin-top: 10rpx;
  font-size: 22rpx;
  color: #7c8a9c;
  line-height: 1.45;
}

.swap-row {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  padding: 2rpx 0 18rpx;
}

.link-btn {
  font-size: 24rpx;
  color: #1d6fbf;
  padding: 6rpx 12rpx;
  border-radius: 10rpx;
  line-height: 1.3;
}

/* ---------- 出行方式 ---------- */
.mode-row {
  display: flex;
  gap: 8rpx;
  margin-top: 20rpx;
  padding: 8rpx;
  background: #f5f7fa;
  border-radius: 18rpx;
}

.mode {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6rpx;
  padding: 14rpx 4rpx;
  font-size: 24rpx;
  color: #5b6a7d;
  border-radius: 12rpx;
  line-height: 1.3;
}

.mode-active {
  background: #ffffff;
  color: #17518c;
  font-weight: 600;
  box-shadow: 0 2rpx 4rpx rgba(16, 32, 56, 0.06), 0 4rpx 12rpx rgba(16, 32, 56, 0.05);
}

.mode-disabled {
  color: #a8b4c2;
}

.mode-tag {
  font-size: 18rpx;
  font-weight: 600;
  color: #7c8a9c;
  background: #e5ebf2;
  border-radius: 6rpx;
  padding: 0 6rpx;
  line-height: 1.6;
}

.mode-note {
  display: block;
  margin-top: 14rpx;
  font-size: 22rpx;
  color: #7c8a9c;
  line-height: 1.6;
}

.mode-note-ico {
  color: #e08a1e;
  margin-right: 4rpx;
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

.btn-primary.btn-idle {
  background: linear-gradient(180deg, #9fc4e6, #7fadd7);
  box-shadow: 0 4rpx 12rpx rgba(29, 111, 191, 0.16);
}

/* ---------- 结果空态 ---------- */
.result-empty {
  padding: 54rpx 32rpx 58rpx;
  display: flex;
  flex-direction: column;
  align-items: center;
}

.empty-ico {
  font-size: 64rpx;
  line-height: 1.2;
  opacity: 0.5;
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
  font-size: 24rpx;
  color: #7c8a9c;
  line-height: 1.8;
  text-align: center;
}

.empty-note {
  margin-top: 14rpx;
  font-size: 22rpx;
  color: #a8b4c2;
  line-height: 1.6;
  text-align: center;
}

.foot-note {
  display: block;
  padding: 10rpx 20rpx 24rpx;
  text-align: center;
  font-size: 20rpx;
  color: #a8b4c2;
  line-height: 1.7;
}
</style>
