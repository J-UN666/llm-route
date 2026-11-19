<template>
  <view class="chat">
    <view class="toolbar">
      <navigator open-type="navigateBack" class="plain-button">普通表单</navigator>
      <button v-if="session || pending" class="plain-button" @tap="newConversation">新会话</button>
    </view>

    <view v-if="!messages.length && !pending && !restoring" class="welcome">
      <text>从犀浦校区出发，想去成都哪里？</text>
      <text class="muted">可以补充地点、出发时间或换乘偏好。</text>
      <button class="outline" @tap="sample">从图书馆去天府广场，少走路</button>
    </view>

    <view v-for="message in messages" :key="message.sequence" class="message"
      :id="message.sequence === messages[messages.length - 1]?.sequence ? 'chat-latest' : undefined"
      :class="{ user: message.role === 'USER' }">
      <text class="message-text">{{ message.content }}</text>
      <view v-if="message.status === 'SUCCEEDED' && message.journeyRequestId &&
        message.journeyRequestId !== latest?.journeyRequestId" class="history">
        <text>{{ formatBeijingDateTime(message.createdAt) }} 的历史路线</text>
        <button class="plain-button" :disabled="loading || !session"
          @tap="openHistory(message.journeyRequestId)">查看</button>
      </view>
    </view>

    <view v-if="pending" id="chat-pending" class="message user">
      <text class="message-text">{{ pending.displayText }}</text>
      <text class="muted">{{ loading ? '正在查询…' : '上次提交未确认' }}</text>
      <button v-if="!loading && !restoring" class="outline" @tap="retry">重试提交</button>
    </view>
    <text v-if="restoring" class="muted">正在恢复会话…</text>
    <view v-if="notice" class="notice">
      <text>{{ notice.message }}</text>
      <text v-if="notice.requestId" class="muted">问题编号 {{ notice.requestId }}</text>
      <button v-if="notice.retryable && !pending" class="outline" :disabled="loading || restoring" @tap="retry">重试</button>
    </view>

    <view v-if="hasChoices && !pending && !loading && !closed" class="choices">
      <view v-for="item in clarifications" :key="item.field">
        <template v-if="item.confirmationId">
          <text class="muted">{{ item.field === 'ORIGIN' ? '选择起点' : '选择终点' }}</text>
          <button v-for="candidate in item.candidates" :key="candidate.id" class="candidate"
            :disabled="!candidate.routingReady" @tap="confirmCandidate(item, candidate)">
            {{ candidate.name }}{{ candidate.category ? ' · ' + categoryLabel(candidate.category) : '' }}
            <text v-if="!candidate.routingReady" class="muted">
              {{ candidate.unavailableReason || '暂无法算路' }}
            </text>
          </button>
        </template>
      </view>
    </view>

    <view v-if="!loading && !pending && !notice && latest?.status === 'JOURNEY_FAILED'" class="notice">
      <text v-if="latest.error?.requestId" class="muted">行程编号 {{ latest.error.requestId }}</text>
      <button v-if="latest.error?.retryable && !closed" class="outline" @tap="sendMessage('按当前条件重新查询')">
        重新查路线
      </button>
    </view>

    <view v-if="showJourney && visibleJourney" id="chat-route" class="route">
      <view class="route-head">
        <text>{{ historyJourney ? '历史路线' : pending ? '会话最新路线' : '本次路线' }}</text>
        <text class="muted">{{ formatBeijingDateTime(visibleJourney.queriedAt) }}</text>
        <button v-if="historyJourney" class="plain-button" @tap="returnToLatest">返回本次路线</button>
      </view>
      <template v-if="visibleJourney.status === 'SUCCEEDED'">
        <text v-for="warning in visibleJourney.warnings || []" :key="warning" class="muted">{{ warning }}</text>
        <JourneyCard v-for="(plan, index) in visibleJourney.plans" :key="plan.planId"
          :plan="plan" :index="index" :selected="plan.planId === selectedPlanId"
          @select="selectPlan" />
        <template v-if="selectedPlan">
          <JourneyDetail :plan="selectedPlan" />
          <JourneyMap :plan="selectedPlan" />
        </template>
      </template>
      <text v-else class="muted">当前条件下没有可用方案。</text>
    </view>

    <view class="composer">
      <textarea v-model="draft" auto-height :maxlength="500" :disabled="closed"
        placeholder="输入地点或修改出行条件" />
      <button class="send" :disabled="!draft.trim() || loading || restoring || closed" @tap="sendMessage(draft)">
        发送
      </button>
    </view>
    <view id="chat-end"></view>
  </view>
