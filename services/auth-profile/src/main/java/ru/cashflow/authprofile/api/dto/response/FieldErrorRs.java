package ru.cashflow.authprofile.api.dto.response;

import lombok.Builder;

/** Does not carry the rejected value, which may contain credentials or personal data. */
@Builder
public record FieldErrorRs(String field, String code, String message) {}
