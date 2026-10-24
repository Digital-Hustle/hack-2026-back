package ru.cashflow.forecast.units;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import ru.cashflow.forecast.features.forecast.application.SnapshotValidator;
import ru.cashflow.forecast.features.shared.model.InputException;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SnapshotValidatorTest {
    private final SnapshotValidator validator = new SnapshotValidator(TestInputs.CLOCK);

    @Test
    void acceptsShortCompleteHistoryAndDoesNotExtendAlreadyComputedStartBalance() throws Exception {
        assertThatCode(() -> validator.validate(TestInputs.point())).doesNotThrowAnyException();
        assertThatCode(() -> validator.validate(TestInputs.point(node -> node.put("startBalanceDate", "2026-10-20"))))
                .doesNotThrowAnyException();
        assertThatCode(() -> validator.validate(TestInputs.point(node -> node.put("startBalanceDate", "2026-01-01"))))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsFutureFactsAndLatePlanRevision() {
        assertInvalid(node -> ((ObjectNode) node.withArray("payments").get(0)).put("date", "2026-10-25"));
        assertInvalid(node -> ((ObjectNode) node.withArray("knownPlans").get(0)).put("knownAt", "2026-10-24T21:00:00Z"));
        assertInvalid(node -> ((ObjectNode) node.withArray("knownPlans").get(0)).put("updatedAt", "2026-10-26T00:00:00Z"));
        assertInvalid(node -> node.put("startBalanceDate", "2026-10-25"));
        assertInvalid(node -> node.put("asOfDate", "2026-10-26"));
    }

    @Test
    void rejectsDuplicateDatesMissingCalendarDaysAndPaymentIdentities() {
        assertInvalid(node -> node.withArray("dailyHistory").add(node.withArray("dailyHistory").get(0)));
        assertInvalid(node -> node.withArray("dailyHistory").remove(0));
        assertInvalid(node -> node.withArray("payments").add(node.withArray("payments").get(0)));
    }

    @Test
    void rejectsFlowsThatDisagreeWithPaymentsAndInvalidZeroOrMissingStatus() {
        assertInvalid(node -> ((ObjectNode) node.withArray("dailyHistory").get(0)).put("inflow", "1.00"));
        assertInvalid(node -> ((ObjectNode) node.withArray("dailyHistory").get(0)).put("status", "ZERO_CONFIRMED"));
        assertInvalid(node -> ((ObjectNode) node.withArray("dailyHistory").get(0)).put("status", "MISSING"));
    }

    @Test
    void rejectsForeignPlansBadRemainingAmountsAndPlansInHistoryOnly() {
        assertInvalid(node -> ((ObjectNode) node.withArray("knownPlans").get(0))
                .put("accountId", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"));
        assertInvalid(node -> ((ObjectNode) node.withArray("knownPlans").get(0)).put("remainingAmount", "1.00"));
        assertInvalid(node -> node.put("scope", "HISTORY_ONLY"));
        assertInvalid(node -> node.put("algorithmVersion", "UNKNOWN_V1"));
    }

    @Test
    void missingPeriodsAndLateCompletenessCannotBeBypassedByAnOldAnchor() {
        assertInvalid(node -> {
            node.put("startBalanceDate", "2026-10-20");
            node.withArray("completeRanges").removeAll();
            ObjectNode day = (ObjectNode) node.withArray("dailyHistory").get(4);
            day.put("status", "MISSING");
            day.putNull("inflow");
            day.putNull("outflow");
            day.putNull("balanceEod");
        });
        assertInvalid(node -> ((ObjectNode) node.withArray("completeRanges").get(0)).put("createdAt", "2026-10-24T21:00:00Z"));
    }

    private void assertInvalid(java.util.function.Consumer<ObjectNode> change) {
        assertThatThrownBy(() -> validator.validate(TestInputs.point(change))).isInstanceOf(InputException.class);
    }
}
