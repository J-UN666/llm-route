package cn.swjtu.zhixing.assistant;

import static cn.swjtu.zhixing.assistant.AssistantConversation.*;

import cn.swjtu.zhixing.assistant.AssistantConversationStore.Owner;
import cn.swjtu.zhixing.assistant.AssistantConversationStore.Start;
import cn.swjtu.zhixing.journey.JourneyResponse;
import cn.swjtu.zhixing.serviceconfig.PersonalServices;
import jakarta.servlet.http.HttpServletRequest;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/assistant")
class AssistantController {
    static final String OWNER_HEADER = "X-Conversation-Token";
    private static final Set<String> QUERY_FIELDS =
            Set.of("message", "conversationId", "clientRequestId", "stateVersion", "confirmation");

    private final AssistantPlanner planner;
    private final AssistantConversationStore conversations;
    private final AssistantTools tools;
    private final PersonalServices personalServices;

    AssistantController(AssistantPlanner planner, AssistantConversationStore conversations, AssistantTools tools,
                        PersonalServices personalServices) {
        this.planner = planner;
        this.conversations = conversations;
        this.tools = tools;
        this.personalServices = personalServices;
    }

    @PostMapping(path = "/query", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Reply> query(@RequestBody String raw,
            @RequestHeader(name = OWNER_HEADER, required = false) String token,
            @RequestHeader(name = PersonalServices.OWNER_HEADER, required = false) String serviceToken,
            HttpServletRequest request) {
        long startedAt = System.nanoTime();
        String requestId = AssistantIds.requestId();
        Query query = parseQuery(raw, requestId);
        var credentials = personalServices.forRequest(serviceToken, request);
        if (query.conversationId() == null) {
            return uncached(planner.run(query, State.EMPTY, requestId, AssistantPlanner.BUDGET_MILLIS, credentials).reply());
        }
        Owner owner = Owner.from(query.conversationId(), token, requestId);
        Start started = conversations.start(owner, query, requestId);
        if (started.replay() != null) {
            return uncached(started.replay());
        }
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        long remaining = AssistantPlanner.BUDGET_MILLIS - elapsed
                - TimeUnit.SECONDS.toMillis(AssistantConversationStore.TRANSACTION_SECONDS);
        AssistantPlanner.Result result = planner.run(query, started.state(), requestId, remaining, credentials);
        return uncached(conversations.accept(owner, query, started, result));
    }

    @PostMapping("/conversations")
    ResponseEntity<Created> create() {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(conversations.create());
    }

    @GetMapping("/conversations/{id}")
    ResponseEntity<Snapshot> restore(@PathVariable String id,
            @RequestHeader(name = OWNER_HEADER, required = false) String token) {
        String requestId = AssistantIds.requestId();
        return uncached(conversations.restore(owner(id, token, requestId), requestId));
    }

    @PostMapping("/conversations/{id}/close")
    ResponseEntity<Void> close(@PathVariable String id,
            @RequestHeader(name = OWNER_HEADER, required = false) String token) {
        String requestId = AssistantIds.requestId();
        conversations.close(owner(id, token, requestId), requestId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @GetMapping("/conversations/{id}/journeys/{journeyId}")
    ResponseEntity<JourneyResponse> journey(@PathVariable String id, @PathVariable String journeyId,
            @RequestHeader(name = OWNER_HEADER, required = false) String token) {
        String requestId = AssistantIds.requestId();
        return uncached(conversations.journey(owner(id, token, requestId), journeyId, requestId));
    }

    private Query parseQuery(String raw, String requestId) {
        try {
            JsonNode node = tools.parseObject(raw);
            if (node.properties().stream().anyMatch(field -> !QUERY_FIELDS.contains(field.getKey()))) {
                throw new IllegalArgumentException();
            }
            String message = optionalText(node, "message", 500);
            UUID conversationId = optionalUuid(node, "conversationId");
            UUID clientRequestId = optionalUuid(node, "clientRequestId");
            JsonNode versionValue = node.get("stateVersion");
            Long version = null;
            if (versionValue != null) {
                if (!versionValue.isIntegralNumber() || !versionValue.canConvertToLong()
                        || versionValue.longValue() < 0 || versionValue.longValue() > 9_007_199_254_740_991L) {
                    throw new IllegalArgumentException();
                }
                version = versionValue.longValue();
            }

            Confirmation confirmation = confirmation(node.get("confirmation"));
            if ((message == null) == (confirmation == null)
                    || (conversationId == null && (clientRequestId != null || version != null || confirmation != null))
                    || (conversationId != null && (clientRequestId == null || version == null))) {
                throw new IllegalArgumentException();
            }
            return new Query(message, conversationId, clientRequestId, version, confirmation);
        } catch (IOException | IllegalArgumentException ex) {
            throw invalidRequest(requestId);
        }
    }

    private static Confirmation confirmation(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (!node.isObject() || node.size() != 2) {
            throw new IllegalArgumentException();
        }
        UUID id = optionalUuid(node, "confirmationId");
        String placeId = optionalText(node, "placeId", 96);
        if (id == null || !AssistantTools.validId(placeId)) {
            throw new IllegalArgumentException();
        }
        return new Confirmation(id, placeId);
    }

    private static UUID optionalUuid(JsonNode node, String key) {
        String raw = optionalText(node, key, 36);
        return raw == null ? null : uuid(raw);
    }

    private static UUID uuid(String raw) {
        UUID id = UUID.fromString(raw);
        if (!id.toString().equals(raw) || id.version() != 4 || id.variant() != 2) {
            throw new IllegalArgumentException();
        }
        return id;
    }

    private static String optionalText(JsonNode node, String key, int max) {
        JsonNode value = node.get(key);
        if (value == null) {
            return null;
        }
        if (!value.isTextual() || value.textValue().isBlank() || value.textValue().length() > max) {
            throw new IllegalArgumentException();
        }
        return value.textValue().strip();
    }

    private static Owner owner(String id, String token, String requestId) {
        try {
            return Owner.from(uuid(id), token, requestId);
        } catch (IllegalArgumentException ex) {
            throw invalidRequest(requestId);
        }
    }

    private static AssistantApiException invalidRequest(String requestId) {
        return new AssistantApiException(HttpStatus.BAD_REQUEST, AssistantErrorCodes.INVALID_PARAMETER,
                "消息、会话版本或候选确认参数不符合约定", false, requestId);
    }

    private static <T> ResponseEntity<T> uncached(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
