package ru.cashflow.orchestrator.features.forecast.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import ru.cashflow.orchestrator.features.forecast.gateway.ForecastException;
import ru.cashflow.orchestrator.features.jobs.model.JobTypes.Job;
import ru.cashflow.orchestrator.features.snapshot.repository.SnapshotRepository.StoredSnapshot;

/**
 * Safe default until a validating handler exists for a job type: a response that cannot be validated is never stored
 * and never reported as success. The job fails with an explicit code instead of producing a fake forecast.
 */
@Component
@ConditionalOnMissingBean(value = ResultHandler.class, ignored = RejectingResultHandler.class)
public class RejectingResultHandler implements ResultHandler {

    @Override
    public void accept(Job job, int attempt, String leaseOwner, StoredSnapshot snapshot, String responseBody) {
        throw new ForecastException(ForecastException.Kind.PERMANENT, "RESULT_VALIDATOR_MISSING",
                "Результат не может быть проверен и не сохранён", null);
    }
}
