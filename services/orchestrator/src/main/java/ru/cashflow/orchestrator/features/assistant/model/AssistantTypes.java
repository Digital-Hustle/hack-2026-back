package ru.cashflow.orchestrator.features.assistant.model;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class AssistantTypes {

    private AssistantTypes() {
    }

    public record Message(String role, String content) {
    }

    public enum Status { ANSWERED, OUT_OF_SCOPE, INSUFFICIENT_DATA, NEEDS_CLARIFICATION }

    public enum Topic { OVERVIEW, FORECAST, GAP_DETAILS, CATEGORIES, COUNTERPARTIES, QUALITY }

    public enum Period { LAST_7_DAYS, LAST_30_DAYS, CURRENT_MONTH, PREVIOUS_MONTH, EXPLICIT }

    public record Question(String message, List<Message> history, LocalDate fromDate, LocalDate toDate,
                           UUID forecastId, UUID gapId) {
    }

    /** A model can choose only these read operations; it cannot supply SQL, URLs or tenant identifiers. */
    public record Plan(Status status, List<Topic> topics, Period period) {
    }

    public record Metadata(LocalDate asOfDate, long dataVersion) {
    }

    public record Source(String id, String type, String href, String asOfDate, long dataVersion,
                         String fromDate, String toDate, UUID forecastId, UUID snapshotId, String staleness) {
    }

    /** The model may select an ID, but neither supply nor edit the server-rendered statement. */
    public record Fact(String id, String sourceId, String text) {
    }

    public record Context(Metadata metadata, boolean forecastAvailable, boolean forecastStale,
                          List<Source> sources, List<Fact> facts, List<String> warnings) {
    }

    public record Reply(String answer, String model, String asOfDate, long dataVersion,
                        boolean forecastAvailable, boolean forecastStale, Status status,
                        List<Source> sources, List<Fact> facts, List<String> warnings) {
    }
}
