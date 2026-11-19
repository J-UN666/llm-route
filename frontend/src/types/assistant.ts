import type { JourneyErrorBody, JourneyPreference, JourneyResponse, PlaceScope } from '@/types/journey';

export type AssistantStatus = 'SUCCEEDED' | 'NO_ROUTE' | 'NEEDS_CLARIFICATION' | 'JOURNEY_FAILED';

export interface Conditions {
  originId: string | null;
  destinationId: string | null;
  departureTime: string | null;
  preference: JourneyPreference;
}

export interface AssistantCandidate {
  id: string;
  name: string;
  kind: PlaceScope;
  category: string | null;
  routingReady: boolean;
  unavailableReason: string | null;
}

export interface Clarification {
  field: 'ORIGIN' | 'DESTINATION' | 'DEPARTURE_TIME' | 'PREFERENCE';
  reason: string;
  message: string;
  candidates: AssistantCandidate[];
  confirmationId: string | null;
  expiresAt: string | null;
}

export interface Confirmation {
  confirmationId: string;
  placeId: string;
}

export interface AssistantReply {
  schemaVersion: number;
  requestId: string;
  conversationId: string;
  stateVersion: number;
  status: AssistantStatus;
  answer: string;
  conditions: Conditions;
  clarifications: Clarification[];
  journey: JourneyResponse | null;
  journeyRequestId: string | null;
  error: JourneyErrorBody | null;
}

export interface AssistantMessage {
  sequence: number;
  role: 'USER' | 'ASSISTANT';
  content: string;
  createdAt: string;
  journeyRequestId: string | null;
  status: AssistantStatus | null;
}

export interface AssistantSnapshot {
  conversationId: string;
  stateVersion: number;
  state: 'COLLECTING' | 'AWAITING_CONFIRMATION' | 'READY' | 'CLOSED';
  conditions: Conditions;
  clarifications: Clarification[];
  messages: AssistantMessage[];
  latest: AssistantReply | null;
}

export interface AssistantSession {
  conversationId: string;
  stateVersion: number;
  ownerToken: string;
}

export type AssistantInput = { message: string } | { confirmation: Confirmation };

export interface AssistantSubmission {
  clientRequestId: string;
  stateVersion: number;
  input: AssistantInput;
  displayText: string;
}
