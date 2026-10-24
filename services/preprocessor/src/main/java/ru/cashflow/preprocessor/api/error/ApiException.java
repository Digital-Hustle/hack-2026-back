package ru.cashflow.preprocessor.api.error;

import java.util.List;

/** Safe client-facing failure: the message never contains financial values or identifiers of other tenants. */
public class ApiException extends RuntimeException {

    private final int status;
    private final String code;
    private final List<FieldError> errors;

    public ApiException(int status, String code, String message) {
        this(status, code, message, List.of());
    }

    public ApiException(int status, String code, String message, List<FieldError> errors) {
        super(message);
        this.status = status;
        this.code = code;
        this.errors = List.copyOf(errors);
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public List<FieldError> errors() {
        return errors;
    }

    public static ApiException badRequest(String detail, String field, String fieldCode) {
        return new ApiException(400, "MALFORMED_REQUEST", detail, List.of(new FieldError(field, fieldCode, detail)));
    }

    public static ApiException unauthorized() {
        return new ApiException(401, "AUTH_REQUIRED", "Нужен проверенный контекст пользователя");
    }

    public static ApiException unprocessable(String detail, String field, String fieldCode) {
        return new ApiException(422, "VALIDATION_FAILED", detail, List.of(new FieldError(field, fieldCode, detail)));
    }

    public static ApiException payloadTooLarge(String detail) {
        return new ApiException(413, "PAYLOAD_TOO_LARGE", detail, List.of(new FieldError("file", "FILE_TOO_LARGE",
                detail)));
    }

    public static ApiException unsupportedMediaType(String detail) {
        return new ApiException(415, "UNSUPPORTED_MEDIA_TYPE", detail,
                List.of(new FieldError("file", "UNSUPPORTED_FILE", detail)));
    }

    public record FieldError(String field, String code, String message) {
    }
}
