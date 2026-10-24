package ru.cashflow.orchestrator.features.snapshot.command;

import java.util.List;

import ru.cashflow.orchestrator.features.snapshot.model.BacktestOrigin;

/** No result can be computed; retain the reason for every requested origin for the caller. */
public final class BacktestSnapshotException extends SnapshotException {

    private final List<BacktestOrigin> origins;

    public BacktestSnapshotException(List<BacktestOrigin> origins) {
        super("NO_EVALUABLE_ORIGINS", "Нет ни одного origin с восстановимой историей и полным scoring-периодом");
        this.origins = List.copyOf(origins);
    }

    public List<BacktestOrigin> origins() {
        return origins;
    }
}
