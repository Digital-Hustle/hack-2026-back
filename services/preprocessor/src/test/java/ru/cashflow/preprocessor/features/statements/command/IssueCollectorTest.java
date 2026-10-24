package ru.cashflow.preprocessor.features.statements.command;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IssueCollectorTest {

    @Test
    void countsWarningsBeyondStoredIssueLimitWithoutCountingErrors() {
        var collector = new IssueCollector(1);

        collector.add("EMPTY_COUNTERPARTY_INN", "WARNING", "STATEMENTS", 1, 2, "counterparty_inn", "missing");
        collector.add("INVALID_FIELD", "ERROR", "STATEMENTS", 2, 3, "amount", "invalid");
        collector.add("MISSING_BALANCE", "WARNING", "STATEMENTS", 3, 4, "balance_eod", "missing");

        assertThat(collector.total()).isEqualTo(3);
        assertThat(collector.warningTotal()).isEqualTo(2);
        assertThat(collector.issues()).hasSize(1);
        assertThat(collector.truncated()).isTrue();
    }
}
