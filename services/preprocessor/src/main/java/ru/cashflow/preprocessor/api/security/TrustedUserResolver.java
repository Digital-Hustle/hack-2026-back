package ru.cashflow.preprocessor.api.security;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import ru.cashflow.preprocessor.api.error.ApiException;

/**
 * Reads exactly one canonical X-User-Id. Gateway strips client copies of this header before setting it,
 * so trust depends on PreProcessor ingress being reachable only from Gateway (closed network).
 */
@Component
public class TrustedUserResolver implements HandlerMethodArgumentResolver {

    public static final String USER_HEADER = "X-User-Id";

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType().equals(CurrentUser.class);
    }

    @Override
    public CurrentUser resolveArgument(MethodParameter parameter, ModelAndViewContainer container,
                                       NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
        List<String> values = request == null ? List.of() : Collections.list(request.getHeaders(USER_HEADER));
        if (values.size() != 1) {
            throw ApiException.unauthorized();
        }
        String value = values.getFirst().strip();
        try {
            UUID id = UUID.fromString(value);
            if (!id.toString().equalsIgnoreCase(value)) {
                throw ApiException.unauthorized();
            }
            return new CurrentUser(id);
        } catch (IllegalArgumentException exception) {
            throw ApiException.unauthorized();
        }
    }
}
