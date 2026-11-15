package cn.swjtu.zhixing.campus;

/** departureTime 缺失或不是合法的 ISO-8601 带时区时间（HTTP 400 / INVALID_DEPARTURE_TIME）。 */
public class InvalidDepartureTimeException extends RuntimeException {

    public InvalidDepartureTimeException(String message) {
        super(message);
    }
}
