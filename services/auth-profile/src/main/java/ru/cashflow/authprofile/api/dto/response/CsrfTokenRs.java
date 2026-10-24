package ru.cashflow.authprofile.api.dto.response;

import lombok.Builder;

@Builder
public record CsrfTokenRs(String token, String headerName) {

    @Override
    public String toString() {
        return "CsrfTokenRs[redacted]";
    }

    public static class CsrfTokenRsBuilder {

        @Override
        public String toString() {
            return "CsrfTokenRsBuilder[redacted]";
        }
    }
}
