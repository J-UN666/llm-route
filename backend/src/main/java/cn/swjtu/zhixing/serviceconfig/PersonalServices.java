package cn.swjtu.zhixing.serviceconfig;

import jakarta.servlet.http.HttpServletRequest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** An anonymous owner token references an ephemeral, server-side service configuration. */
@Component
public class PersonalServices {
    public static final String OWNER_HEADER = "X-Service-Token";
    private static final Duration LIFETIME = Duration.ofMinutes(30);
    private static final int MAX_SESSIONS = 1024;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, Session> sessions = new HashMap<>();

    public record Credentials(String amapKey, String modelKey, String model) {
        @Override
        public String toString() {
            return "Personal services";
        }
    }

    record Session(Instant expiresAt, Credentials credentials) {}

    record Created(String ownerToken, Instant expiresAt) {}

    synchronized Created create() {
        purge();
        if (sessions.size() >= MAX_SESSIONS) {
            throw new ServiceConfigException(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_CONFIG_BUSY",
                    "个人配置服务繁忙，请稍后重试", true);
        }
        byte[] entropy = new byte[32];
        RANDOM.nextBytes(entropy);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
        Instant expiresAt = Instant.now().plus(LIFETIME);
        sessions.put(token, new Session(expiresAt, new Credentials(null, null, null)));
        return new Created(token, expiresAt);
    }

    public Credentials forRequest(String token, HttpServletRequest request) {
        if (token == null) {
            return null;
        }
        requireHttps(request);
        return require(token).credentials();
    }

    synchronized Session require(String token) {
        Session session = token != null && token.matches("[A-Za-z0-9_-]{43}") ? sessions.get(token) : null;
        if (session == null || !session.expiresAt().isAfter(Instant.now())) {
            if (session != null) {
                sessions.remove(token);
            }
            throw new ServiceConfigException(HttpStatus.NOT_FOUND, "SERVICE_CONFIG_EXPIRED",
                    "个人配置已失效，请在设置重新验证 Key，或选择默认服务", false);
        }
        return session;
    }

    synchronized Session replace(String token, Session before, Credentials credentials) {
        Session current = require(token);
        if (current != before) {
            throw new ServiceConfigException(HttpStatus.CONFLICT, "SERVICE_CONFIG_CHANGED",
                    "服务配置已更新，请刷新设置后再试", true);
        }
        Session updated = new Session(current.expiresAt(), credentials);
        sessions.put(token, updated);
        return updated;
    }

    synchronized void revoke(String token) {
        require(token);
        sessions.remove(token);
    }

    @Scheduled(fixedDelay = 30_000)
    synchronized void purge() {
        Instant now = Instant.now();
        sessions.values().removeIf(session -> !session.expiresAt().isAfter(now));
    }

    static void requireHttps(HttpServletRequest request) {
        if (!request.isSecure()) {
            throw new ServiceConfigException(HttpStatus.FORBIDDEN, "SERVICE_CONFIG_HTTPS_REQUIRED",
                    "个人 Key 设置和使用需要 HTTPS", false);
        }
    }
}
