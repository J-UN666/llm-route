/**
 * 普通行程查询的目标 DTO 与页面状态。
 *
 * 来源：`../按周Agent任务与验收.md` W1-08 任务卡的目标契约（W1-07 先行落地前端）。
 * 当前 main 基线尚未提供 `POST /api/journeys`（W1-08 未实现），因此本文件中的
 * 响应类型是“目标 DTO”，页面按它渲染，联调时用标注来源的交互样例（契约 stub）验证；
 * W1-08/W1-09 落地后以 `docs/contracts/journey-api.md` 与真实响应为准对齐字段，
 * 字段缺失时保留 null，不伪造 0 值。
 */

/** 地点查询范围：起点只允许校园内部地点，终点只允许城市目的地。 */
export type PlaceScope = 'CAMPUS' | 'CITY';

/** 地点类型，与 W1-04 `GET /api/places` 返回的 kind 一致。 */
export type PlaceKind = 'CAMPUS' | 'GATE' | 'CITY';

/** 带坐标系的位置，字段与 W1-04 地点接口一致。 */
export interface GeoPosition {
  pointId: string;
  longitude: number | null;
  latitude: number | null;
  coordinateSystem: string;
  positionRole: string;
}

/** W1-04 `GET /api/places` 返回的候选地点。 */
export interface PlaceCandidate {
  id: string;
  name: string;
  kind: PlaceKind;
  category: string | null;
  aliases: string[];
  routingReady: boolean;
  unavailableReason: string | null;
  verificationStatus: string;
  displayPosition: GeoPosition | null;
  routingPosition: GeoPosition | null;
  dataVersion: string | null;
  sourceRefs: string[];
}

/** W1-04 `GET /api/places` 响应体。 */
export interface PlaceSearchResponse {
  items: PlaceCandidate[];
}

/** W1-09 统一的偏好枚举，提交时必须使用这里的 value。 */
export type JourneyPreference = 'RECOMMENDED' | 'LESS_WALKING' | 'FEWER_TRANSFERS';

export interface PreferenceOption {
  value: JourneyPreference;
  label: string;
  hint: string;
}

/** 偏好的唯一来源；页面与 W1-10 都从这里读取，避免各写一套中文字符串。 */
export const JOURNEY_PREFERENCES: readonly PreferenceOption[] = [
  { value: 'RECOMMENDED', label: '综合推荐', hint: '总耗时优先，兼顾换乘与步行' },
  { value: 'LESS_WALKING', label: '少步行', hint: '步行距离优先' },
  { value: 'FEWER_TRANSFERS', label: '少换乘', hint: '换乘次数优先' }
] as const;

export function preferenceLabel(preference: JourneyPreference): string {
  const found = JOURNEY_PREFERENCES.find((item) => item.value === preference);
  return found ? found.label : preference;
}

/** POST /api/journeys 请求体：departureTime 必须是带 +08:00 的 ISO 8601。 */
export interface JourneyRequest {
  originId: string;
  destinationId: string;
  departureTime: string;
  preference: JourneyPreference;
}

/** 行程接口的顶层状态；有效查询无方案时后端返回 200 + NO_ROUTE。 */
export type JourneyStatus = 'SUCCEEDED' | 'NO_ROUTE';

/** 行程段交通方式，允许后端扩展其它值。 */
export type JourneySegmentMode = 'CAMPUS_WALK' | 'WALK' | 'BUS' | 'METRO' | (string & {});

/** 行程段中的点（起点、终点、线路站点等）。 */
export interface JourneyPoint {
  pointId: string | null;
  name: string | null;
  longitude: number | null;
  latitude: number | null;
  coordinateSystem: string | null;
  positionRole?: string | null;
}

/** 行程段可用的几何线，坐标系统在字段中显式标注。 */
export interface JourneyGeometry {
  type: 'LineString' | (string & {});
  coordinates: Array<[number, number]> | null;
  coordinateSystem: string;
}

/**
 * 行程段。字段名来自 W1-08 任务卡的目标描述（序号/mode/说明/起终点/线路站点/
 * 距离/耗时/geometry/coordinateSystem）；W1-08 落地时若字段名变化，只改这里与渲染层。
 */
export interface JourneySegment {
  sequence: number;
  mode: JourneySegmentMode;
  description: string | null;
  fromPoint: JourneyPoint | null;
  toPoint: JourneyPoint | null;
  lineId: string | null;
  lineName: string | null;
  direction: string | null;
  boardingStation: string | null;
  alightingStation: string | null;
  distanceMeters: number | null;
  durationSeconds: number | null;
  geometry: JourneyGeometry | null;
  coordinateSystem: string | null;
}

/** W1-08 单条候选方案。缺失指标保持 null，页面显示“未提供”。 */
export interface JourneyPlan {
  planId: string;
  originId: string;
  destinationId: string;
  gateId: string | null;
  gateExitPoint: JourneyPoint | null;
  departureTime: string;
  cityDepartureTime: string | null;
  providerDepartureTime: string | null;
  roundingWaitSeconds: number | null;
  arrivalTime: string | null;
  totalDurationSeconds: number | null;
  totalWalkingMeters: number | null;
  transferCount: number | null;
  costYuan: number | null;
  segments: JourneySegment[];
  sources: string[];
  queriedAt: string | null;
  campusDataVersion: string | null;
}

/** POST /api/journeys 成功响应。 */
export interface JourneyResponse {
  requestId: string;
  status: JourneyStatus;
  plans: JourneyPlan[];
  queriedAt: string;
  campusDataVersion: string;
  provider: string;
  warnings?: string[];
}

/** 后端错误体（HTTP 4xx/5xx）。 */
export interface JourneyErrorBody {
  requestId: string | null;
  code: string;
  message: string;
  retryable: boolean | null;
}

/** 页面结果状态机；W1-10 消费 success/noRoute/error 与 plans/selectedPlanId。 */
export type JourneyViewStatus = 'idle' | 'loading' | 'success' | 'noRoute' | 'error';

/** 已本地化的错误视图，保留后端 code 与 requestId 供排查。 */
export interface JourneyUiError {
  code: string;
  hint: string;
  detail: string | null;
  retryable: boolean;
  requestId: string | null;
  httpStatus: number | null;
}

/**
 * 统一结果状态（W1-10 交接点）。
 * - requestToken：客户端请求标识，用于丢弃过期响应；
 * - responseRequestId：后端 requestId，展示与排查用；
 * - plans/selectedPlanId：成功后默认选第一条；失败/无方案时清空。
 */
export interface JourneyQueryState {
  requestToken: number;
  responseRequestId: string | null;
  status: JourneyViewStatus;
  lastRequest: JourneyRequest | null;
  submittedAt: string | null;
  response: JourneyResponse | null;
  plans: JourneyPlan[];
  selectedPlanId: string | null;
  error: JourneyUiError | null;
}

export function createIdleJourneyState(): JourneyQueryState {
  return {
    requestToken: 0,
    responseRequestId: null,
    status: 'idle',
    lastRequest: null,
    submittedAt: null,
    response: null,
    plans: [],
    selectedPlanId: null,
    error: null
  };
}
