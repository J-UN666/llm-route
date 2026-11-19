package cn.swjtu.zhixing.assistant;

import static cn.swjtu.zhixing.assistant.AssistantConversation.*;

import cn.swjtu.zhixing.journey.JourneyResponse;
import cn.swjtu.zhixing.transit.TransitPreference;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Repository
class AssistantConversationStore {
    static final int TRANSACTION_SECONDS = 3;
    private static final String ACTIVE = " AND updated_at_utc >= UTC_TIMESTAMP(3) - INTERVAL 30 DAY";
    private static final SecureRandom RANDOM = new SecureRandom();

    record Owner(UUID id, byte[] hash) {
        static Owner from(UUID id, String token, String requestId) {
            if (token == null || !token.matches("[a-zA-Z0-9_-]{43}")) {
                throw notFound(requestId);
            }
            return new Owner(id, digest(token));
        }

        String key() {
            return id.toString();
        }
    }

    record Start(State state, Reply replay, byte[] requestHash) {}
    private record Pending(List<Clarification> items) {}
    private record SavedTurn(byte[] requestHash, Reply reply) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    AssistantConversationStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional(timeout = TRANSACTION_SECONDS)
    public Created create() {
        byte[] bits = new byte[32];
        RANDOM.nextBytes(bits);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bits);
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO conversation (conversation_id, owner_token_hash) VALUES (?, ?)",
                id.toString(), digest(token));
        return new Created(id, token, 0);
    }

    @Transactional(readOnly = true, timeout = TRANSACTION_SECONDS)
    public Start start(Owner owner, Query query, String requestId) {
        State state = load(owner, requestId);
        byte[] fingerprint = digest(json(query));
        Reply replay = replay(owner, query, fingerprint, requestId);
        if (replay == null) {
            if (state.closed()) {
                throw failure(HttpStatus.CONFLICT, "CONVERSATION_CLOSED", "会话已结束，请新建会话", requestId);
            }
            if (state.version() != query.stateVersion()) {
                throw conflict(requestId);
            }
        }
        return new Start(state, replay, fingerprint);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = TRANSACTION_SECONDS)
    public Reply accept(Owner owner, Query query, Start start, AssistantPlanner.Result result) {
        Reply reply = result.reply();
        State previous = start.state();
        Conditions conditions = reply.conditions();
        String state = reply.clarifications().isEmpty() ? "READY" : "AWAITING_CONFIRMATION";
        String pending = reply.clarifications().isEmpty() ? null : json(new Pending(reply.clarifications()));
        int updated = jdbc.update("""
                UPDATE conversation SET origin_place_id=?, destination_place_id=?, departure_at_utc=?,
                    preference=?, state=?, pending_confirmation=?, state_version=state_version+1,
                    next_message_seq=next_message_seq+2, updated_at_utc=UTC_TIMESTAMP(3)
                WHERE conversation_id=? AND owner_token_hash=? AND state_version=? AND state <> 'CLOSED'
                """ + ACTIVE, conditions.originId(), conditions.destinationId(), utc(conditions.departureTime()),
                conditions.preference().name(), state, pending, owner.key(), owner.hash(), previous.version());
        if (updated == 0) {
            load(owner, reply.requestId());
            Reply replay = replay(owner, query, start.requestHash(), reply.requestId());
            if (replay != null) {
                return replay;
            }
            throw conflict(reply.requestId());
        }

        append(owner, previous.nextSequence(), "USER", result.userText());
        append(owner, previous.nextSequence() + 1, "ASSISTANT", reply.answer());
        if (reply.journeyRequestId() != null) {
            saveJourney(owner, previous, result);
        }
        jdbc.update("""
                INSERT INTO conversation_turn (conversation_id, client_request_id, request_hash,
                    assistant_sequence, journey_request_id, response_snapshot)
                VALUES (?, ?, ?, ?, ?, ?)
                """, owner.key(), query.clientRequestId().toString(), start.requestHash(),
                previous.nextSequence() + 1, reply.journeyRequestId(), json(reply.withJourney(null)));
        return reply;
    }

    @Transactional(readOnly = true, timeout = TRANSACTION_SECONDS)
    public Snapshot restore(Owner owner, String requestId) {
        State state = load(owner, requestId);
        List<Message> descending = jdbc.query("""
                SELECT m.sequence_no, m.role, m.content, m.created_at_utc, t.journey_request_id,
                    JSON_UNQUOTE(JSON_EXTRACT(t.response_snapshot, '$.status')) AS response_status
                FROM conversation_message m
                LEFT JOIN conversation_turn t ON t.conversation_id=m.conversation_id
                    AND t.assistant_sequence=m.sequence_no
                WHERE m.conversation_id=? AND m.role IN ('USER', 'ASSISTANT')
                ORDER BY m.sequence_no DESC LIMIT 40
                """, (rs, index) -> {
            String status = rs.getString("response_status");
            return new Message(rs.getLong("sequence_no"), rs.getString("role"), rs.getString("content"),
                    beijing(rs, "created_at_utc"), rs.getString("journey_request_id"),
                    status == null ? null : Status.valueOf(status));
        }, owner.key());
        Reply latest = jdbc.query("""
                SELECT t.request_hash, t.response_snapshot, j.result_snapshot
                FROM conversation_turn t
                LEFT JOIN journey_query j ON j.request_id=t.journey_request_id
                WHERE t.conversation_id=? ORDER BY t.assistant_sequence DESC LIMIT 1
                """, this::mapTurn, owner.key()).stream().findFirst().map(SavedTurn::reply).orElse(null);
        return new Snapshot(owner.id(), state.version(), state.state(), state.conditions(),
                state.clarifications(), descending.reversed(), latest);
    }

    @Transactional(timeout = TRANSACTION_SECONDS)
    public void close(Owner owner, String requestId) {
        load(owner, requestId);
        jdbc.update("""
                UPDATE conversation SET state='CLOSED', pending_confirmation=NULL,
                    state_version=state_version+1, updated_at_utc=UTC_TIMESTAMP(3)
                WHERE conversation_id=? AND owner_token_hash=? AND state <> 'CLOSED'
                """ + ACTIVE, owner.key(), owner.hash());
    }

    @Transactional(readOnly = true, timeout = TRANSACTION_SECONDS)
    public JourneyResponse journey(Owner owner, String journeyId, String requestId) {
        load(owner, requestId);
        return jdbc.query("""
                SELECT j.result_snapshot
                FROM journey_query j JOIN conversation_turn t
                    ON t.journey_request_id=j.request_id AND t.conversation_id=j.conversation_id
                WHERE j.request_id=? AND j.conversation_id=? AND j.owner_token_hash=?
                  AND j.result_snapshot IS NOT NULL
                """, (rs, index) -> read(rs.getString("result_snapshot"), JourneyResponse.class),
                journeyId, owner.key(), owner.hash()).stream().findFirst()
                .orElseThrow(() -> failure(HttpStatus.NOT_FOUND, "JOURNEY_NOT_FOUND", "找不到此行程快照", requestId));
    }

    private State load(Owner owner, String requestId) {
        return jdbc.query("""
                SELECT state_version, next_message_seq, state, origin_place_id, destination_place_id,
                    departure_at_utc, preference, pending_confirmation
                FROM conversation WHERE conversation_id=? AND owner_token_hash=?
                """ + ACTIVE, this::mapState, owner.key(), owner.hash()).stream().findFirst()
                .orElseThrow(() -> notFound(requestId));
    }

    private State mapState(ResultSet rs, int index) throws SQLException {
        Pending pending = read(rs.getString("pending_confirmation"), Pending.class);
        return new State(rs.getLong("state_version"), rs.getLong("next_message_seq"),
                rs.getString("state"),
                new Conditions(rs.getString("origin_place_id"), rs.getString("destination_place_id"),
                        beijing(rs, "departure_at_utc"), TransitPreference.valueOf(rs.getString("preference"))),
                pending == null || pending.items() == null ? List.of() : pending.items());
    }

    private Reply replay(Owner owner, Query query, byte[] fingerprint, String requestId) {
        SavedTurn turn = jdbc.query("""
                SELECT t.request_hash, t.response_snapshot, j.result_snapshot
                FROM conversation_turn t
                LEFT JOIN journey_query j ON j.request_id=t.journey_request_id
                WHERE t.conversation_id=? AND t.client_request_id=?
                """, this::mapTurn, owner.key(), query.clientRequestId().toString()).stream().findFirst().orElse(null);
        if (turn == null) {
            return null;
        }
        if (!MessageDigest.isEqual(turn.requestHash(), fingerprint)) {
            throw failure(HttpStatus.CONFLICT, "REQUEST_ID_REUSED", "重试标识已用于另一条请求", requestId);
        }
        return turn.reply();
    }

    private SavedTurn mapTurn(ResultSet rs, int index) throws SQLException {
        Reply reply = read(rs.getString("response_snapshot"), Reply.class);
        return new SavedTurn(rs.getBytes("request_hash"), reply.withJourney(
                read(rs.getString("result_snapshot"), JourneyResponse.class)));
    }

    private void append(Owner owner, long sequence, String role, String content) {
        jdbc.update("""
                INSERT INTO conversation_message (message_id, conversation_id, sequence_no, role, content)
                VALUES (?, ?, ?, ?, ?)
                """, UUID.randomUUID().toString(), owner.key(), sequence, role, content);
    }

    private void saveJourney(Owner owner, State previous, AssistantPlanner.Result result) {
        Reply reply = result.reply();
        Conditions conditions = reply.conditions();
        JourneyResponse route = reply.journey();
        AssistantError error = reply.error();
        String campusVersion = result.campusDataVersion() == null ? "unavailable" : result.campusDataVersion();
        jdbc.update("""
                INSERT INTO journey_query (request_id, conversation_id, owner_token_hash, conversation_version,
                    entry_point, origin_place_id, destination_place_id, departure_at_utc, preference,
                    campus_data_version, provider, request_snapshot, status, result_snapshot,
                    error_code, error_message, started_at_utc, completed_at_utc)
                VALUES (?, ?, ?, ?, 'ASSISTANT', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(3))
                """, reply.journeyRequestId(), owner.key(), owner.hash(), previous.version(),
                conditions.originId(), conditions.destinationId(), utc(conditions.departureTime()),
                conditions.preference().name(), campusVersion,
                route == null || route.provider() == null ? "AMAP" : route.provider(),
                json(Map.of("conditions", conditions.journeyRequest())),
                route == null ? "FAILED" : route.status().name(), route == null ? null : json(route),
                error == null ? null : error.code(), error == null ? null : error.message(),
                utc(result.journeyStartedAt()));
    }

    private static LocalDateTime utc(OffsetDateTime time) {
        return time == null ? null : time.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    private static OffsetDateTime beijing(ResultSet rs, String column) throws SQLException {
        LocalDateTime value = rs.getObject(column, LocalDateTime.class);
        return value == null ? null : value.atOffset(ZoneOffset.UTC)
                .atZoneSameInstant(AssistantPlanner.BEIJING).toOffsetDateTime();
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("无法序列化会话数据", ex);
        }
    }

    private <T> T read(String json, Class<T> type) {
        if (json == null) {
            return null;
        }
        try {
            return mapper.readerFor(type).without(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
                    .readValue(json);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("无法读取会话快照", ex);
        }
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("缺少 SHA-256", ex);
        }
    }

    private static AssistantApiException notFound(String requestId) {
        return failure(HttpStatus.NOT_FOUND, "CONVERSATION_NOT_FOUND", "找不到此会话，请新建会话", requestId);
    }

    private static AssistantApiException conflict(String requestId) {
        return failure(HttpStatus.CONFLICT, "STATE_VERSION_CONFLICT", "会话已更新，请先恢复最新消息", requestId);
    }

    private static AssistantApiException failure(HttpStatus status, String code, String message, String requestId) {
        return new AssistantApiException(status, code, message, false, requestId);
    }
}