</template>

<script setup lang="ts">
import { computed, nextTick, ref } from 'vue';
import { onShow, onUnload } from '@dcloudio/uni-app';
import JourneyCard from '@/components/JourneyCard.vue';
import JourneyDetail from '@/components/JourneyDetail.vue';
import JourneyMap from '@/components/JourneyMap.vue';
import {
  ApiRequestError, createAssistantSession, closeAssistantSession, formatBeijingDateTime,
  getAssistantJourney, getAssistantSession, LatestRequestTracker, sendAssistantTurn
} from '@/services/api';
import { defaultSelectedPlanId, resolveSelectedPlan, selectExistingPlanId } from '@/services/journey-selection';
import { categoryLabel } from '@/services/place-format';
import type {
  AssistantCandidate, AssistantInput, AssistantMessage, AssistantReply,
  AssistantSession, AssistantSubmission, Clarification
} from '@/types/assistant';
import type { JourneyResponse } from '@/types/journey';

const STORAGE_KEY = 'zhixing.assistant.session.v1';
interface StoredSession {
  session: AssistantSession | null;
  pending: AssistantSubmission | null;
}
interface Notice {
  message: string;
  requestId: string | null;
  retryable: boolean;
}

const stored = (uni.getStorageSync(STORAGE_KEY) || {}) as Partial<StoredSession>;
const session = ref<AssistantSession | null>(stored.session ?? null);
const pending = ref<AssistantSubmission | null>(stored.pending ?? null);
const messages = ref<AssistantMessage[]>([]);
const latest = ref<AssistantReply | null>(null);
const clarifications = ref<Clarification[]>([]);
const draft = ref('');
const loading = ref(false);
const restoring = ref(false);
const closed = ref(false);
const notice = ref<Notice | null>(null);
const historyJourney = ref<JourneyResponse | null>(null);
const selectedPlanId = ref<string | null>(null);
const hasChoices = computed(() => clarifications.value.some((item) => item.confirmationId));
const visibleJourney = computed(() => historyJourney.value ?? latest.value?.journey ?? null);
const showJourney = computed(() => {
  // A newer server snapshot can be displayed while the original submission is still replayable.
  if (pending.value) return (latest.value?.stateVersion ?? -1) > pending.value.stateVersion;
  return !loading.value && (!!historyJourney.value || !notice.value);
});
const selectedPlan = computed(() => resolveSelectedPlan(visibleJourney.value?.plans ?? [], selectedPlanId.value));

const requests = new LatestRequestTracker();

function save(): void {
  uni.setStorageSync(STORAGE_KEY, { session: session.value, pending: pending.value } satisfies StoredSession);
}

function scrollToEnd(): void {
  void nextTick(() => {
    const selector = pending.value && !loading.value ? '#chat-pending'
      : visibleJourney.value && showJourney.value ? '#chat-route'
      : messages.value.length && !pending.value && !notice.value ? '#chat-latest' : '#chat-end';
    uni.pageScrollTo({ selector, duration: 150, offsetTop: selector === '#chat-end' ? 0 : -42 });
  });
}

function showError(error: unknown): void {
  if (error instanceof ApiRequestError) {
    const message = error.code === 'NETWORK_TIMEOUT'
      ? '问路响应超时，可以重试这条消息或返回普通表单。'
      : error.code === 'NETWORK_ERROR'
        ? '连接中断，请恢复网络后重试。'
        : error.serverMessage || error.message;
    notice.value = { message, retryable: error.retryable, requestId: error.requestId };
  } else {
    notice.value = { message: '暂时无法完成操作，请重试。', retryable: true, requestId: null };
  }
}

function handleMissingConversation(error: unknown, duringRestore = false): boolean {
  if (!(error instanceof ApiRequestError) ||
    (error.code !== 'CONVERSATION_NOT_FOUND' && !(duringRestore && error.httpStatus === 404))) {
    return false;
  }
  resetConversation();
  notice.value = {
    message: '会话已失效，发送消息即可开始新会话。', retryable: false, requestId: error.requestId
  };
  return true;
}

