package ru.cashflow.orchestrator.features.forecast.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls input versions; disabled in tests so reconciliation is called deterministically. */
@Component
@ConditionalOnProperty(name = "orchestrator.auto-refresh.enabled", havingValue = "true", matchIfMissing = true)
public class AutoRefreshScheduler {

    private final AutoRefreshService service;

    public AutoRefreshScheduler(AutoRefreshService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${orchestrator.auto-refresh.poll-interval:30s}")
    public void poll() {
        service.reconcile();
    }
}
