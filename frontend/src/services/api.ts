/**
 * 小程序 API 访问层。
 *
 * 地点接口：W1-04 `GET /api/places`（已可用）。
 * 行程接口：W1-08 目标契约 `POST /api/journeys`（当前基线尚未实现，前端按目标 DTO 接入，
 * W1-08 落地后以此文件为唯一适配点对齐真实响应）。
 *
 * 约定：
 * - 基址来自 `VITE_API_BASE_URL`，缺省回退到本机 8080，避免未配置时静默请求错误主机；
 * - 所有时间以 `Asia/Shanghai`（+08:00）生成与解析，不使用设备本地时区；
 * - 网络/HTTP 错误统一抛 `ApiRequestError`，页面用 `toJourneyUiError` 转成可理解提示；
 * - 不做全局 loading/自动重试，请求标识与防抖由调用方（PlacePicker/页面）控制。
 */

import type {
  JourneyErrorBody,
  JourneyRequest,
  JourneyResponse,
  JourneyUiError,
  PlaceCandidate,
  PlaceScope,
  PlaceSearchResponse
} from '@/types/journey';

const FALLBACK_API_BASE_URL = 'http://127.0.0.1:8080';
const REQUEST_TIMEOUT_MS = 15000;
const BEIJING_OFFSET_MINUTES = 8 * 60;

function normalizeBaseUrl(value: unknown): string {
  return typeof value === 'string' ? value.trim().replace(/\/+$/, '') : '';
}

const envBaseUrl = normalizeBaseUrl(import.meta.env.VITE_API_BASE_URL);

/** 当前构建使用的 API 基址（已去除末尾斜杠）。 */
export const API_BASE_URL = envBaseUrl || FALLBACK_API_BASE_URL;

/** 基址来源：环境变量或回退默认值，页脚/README 排查配置时使用。 */
export const API_BASE_URL_SOURCE: 'env' | 'default' = envBaseUrl ? 'env' : 'default';

/** 后端错误 code 到可理解中文提示的映射；未知 code 回退服务端 message。 */
const ERROR_HINTS: Record<string, string> = {
  NETWORK_ERROR: '无法连接行程服务，请确认后端已启动、手机与电脑在同一网络，并检查 API 地址配置。',
  NETWORK_TIMEOUT: '行程服务响应超时，请稍后重试；高峰期查询可能需要更久。',
  INVALID_PARAMETER: '查询参数不完整或格式不正确，请检查起点、终点和出发时间。',
  INVALID_REQUEST: '查询参数不完整或格式不正确，请检查起点、终点和出发时间。',
  INVALID_SCOPE: '地点查询范围不合法，请刷新页面后重试。',
  PLACE_NOT_FOUND: '所选地点不存在或已被移除，请重新搜索并确认地点。',
  PLACE_NOT_ROUTABLE: '所选地点尚未通过可用性核实，请换一个已可用地点。',
  PLACE_UNAVAILABLE: '所选地点尚未通过可用性核实，请换一个已可用地点。',
  CAMPUS_CONNECTOR_UNAVAILABLE: '校园接驳数据暂不可用，请稍后重试或联系管理员。',
  COORDINATE_SYSTEM_MISMATCH: '地点坐标系统尚未确认，暂不能用于算路。',
  DEPARTURE_TIME_INVALID: '出发时间不合法，请选择晚于当前时间的北京时间。',
  PLACE_DATA_LOAD_FAILED: '地点数据加载失败，请稍后重试。',
  PROVIDER_ERROR: '城市公共交通服务暂时不可用，请稍后重试。',
  PROVIDER_TIMEOUT: '城市公共交通服务响应超时，请稍后重试。',
  PROVIDER_RATE_LIMITED: '城市公共交通服务查询过于频繁，请稍后重试。',
  RESPONSE_STRUCTURE_ERROR: '行程服务返回的数据结构不完整，请联系管理员排查。',
  SERVER_ERROR: '行程服务内部错误，请稍后重试。',
  UNKNOWN_ERROR: '查询失败，请稍后重试。'
};

