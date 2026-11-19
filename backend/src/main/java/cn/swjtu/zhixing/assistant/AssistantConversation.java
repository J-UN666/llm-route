package cn.swjtu.zhixing.assistant;

import cn.swjtu.zhixing.campus.PlaceKind;
import cn.swjtu.zhixing.journey.JourneyRequest;
import cn.swjtu.zhixing.journey.JourneyResponse;
import cn.swjtu.zhixing.transit.TransitPreference;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

final class AssistantConversation {

    enum Field {
        ORIGIN, DESTINATION, DEPARTURE_TIME, PREFERENCE
    }

    enum Status {
        SUCCEEDED, NO_ROUTE, NEEDS_CLARIFICATION, JOURNEY_FAILED
    }

    enum Reason {
        MISSING_ORIGIN, MISSING_DESTINATION, LOCATION_UNKNOWN, NO_MATCH, AMBIGUOUS,
        PLACE_UNAVAILABLE, DEPARTURE_REQUIRED, DEPARTURE_EXPIRED, PREFERENCE_UNCLEAR
    }

    record Conditions(String originId, String destinationId, OffsetDateTime departureTime,
                      TransitPreference preference) {
        static final Conditions EMPTY = new Conditions(null, null, null, TransitPreference.RECOMMENDED);

        JourneyRequest journeyRequest() {
            return new JourneyRequest(originId, destinationId, departureTime.toString(), preference.name());
        }
    }

    record Candidate(String id, String name, PlaceKind kind, String category,
                     boolean routingReady, String unavailableReason) {}

    record Clarification(Field field, Reason reason, String message, List<Candidate> candidates,
                         UUID confirmationId, OffsetDateTime expiresAt) {

        static Clarification ask(Field field, Reason reason, String message) {
            return new Clarification(field, reason, message, List.of(), null, null);
        }
    }

    record Confirmation(UUID confirmationId, String placeId) {}

    record Query(String message, UUID conversationId, UUID clientRequestId,
                 Long stateVersion, Confirmation confirmation) {

        String messageForHistory(String confirmedName) {
            return confirmation == null ? message : "选择 " + confirmedName;
        }
    }

    record Trace(String callId, String name, String status, String journeyRequestId, JourneyRequest conditions) {}

    record Reply(int schemaVersion, String requestId, Status status, String answer, Conditions conditions,
                 List<Clarification> clarifications, List<Trace> toolCalls, JourneyResponse journey,
                 String journeyRequestId, AssistantError error, UUID conversationId, Long stateVersion) {

        Reply withJourney(JourneyResponse value) {
            return new Reply(schemaVersion, requestId, status, answer, conditions, clarifications, toolCalls, value,
                    journeyRequestId, error, conversationId, stateVersion);
        }
    }

    record Created(UUID conversationId, String ownerToken, long stateVersion) {}

    record Message(long sequence, String role, String content, OffsetDateTime createdAt,
                   String journeyRequestId, Status status) {}

    record Snapshot(UUID conversationId, long stateVersion, String state, Conditions conditions,
                    List<Clarification> clarifications, List<Message> messages, Reply latest) {}

    record State(long version, long nextSequence, String state, Conditions conditions,
                 List<Clarification> clarifications) {
        static final State EMPTY = new State(0, 1, "COLLECTING", Conditions.EMPTY, List.of());

        boolean closed() {
            return "CLOSED".equals(state);
        }
    }

    private AssistantConversation() {}
}