function setLatest(reply: AssistantReply | null, items: Clarification[]): void {
  latest.value = reply;
  clarifications.value = items;
  historyJourney.value = null;
  selectedPlanId.value = defaultSelectedPlanId(reply?.journey?.plans ?? []);
}

async function restore(): Promise<void> {
  const owner = session.value;
  if (!owner || loading.value || restoring.value) return;
  const token = requests.begin();
  restoring.value = true;
  notice.value = null;
  try {
    const snapshot = await getAssistantSession(owner);
    if (!requests.isCurrent(token) || snapshot.conversationId !== owner.conversationId ||
      snapshot.stateVersion < owner.stateVersion) return;
    session.value = { ...owner, stateVersion: snapshot.stateVersion };
    closed.value = snapshot.state === 'CLOSED';
    messages.value = snapshot.messages;
    setLatest(snapshot.latest, snapshot.clarifications);
    if (closed.value) pending.value = null;
    save();
    scrollToEnd();
  } catch (error) {
    if (!requests.isCurrent(token)) return;
    if (!handleMissingConversation(error, true)) showError(error);
  } finally {
    if (requests.isCurrent(token)) restoring.value = false;
  }
}

function requestId(): string {
  // Client retry identifier only. The owner credential is generated on the server.
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (character) => {
    const digit = Math.floor(Math.random() * 16);
    return (character === 'x' ? digit : (digit & 3) | 8).toString(16);
  });
}

function sample(): void {
  draft.value = '从图书馆去天府广场，少走路';
}

function sendMessage(text: string): void {
  const message = text.trim();
  if (!message || loading.value || restoring.value || closed.value) return;
  draft.value = '';
  submit({ message }, message);
}

function confirmCandidate(item: Clarification, candidate: AssistantCandidate): void {
  if (!item.confirmationId || !candidate.routingReady || pending.value || loading.value || restoring.value) return;
  submit({ confirmation: { confirmationId: item.confirmationId, placeId: candidate.id } }, `选择 ${candidate.name}`);
}

function submit(input: AssistantInput, displayText: string): void {
  pending.value = {
    clientRequestId: requestId(), stateVersion: session.value?.stateVersion ?? 0, input, displayText
  };
  save();
  void sendPending();
}

async function sendPending(): Promise<void> {
  const submission = pending.value;
  if (!submission || loading.value || restoring.value) return;
  const token = requests.begin();
  loading.value = true;
  notice.value = null;
  if (historyJourney.value) returnToLatest();
  scrollToEnd();
  let refresh = false;
  try {
    let owner = session.value;
    if (!owner) {
      owner = await createAssistantSession();
      if (!requests.isCurrent(token)) {
        void closeAssistantSession(owner).catch(() => {});
        return;
      }
      session.value = owner;
      save();
    }
    const reply = await sendAssistantTurn(owner, submission);
    if (!requests.isCurrent(token) || pending.value?.clientRequestId !== submission.clientRequestId ||
      reply.conversationId !== owner.conversationId) return;

    pending.value = null;
    if (reply.stateVersion >= owner.stateVersion && reply.stateVersion !== latest.value?.stateVersion) {
      session.value = { ...owner, stateVersion: reply.stateVersion };
      appendMessages(submission.displayText, reply);
      setLatest(reply, reply.clarifications);
    } else if (!latest.value) {
      refresh = true;
    }
    save();
  } catch (error) {
    if (!requests.isCurrent(token) || handleMissingConversation(error)) return;
    if (error instanceof ApiRequestError && !error.retryable) {
      pending.value = null;
      refresh = error.code === 'STATE_VERSION_CONFLICT' || error.code === 'CONVERSATION_CLOSED';
      save();
    }
    showError(error);
  } finally {
    if (requests.isCurrent(token)) {
      loading.value = false;
      if (refresh) {
        const hint = notice.value;
        const restoreTask = restore();
        const refreshToken = requests.current;
        await restoreTask;
        if (requests.isCurrent(refreshToken) && session.value && notice.value === null) notice.value = hint;
      }
      scrollToEnd();
    }
  }
}

