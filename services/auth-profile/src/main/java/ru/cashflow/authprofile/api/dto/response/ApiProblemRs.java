package ru.cashflow.authprofile.api.dto.response;

import lombok.Builder;

import ru.cashflow.authprofile.api.error.ErrorCode;

import java.net.URI;
import java.util.List;

/** RFC 9457 shape including project extensions. Serve as application/problem+json. */
@Builder
public record ApiProblemRs(
        URI type,
        String title,
        int status,
        String detail,
        URI instance,
        ErrorCode code,
        String traceId,
        List<FieldErrorRs> errors) {}
