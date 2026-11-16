package cn.swjtu.zhixing.journey;

/** 普通行程查询顶层状态。有效查询没有候选时仍返回 HTTP 200 与 {@link #NO_ROUTE}。 */
public enum JourneyStatus {
    SUCCEEDED,
    NO_ROUTE
}