function appendMessages(userText: string, reply: AssistantReply): void {
  const base = messages.value[messages.value.length - 1]?.sequence ?? 0;
  const createdAt = new Date().toISOString();
  const newMessages: AssistantMessage[] = [
    { sequence: base + 1, role: 'USER', content: userText, createdAt, journeyRequestId: null, status: null },
    { sequence: base + 2, role: 'ASSISTANT', content: reply.answer, createdAt,
      journeyRequestId: reply.journeyRequestId, status: reply.status }
  ];
  messages.value = [...messages.value, ...newMessages].slice(-40);
}

function resetConversation(): void {
  requests.invalidate();
  session.value = null;
  pending.value = null;
  messages.value = [];
  notice.value = null;
  restoring.value = false;
  loading.value = false;
  closed.value = false;
  setLatest(null, []);
  save();
}

function newConversation(): void {
  const previous = session.value;
  resetConversation();
  draft.value = '';
  if (previous) void closeAssistantSession(previous).catch(() => {});
}

function retry(): void {
  if (pending.value) {
    void sendPending();
  } else {
    void restore();
  }
}

async function openHistory(journeyId: string): Promise<void> {
  const owner = session.value;
  if (!owner || loading.value || restoring.value) return;
  const token = requests.begin();
  loading.value = true;
  notice.value = null;
  try {
    const result = await getAssistantJourney(owner, journeyId);
    if (!requests.isCurrent(token) || result.requestId !== journeyId) return;
    historyJourney.value = result;
    selectedPlanId.value = defaultSelectedPlanId(result.plans);
  } catch (error) {
    if (requests.isCurrent(token) && !handleMissingConversation(error)) showError(error);
  } finally {
    if (requests.isCurrent(token)) {
      loading.value = false;
      scrollToEnd();
    }
  }
}

function returnToLatest(): void {
  historyJourney.value = null;
  selectedPlanId.value = defaultSelectedPlanId(latest.value?.journey?.plans ?? []);
}

function selectPlan(planId: string): void {
  const id = selectExistingPlanId(visibleJourney.value?.plans ?? [], planId);
  if (id) selectedPlanId.value = id;
}

onShow(() => { void restore(); });
onUnload(() => requests.invalidate());
</script>

<style scoped>
.chat {
  min-height: 100vh;
  padding: 18rpx 24rpx 30rpx;
  box-sizing: border-box;
  background: #f5f7fa;
  color: #132b43;
}
.toolbar, .composer, .route-head, .history { display: flex; align-items: center; gap: 14rpx; }
.toolbar {
  position: sticky;
  top: 0;
  z-index: 3;
  justify-content: space-between;
  margin-bottom: 20rpx;
  padding: 6rpx 0;
  background: #f5f7fa;
}
.plain-button, .outline, .candidate, .send {
  margin: 0;
  padding: 8rpx 20rpx;
  font-size: 26rpx;
  line-height: 1.5;
  border-radius: 12rpx;
}
.plain-button { background: transparent; color: #225a84; }
.plain-button::after, .send::after { border: none; }
.welcome, .message, .notice, .route, .choices {
  margin-bottom: 18rpx;
  padding: 20rpx;
  border-radius: 16rpx;
  background: #fff;
}
.welcome .outline { margin-top: 18rpx; }
.message { width: fit-content; max-width: 92%; box-sizing: border-box; }
.message.user { margin-left: auto; background: #dceefb; }
.message-text { display: block; white-space: pre-wrap; overflow-wrap: break-word; }
.muted { display: block; font-size: 24rpx; color: #5b6d80; }
.outline, .candidate { background: #eef5fb; color: #153e5c; text-align: left; }
.candidate { width: 100%; margin-top: 10rpx; }
.notice .outline, #chat-pending .outline { margin-top: 12rpx; }
.route-head { justify-content: space-between; flex-wrap: wrap; }
.composer {
  position: sticky;
  bottom: 0;
  z-index: 2;
  padding: 16rpx 0 calc(12rpx + env(safe-area-inset-bottom));
  background: #f5f7fa;
}
.composer textarea {
  flex: 1;
  min-height: 44rpx;
  max-height: 160rpx;
  padding: 14rpx;
  box-sizing: border-box;
  border: 1px solid #d5e0eb;
  border-radius: 12rpx;
  background: #fff;
  font-size: 28rpx;
}
.send { background: #133e65; color: #fff; }
</style>
