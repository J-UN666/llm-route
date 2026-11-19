<template>
  <view class="settings">
    <view class="panel">
      <text class="heading">服务配置</text>
      <view class="modes">
        <button :class="{ active: mode === 'default' }" :disabled="busy" @tap="choose('default')">
          默认托管服务
        </button>
        <button :class="{ active: mode === 'personal' }" :disabled="busy || !PERSONAL_CONFIG_ENABLED"
          @tap="choose('personal')">
          个人配置
        </button>
      </view>
      <text v-if="!PERSONAL_CONFIG_ENABLED" class="hint">个人配置仅在 HTTPS 渠道可用。</text>
      <text v-else-if="mode === 'default'" class="hint">当前使用项目提供的服务。</text>
      <text v-else class="hint">
        Key 在后端内存保存 30 分钟，重启服务后失效。关闭小程序进程后需重新配置。
      </text>
    </view>

    <view v-if="mode === 'personal'" class="personal">
      <view class="panel">
        <view class="row">
          <text class="label">高德 Web 服务 Key</text>
          <text class="status">{{ config?.amapVerified ? '已验证' : '未验证' }}</text>
        </view>
        <input v-model="amapKey" password maxlength="32" :disabled="busy" class="key"
          placeholder="输入高德 Web 服务 Key" />
        <view class="actions">
          <button :loading="operation === 'amap'" :disabled="busy || !amapKey.trim()" @tap="save('amap')">
            {{ config?.amapVerified ? '验证并更新' : '验证并启用' }}
          </button>
          <button v-if="config?.amapVerified" :disabled="busy" @tap="revoke('amap')">撤销</button>
        </view>
        <text class="hint">只配置高德即可使用普通行程查询。</text>
      </view>

      <view class="panel">
        <view class="row">
          <text class="label">模型 API Key</text>
          <text class="status">{{ config?.modelVerified ? '已验证' : '未验证' }}</text>
        </view>
        <text v-if="config?.modelVerified" class="hint">当前模型 {{ config.model }}</text>
        <picker :range="models" :value="modelIndex" :disabled="busy || models.length === 0" @change="selectModel">
          <view class="key model">{{ provider }} · {{ models[modelIndex] || '加载模型中' }}</view>
        </picker>
        <input v-model="modelKey" password maxlength="160" :disabled="busy" class="key" placeholder="输入模型 API Key" />
        <view class="actions">
          <button :loading="operation === 'model'" :disabled="busy || !modelKey.trim() || models.length === 0"
            @tap="save('model')">
            {{ config?.modelVerified ? '验证并更新' : '验证并启用' }}
          </button>
          <button v-if="config?.modelVerified" :disabled="busy" @tap="revoke('model')">撤销</button>
        </view>
      </view>

      <view v-if="config" class="expiry">
        <text>有效至 {{ formatBeijingDateTime(config.expiresAt) }}（北京时间）</text>
        <button :disabled="busy" @tap="revokeAll">撤销个人配置</button>
      </view>
    </view>
    <text v-if="notice" class="notice" :class="{ error: failed }" role="status">{{ notice }}</text>
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { onHide, onShow, onUnload } from '@dcloudio/uni-app';
import {
  ApiRequestError, PERSONAL_CONFIG_ENABLED, formatBeijingDateTime, getModelOptions, getServiceConfig,
  getServiceMode, revokePersonalConfig, revokeServiceKey, setServiceMode, verifyServiceKey,
  type PersonalService, type ServiceConfigStatus, type ServiceMode
} from '@/services/api';

const mode = ref(getServiceMode());
const config = ref<ServiceConfigStatus | null>(null);
const provider = ref('');
const models = ref<string[]>([]);
const modelIndex = ref(0);
const amapKey = ref('');
const modelKey = ref('');
const notice = ref('');
const failed = ref(false);
const operation = ref('');
const busy = computed(() => operation.value.length > 0);

