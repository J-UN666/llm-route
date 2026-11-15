package cn.swjtu.zhixing.campus;

/** 缺少或为空的 originId（HTTP 400 / INVALID_ORIGIN_ID）。 */
public class InvalidOriginIdException extends RuntimeException {

    public InvalidOriginIdException(String message) {
        super(message);
    }
}
