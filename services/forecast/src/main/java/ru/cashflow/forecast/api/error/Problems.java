package ru.cashflow.forecast.api.error;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ProblemDetail;

public final class Problems {
    private Problems() { }

    public static ProblemDetail create(int status, String code, String detail, List<FieldErrorRs> errors, String traceId) {
        var problem = ProblemDetail.forStatusAndDetail(org.springframework.http.HttpStatusCode.valueOf(status), detail);
        problem.setType(URI.create("urn:cashflow:forecast:problem:" + code.toLowerCase(java.util.Locale.ROOT)));
        problem.setTitle("Forecast request failed");
        problem.setProperty("code", code);
        problem.setProperty("traceId", traceId == null ? UUID.randomUUID().toString() : traceId);
        problem.setProperty("errors", errors);
        problem.setProperty("retryAfterSec", status == 503 ? 1 : null);
        problem.setProperty("resourceId", null);
        return problem;
    }
}