const HTTP_ERROR_FALLBACK: Record<number, { code: string; retryable: boolean; message: string }> = {
  400: { code: 'INVALID_PARAMETER', retryable: false, message: '请求参数不合法' },
  404: { code: 'PLACE_NOT_FOUND', retryable: false, message: '未找到地点' },
  422: { code: 'PLACE_NOT_ROUTABLE', retryable: false, message: '地点或时间暂不可用于算路' },
  500: { code: 'SERVER_ERROR', retryable: true, message: '行程服务内部错误' },
  502: { code: 'PROVIDER_ERROR', retryable: true, message: '城市公共交通服务暂时不可用' },
  504: { code: 'PROVIDER_TIMEOUT', retryable: true, message: '城市公共交通服务响应超时' }
};

/** 统一的网络/接口错误，保留后端 code、HTTP 状态、requestId 与可重试标记。 */
export class ApiRequestError extends Error {
  readonly code: string;
  readonly httpStatus: number | null;
  readonly retryable: boolean;
  readonly requestId: string | null;
  readonly serverMessage: string | null;

  constructor(input: {
    code: string;
    message: string;
    httpStatus?: number | null;
    retryable?: boolean;
    requestId?: string | null;
    serverMessage?: string | null;
  }) {
    super(input.message);
    this.name = 'ApiRequestError';
    this.code = input.code;
    this.httpStatus = input.httpStatus ?? null;
    this.retryable = input.retryable ?? false;
    this.requestId = input.requestId ?? null;
    this.serverMessage = input.serverMessage ?? null;
  }
}

function pickErrorBody(data: unknown): JourneyErrorBody | null {
  if (!data || typeof data !== 'object') return null;
  const record = data as Record<string, unknown>;
  if (typeof record.code !== 'string' || !record.code) return null;
  return {
    requestId: typeof record.requestId === 'string' ? record.requestId : null,
    code: record.code,
    message: typeof record.message === 'string' ? record.message : '',
    retryable: typeof record.retryable === 'boolean' ? record.retryable : null
  };
}

function createHttpError(statusCode: number, data: unknown): ApiRequestError {
  const body = pickErrorBody(data);
  const fallback = HTTP_ERROR_FALLBACK[statusCode] || {
    code: 'SERVER_ERROR',
    retryable: false,
    message: `HTTP ${statusCode}`
  };
  return new ApiRequestError({
    code: body ? body.code : fallback.code,
    message: body && body.message ? body.message : fallback.message,
    httpStatus: statusCode,
    retryable: body && body.retryable !== null ? body.retryable : fallback.retryable,
    requestId: body ? body.requestId : null,
    serverMessage: body && body.message ? body.message : null
  });
}

function createNetworkError(errMsg: string): ApiRequestError {
  const isTimeout = /timeout/i.test(errMsg);
  return new ApiRequestError({
    code: isTimeout ? 'NETWORK_TIMEOUT' : 'NETWORK_ERROR',
    message: errMsg,
    retryable: true,
    serverMessage: errMsg
  });
}

interface RawResponse {
  statusCode: number;
  data: unknown;
}

interface RequestConfig {
  path: string;
  method: 'GET' | 'POST';
  data?: string | Record<string, unknown> | ArrayBuffer;
}

function requestRaw(config: RequestConfig): Promise<RawResponse> {
  return new Promise<RawResponse>((resolve, reject) => {
    const requestOptions: UniApp.RequestOptions = {
      url: `${API_BASE_URL}${config.path}`,
      method: config.method,
      timeout: REQUEST_TIMEOUT_MS,
      success: (response) => {
        resolve({ statusCode: response.statusCode, data: response.data });
      },
      fail: (error) => {
        reject(createNetworkError((error && error.errMsg) || 'request:fail'));
      }
    };
    if (config.method === 'POST') {
      requestOptions.header = { 'Content-Type': 'application/json' };
      requestOptions.data = config.data;
    }
    uni.request(requestOptions);
  });
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object';
}

