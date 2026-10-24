package ru.cashflow.orchestrator.features.jobs.application;

import java.util.function.Supplier;

import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.stereotype.Component;

/**
 * Retries a whole REPEATABLE READ transaction on a serialization conflict (handoff §3). The supplier must start a new
 * transaction per call, so a retry never reuses a partially read MVCC view.
 */
@Component
public class SerializationRetry {

    private static final int ATTEMPTS = 4;

    public <T> T run(Supplier<T> transaction) {
        ConcurrencyFailureException last = null;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                return transaction.get();
            } catch (ConcurrencyFailureException exception) {
                last = exception;
                try {
                    Thread.sleep(10L * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw last;
    }
}
