package ru.cashflow.orchestrator.api.error;

import java.util.UUID;

/** Safe client-facing failure: the message never contains financial values or identifiers of other tenants. */
public class ApiException extends RuntimeException {

    private final int status;
    private final String code;
    private final UUID resourceId;

    public ApiException(int status, String code, String message) {
        this(status, code, message, null);
    }

    /**
     * @param resourceId optional id of the resource the failure is about (for example a rejected import).
     */
    public ApiException(int status, String code, String message, UUID resourceId) {
        super(message);
        this.status = status;
        this.code = code;
        this.resourceId = resourceId;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public UUID resourceId() {
        return resourceId;
    }

    public static ApiException notFound() {
        return new ApiException(404, "NOT_FOUND", "Ресурс не найден");
    }

    public static ApiException forbidden() {
        return new ApiException(403, "FORBIDDEN", "Недостаточно прав для операции");
    }

    public static ApiException unauthorized() {
        return new ApiException(401, "AUTH_REQUIRED", "Нужен проверенный контекст пользователя");
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(409, code, message);
    }

    public static ApiException unprocessable(String code, String message) {
        return new ApiException(422, code, message);
    }

    public static ApiException unprocessable(String code, String message, UUID resourceId) {
        return new ApiException(422, code, message, resourceId);
    }
}