/** GET /api/places：关键词 + 范围查询地点候选（W1-04）。 */
export async function searchPlaces(keyword: string, scope: PlaceScope): Promise<PlaceCandidate[]> {
  const trimmed = keyword.trim();
  if (!trimmed) return [];
  const query = `keyword=${encodeURIComponent(trimmed)}&scope=${encodeURIComponent(scope)}`;
  const response = await requestRaw({ path: `/api/places?${query}`, method: 'GET' });
  if (response.statusCode !== 200) {
    throw createHttpError(response.statusCode, response.data);
  }
  if (!isRecord(response.data) || !Array.isArray((response.data as unknown as PlaceSearchResponse).items)) {
    throw new ApiRequestError({
      code: 'RESPONSE_STRUCTURE_ERROR',
      message: '地点接口响应缺少 items 数组',
      retryable: true
    });
  }
  const items = (response.data as unknown as PlaceSearchResponse).items;
  return items.filter((item): item is PlaceCandidate => {
    return isRecord(item) && typeof item.id === 'string' && typeof item.name === 'string';
  });
}

/** POST /api/journeys：普通行程查询（W1-08 目标契约）。 */
export async function planJourney(request: JourneyRequest): Promise<JourneyResponse> {
  const requestBody: Record<string, unknown> = {
    originId: request.originId,
    destinationId: request.destinationId,
    departureTime: request.departureTime,
    preference: request.preference
  };
  const response = await requestRaw({
    path: '/api/journeys',
    method: 'POST',
    data: requestBody
  });
  if (response.statusCode !== 200) {
    throw createHttpError(response.statusCode, response.data);
  }
  if (!isRecord(response.data)) {
    throw new ApiRequestError({
      code: 'RESPONSE_STRUCTURE_ERROR',
      message: '行程接口响应为空或不是 JSON 对象',
      retryable: true
    });
  }
  const body = response.data as unknown as JourneyResponse;
  if ((body.status !== 'SUCCEEDED' && body.status !== 'NO_ROUTE') || !Array.isArray(body.plans)) {
    throw new ApiRequestError({
      code: 'RESPONSE_STRUCTURE_ERROR',
      message: '行程接口响应缺少合法的 status/plans',
      retryable: true
    });
  }
  return body;
}

/** 把任意异常转为页面可直接展示的错误视图。 */
export function toJourneyUiError(error: unknown): JourneyUiError {
  if (error instanceof ApiRequestError) {
    const hint =
      ERROR_HINTS[error.code] ||
      (error.serverMessage ? `查询失败：${error.serverMessage}` : ERROR_HINTS.UNKNOWN_ERROR);
    return {
      code: error.code,
      hint,
      detail: error.serverMessage,
      retryable: error.retryable,
      requestId: error.requestId,
      httpStatus: error.httpStatus
    };
  }
  const detail = error instanceof Error ? error.message : String(error);
  return {
    code: 'UNKNOWN_ERROR',
    hint: ERROR_HINTS.UNKNOWN_ERROR,
    detail,
    retryable: true,
    requestId: null,
    httpStatus: null
  };
}

/* ------------------------------------------------------------------ */
/* 请求协调：防抖与“只看最新一次”的请求标识                                    */
/* ------------------------------------------------------------------ */

/**
 * 递增请求令牌。发起请求前 `begin()`，响应到达时 `isCurrent(token)` 为 false
 * 就丢弃该响应；条件修改时 `invalidate()` 让在途响应全部过期。
 */
export class LatestRequestTracker {
  private latestToken = 0;

  begin(): number {
    this.latestToken += 1;
    return this.latestToken;
  }

  isCurrent(token: number): boolean {
    return token === this.latestToken;
  }

  invalidate(): number {
    this.latestToken += 1;
    return this.latestToken;
  }

  get current(): number {
    return this.latestToken;
  }
}

/** 300ms 输入防抖；组件卸载时可 cancel。 */
export interface DebouncedTask {
  schedule(task: () => void): void;
  cancel(): void;
}

export function createDebouncedTask(delayMs: number): DebouncedTask {
  let timer: ReturnType<typeof setTimeout> | null = null;
  return {
    schedule(task: () => void) {
      if (timer !== null) clearTimeout(timer);
      timer = setTimeout(() => {
        timer = null;
        task();
      }, delayMs);
    },
    cancel() {
      if (timer !== null) clearTimeout(timer);
      timer = null;
    }
  };
}

/* ------------------------------------------------------------------ */
/* 北京时间工具：请求生成 + 展示格式化                                       */
/* ------------------------------------------------------------------ */

