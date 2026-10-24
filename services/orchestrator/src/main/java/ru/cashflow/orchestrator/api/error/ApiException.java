package ru.cashflow.orchestrator.api.error;

/** Safe client-facing failure: the message never contains financial values or identifiers of other tenants. */
public class ApiException extends RuntimeException {

    private final int status;
    private final String code;

    public ApiException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
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
}
