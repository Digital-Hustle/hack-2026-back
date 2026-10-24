package ru.cashflow.orchestrator.features.forecast.application;

import ru.cashflow.orchestrator.features.forecast.gateway.ForecastException;
import ru.cashflow.orchestrator.features.jobs.model.JobTypes.Job;
import ru.cashflow.orchestrator.features.snapshot.repository.SnapshotRepository.StoredSnapshot;

/**
 * Validates a 200 response against the job and persists it atomically, fenced by the lease (handoff §6).
 * Throws ForecastException(PERMANENT) for an invalid result, so no fake forecast is stored.
 */
public interface ResultHandler {

    void accept(Job job, int attempt, String leaseOwner, StoredSnapshot snapshot, String responseBody)
            throws ForecastException;
}