function pad2(value: number): string {
  return value < 10 ? `0${value}` : `${value}`;
}

function isValidCalendarDate(year: number, month: number, day: number): boolean {
  if (month < 1 || month > 12 || day < 1 || day > 31) return false;
  const utc = Date.UTC(year, month - 1, day);
  const check = new Date(utc);
  return (
    check.getUTCFullYear() === year && check.getUTCMonth() === month - 1 && check.getUTCDate() === day
  );
}

/**
 * 由日期输入（YYYY-MM-DD）和时间输入（HH:mm）生成带 +08:00 的 ISO 8601。
 * 非法日期/时间返回 null，由调用方定位到对应输入提示，而不是静默改值。
 */
export function buildDepartureTime(dateText: string, timeText: string): string | null {
  const dateMatch = /^(\d{4})-(\d{2})-(\d{2})$/.exec(dateText.trim());
  const timeMatch = /^(\d{2}):(\d{2})$/.exec(timeText.trim());
  if (!dateMatch || !timeMatch) return null;
  const year = Number(dateMatch[1]);
  const month = Number(dateMatch[2]);
  const day = Number(dateMatch[3]);
  const hour = Number(timeMatch[1]);
  const minute = Number(timeMatch[2]);
  if (!isValidCalendarDate(year, month, day) || hour > 23 || minute > 59) return null;
  const date = `${dateMatch[1]}-${dateMatch[2]}-${dateMatch[3]}`;
  const time = `${timeMatch[1]}:${timeMatch[2]}`;
  return `${date}T${time}:00+08:00`;
}

export interface BeijingNow {
  date: string;
  time: string;
  /** 下一分钟（避免提交时刚好成为过去时刻），带 +08:00。 */
  iso: string;
}

/** 取北京时间“下一分钟”，供“现在出发”快捷填充。 */
export function getBeijingNow(now: Date = new Date()): BeijingNow {
  const shifted = new Date(now.getTime() + BEIJING_OFFSET_MINUTES * 60 * 1000 + 60 * 1000);
  const date = `${shifted.getUTCFullYear()}-${pad2(shifted.getUTCMonth() + 1)}-${pad2(shifted.getUTCDate())}`;
  const time = `${pad2(shifted.getUTCHours())}:${pad2(shifted.getUTCMinutes())}`;
  return { date, time, iso: `${date}T${time}:00+08:00` };
}

const ISO_WITH_OFFSET_RE =
  /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2}))?(?:\.(\d{1,3}))?(Z|[+-]\d{2}:\d{2})$/;

function parseIsoToEpochMs(iso: string): number | null {
  const match = ISO_WITH_OFFSET_RE.exec(iso.trim());
  if (!match) return null;
  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  const hour = Number(match[4]);
  const minute = Number(match[5]);
  const second = match[6] ? Number(match[6]) : 0;
  if (!isValidCalendarDate(year, month, day) || hour > 23 || minute > 59 || second > 59) return null;
  let offsetMinutes = 0;
  if (match[8] !== 'Z') {
    const sign = match[8][0] === '-' ? -1 : 1;
    const offsetHour = Number(match[8].slice(1, 3));
    const offsetMinute = Number(match[8].slice(4, 6));
    offsetMinutes = sign * (offsetHour * 60 + offsetMinute);
  }
  return Date.UTC(year, month - 1, day, hour, minute, second) - offsetMinutes * 60 * 1000;
}

/** 把后端 ISO 时间按北京时间格式化；无法解析时原样显示，不隐藏数据。 */
export function formatBeijingDateTime(iso: string | null | undefined, fallback = '时间未提供'): string {
  if (!iso) return fallback;
  const epochMs = parseIsoToEpochMs(iso);
  if (epochMs === null) return iso;
  const shifted = new Date(epochMs + BEIJING_OFFSET_MINUTES * 60 * 1000);
  return `${shifted.getUTCFullYear()}-${pad2(shifted.getUTCMonth() + 1)}-${pad2(
    shifted.getUTCDate()
  )} ${pad2(shifted.getUTCHours())}:${pad2(shifted.getUTCMinutes())}`;
}
