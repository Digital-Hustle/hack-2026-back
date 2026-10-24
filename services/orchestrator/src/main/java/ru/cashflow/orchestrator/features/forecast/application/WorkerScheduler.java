package ru.cashflow.orchestrator.features.forecast.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls the job table; disabled in tests so they drive the worker deterministically. */
@Component
@ConditionalOnProperty(name = "orchestrator.jobs.worker-enabled", havingValue = "true", matchIfMissing = true)
public class WorkerScheduler {

    private final ForecastWorker worker;

    public WorkerScheduler(ForecastWorker worker) {
        this.worker = worker;
    }

    @Scheduled(fixedDelayString = "${orchestrator.jobs.poll-interval:1s}")
    public void poll() {
        int processed = 0;
        while (processed < 10 && worker.runOnce()) {
            processed++;
        }
    }
}