function clearInputs(): void {
  amapKey.value = '';
  modelKey.value = '';
}

function showError(error: unknown): void {
  if (error instanceof ApiRequestError && error.code === 'SERVICE_CONFIG_EXPIRED') {
    config.value = null;
  }
  failed.value = true;
  notice.value = error instanceof ApiRequestError && error.serverMessage && !error.code.startsWith('NETWORK_')
    ? error.serverMessage : '暂时无法连接服务配置，请稍后重试。';
}

async function load(): Promise<void> {
  if (!PERSONAL_CONFIG_ENABLED || mode.value !== 'personal' || busy.value) return;
  operation.value = 'load';
  const [options, status] = await Promise.allSettled([getModelOptions(), getServiceConfig()]);
  if (options.status === 'fulfilled') {
    provider.value = options.value.provider;
    models.value = options.value.models;
  } else {
    showError(options.reason);
  }
  if (status.status === 'fulfilled') {
    config.value = status.value;
    modelIndex.value = Math.max(0, models.value.indexOf(config.value?.model || ''));
  } else {
    showError(status.reason);
  }
  operation.value = '';
}

function choose(choice: ServiceMode): void {
  setServiceMode(choice);
  mode.value = choice;
  clearInputs();
  notice.value = '';
  void load();
}

function selectModel(event: { detail: { value: string | number } }): void {
  modelIndex.value = Number(event.detail.value);
}

async function update(action: string, request: () => Promise<ServiceConfigStatus | null>, message: string): Promise<void> {
  if (busy.value) return;
  operation.value = action;
  notice.value = '';
  failed.value = false;
  try {
    config.value = await request();
    notice.value = message;
  } catch (error) {
    showError(error);
  } finally {
    operation.value = '';
  }
}

function save(service: PersonalService): void {
  const input = service === 'amap' ? amapKey : modelKey;
  const key = input.value.trim();
  input.value = '';
  void update(service, () => verifyServiceKey(service, key, models.value[modelIndex.value]), '验证通过，已启用。');
}

function revoke(service: PersonalService): void {
  const input = service === 'amap' ? amapKey : modelKey;
  input.value = '';
  void update('revoke', () => revokeServiceKey(service), '已撤销该 Key。');
}

function revokeAll(): void {
  clearInputs();
  void update('revoke', async () => {
    await revokePersonalConfig();
    return null;
  }, '个人配置已撤销。可重新验证 Key，或选择默认托管服务。');
}

onShow(load);
onHide(clearInputs);
onUnload(clearInputs);
</script>

<style scoped>
.settings { padding: 28rpx; color: #0b2545; }
.personal { display: flex; flex-direction: column; gap: 20rpx; margin-top: 20rpx; }
.panel { display: flex; flex-direction: column; gap: 16rpx; background: #fff; padding: 26rpx; border-radius: 16rpx; }
.heading { font-size: 34rpx; font-weight: 700; }
.label { font-size: 28rpx; font-weight: 650; }
.row, .actions, .modes { display: flex; flex-wrap: wrap; align-items: center; gap: 12rpx; }
.row { justify-content: space-between; }
.status { color: #246759; font-size: 24rpx; }
.hint, .expiry { color: #526579; font-size: 24rpx; line-height: 1.5; }
.key { border: 1px solid #ccd6e1; padding: 12rpx 16rpx; border-radius: 10rpx; min-height: 38rpx; font-size: 26rpx; }
.model { display: flex; align-items: center; }
button { margin: 0; font-size: 24rpx; padding: 8rpx 16rpx; color: #0b2545; background: #eff5fb; }
button.active { color: #fff; background: #0b2545; }
.expiry { display: flex; align-items: center; flex-wrap: wrap; justify-content: space-between; gap: 10rpx; }
.notice { display: block; margin-top: 20rpx; padding: 18rpx; border-radius: 10rpx; background: #e5f5ec; font-size: 26rpx; }
.notice.error { color: #9b3028; background: #fceceb; }
</style>
