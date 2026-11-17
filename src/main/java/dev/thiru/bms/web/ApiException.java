package dev.thiru.bms.web;

import in.jolt.HttpException;
import java.util.List;
import java.util.Map;

/**
 * A web-layer failure with an explicit status and stable problem+json {@code code}. It is an {@link
 * HttpException} so Jolt treats it as an expected outcome and does not log it.
 */
public final class ApiException extends HttpException {

    private static final long serialVersionUID = 1L;

    private final String code;
    private final transient Map<String, String> errors;
    private final transient List<String> conflicts;

    public ApiException(int status, String code, String detail) {
        this(status, code, detail, Map.of(), List.of());
    }

    public ApiException(
            int status,
            String code,
            String detail,
            Map<String, String> errors,
            List<String> conflicts) {
        super(status, detail);
        this.code = code;
        this.errors = Map.copyOf(errors);
        this.conflicts = List.copyOf(conflicts);
    }

    public String code() {
        return code;
    }

    public Map<String, String> errors() {
        return errors;
    }

    public List<String> conflicts() {
        return conflicts;
    }

    public static ApiException unauthenticated(String detail) {
        return new ApiException(401, "UNAUTHENTICATED", detail);
    }

    public static ApiException forbidden(String detail) {
        return new ApiException(403, "FORBIDDEN", detail);
    }

    public static ApiException notFound(String detail) {
        return new ApiException(404, "NOT_FOUND", detail);
    }

    public static ApiException validation(String field, String message) {
        return new ApiException(
                422, "VALIDATION", "Validation failed", Map.of(field, message), List.of());
    }

    public static ApiException validation(Map<String, String> errors) {
        return new ApiException(422, "VALIDATION", "Validation failed", errors, List.of());
    }
}
