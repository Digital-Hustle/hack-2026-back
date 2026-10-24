package ru.cashflow.authprofile.api.dto.request;

import static ru.cashflow.authprofile.features.security.constants.SecurityConstants.OTP_CODE_PATTERN;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import lombok.Builder;

import java.util.UUID;

@Builder
public record VerifyCodeRq(
        @NotNull UUID challengeId, @NotBlank @Pattern(regexp = OTP_CODE_PATTERN) String code) {

    @Override
    public String toString() {
        return "VerifyCodeRq[redacted]";
    }

    public static class VerifyCodeRqBuilder {

        @Override
        public String toString() {
            return "VerifyCodeRqBuilder[redacted]";
        }
    }
}
