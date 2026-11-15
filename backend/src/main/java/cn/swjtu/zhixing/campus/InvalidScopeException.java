package cn.swjtu.zhixing.campus;

/** 地点搜索 scope 不合法，对应 HTTP 400 / INVALID_SCOPE。 */
public class InvalidScopeException extends RuntimeException {

    private final String scope;

    public InvalidScopeException(String scope) {
        super("不支持的 scope: " + scope + "（可选 CAMPUS、GATE、CITY）");
        this.scope = scope;
    }

    public String scope() {
        return scope;
    }
}
