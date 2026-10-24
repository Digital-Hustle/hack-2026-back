package ru.cashflow.authprofile.exception;

import lombok.Builder;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import ru.cashflow.authprofile.api.error.ErrorCode;
import ru.cashflow.authprofile.features.profile.exceptions.ProfileNotFoundException;
import ru.cashflow.authprofile.features.profile.exceptions.ProfileVersionConflictException;
import ru.cashflow.authprofile.features.security.exceptions.AuthenticationRequiredException;
import ru.cashflow.authprofile.features.security.exceptions.CodeAttemptsExceededException;
import ru.cashflow.authprofile.features.security.exceptions.ExpiredCodeException;
import ru.cashflow.authprofile.features.security.exceptions.InvalidCodeException;
import ru.cashflow.authprofile.features.security.exceptions.InvalidRefreshException;
import ru.cashflow.authprofile.features.security.exceptions.RateLimitedException;
import ru.cashflow.authprofile.features.security.exceptions.RefreshReusedException;
import ru.cashflow.authprofile.features.security.exceptions.SessionNotFoundException;

import java.time.Duration;

@Component
public class ExceptionResponseProvider {

    @Builder
    public record Descriptor(ErrorCode code, int status, Long retryAfter) {}

    public Descriptor describe(RuntimeException exception) {
        ErrorCode code =
                switch (exception) {
                    case AuthenticationRequiredException ignored ->
                            ErrorCode.AUTHENTICATION_REQUIRED;
                    case CsrfInvalidException ignored -> ErrorCode.CSRF_INVALID;
                    case InvalidCodeException ignored -> ErrorCode.CODE_INVALID;
                    case ExpiredCodeException ignored -> ErrorCode.CODE_EXPIRED;
                    case CodeAttemptsExceededException ignored -> ErrorCode.CODE_ATTEMPTS_EXCEEDED;
                    case InvalidRefreshException ignored -> ErrorCode.REFRESH_INVALID;
                    case RefreshReusedException ignored -> ErrorCode.REFRESH_REUSED;
                    case RateLimitedException ignored -> ErrorCode.RATE_LIMITED;
                    case SessionNotFoundException ignored -> ErrorCode.SESSION_NOT_FOUND;
                    case ProfileVersionConflictException ignored ->
                            ErrorCode.PROFILE_VERSION_CONFLICT;
                    case ProfileNotFoundException ignored -> ErrorCode.NOT_FOUND;
                    case DependencyUnavailableException ignored -> ErrorCode.DEPENDENCY_UNAVAILABLE;
                    default -> ErrorCode.INTERNAL_ERROR;
                };
        HttpStatus status =
                switch (code) {
                    case AUTHENTICATION_REQUIRED,
                            CODE_INVALID,
                            CODE_EXPIRED,
                            REFRESH_INVALID,
                            REFRESH_REUSED ->
                            HttpStatus.UNAUTHORIZED;
                    case CSRF_INVALID -> HttpStatus.FORBIDDEN;
                    case SESSION_NOT_FOUND, NOT_FOUND -> HttpStatus.NOT_FOUND;
                    case PROFILE_VERSION_CONFLICT -> HttpStatus.CONFLICT;
                    case CODE_ATTEMPTS_EXCEEDED, RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
                    case DEPENDENCY_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
                    default -> HttpStatus.INTERNAL_SERVER_ERROR;
                };
        Long retryAfter =
                exception instanceof RetryableDomainException limited
                        ? retryAfterSeconds(limited.retryAfter())
                        : null;

        return Descriptor.builder()
                .code(code)
                .status(status.value())
                .retryAfter(retryAfter)
                .build();
    }

    private long retryAfterSeconds(Duration duration) {
        long seconds = duration.toSeconds();
        long roundedUp = duration.getNano() == 0 ? seconds : seconds + 1;

        return Math.max(1, roundedUp);
    }
}
