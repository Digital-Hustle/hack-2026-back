package ru.zuzex.practice.gatewayserver.filter;

import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Переводит URL публичного контракта хаба, где область счёта передаётся
 * query-параметром {@code accountId}, в форму Orchestrator, где счёт является
 * сегментом пути {@code /companies/{companyId}/accounts/{accountId}/...}.
 * Применяется к ресурсам {@code payments} и {@code imports}; остальные части
 * пути и исходная строка запроса (без параметра accountId) сохраняются без
 * перекодирования. Запросы без валидного UUID в accountId не изменяются.
 */
@Component
public class OrchestratorCompatGatewayFilterFactory extends AbstractGatewayFilterFactory<Object> {

    private static final Pattern COMPANY_SCOPED =
            Pattern.compile("^/api/v1/orchestrator/companies/([^/]+)/(payments|imports)(/.*)?$");
    private static final Pattern UUID = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern ACCOUNT_ID_PARAM = Pattern.compile("(^|&)accountId=[^&]*");

    @Override
    public GatewayFilter apply(Object config) {
        return (exchange, chain) -> {
            ServerHttpRequest request = exchange.getRequest();
            String accountId = request.getQueryParams().getFirst("accountId");
            Matcher matcher = COMPANY_SCOPED.matcher(request.getPath().value());
            if (accountId == null || !UUID.matcher(accountId).matches() || !matcher.matches()) {
                return chain.filter(exchange);
            }
            String companyId = matcher.group(1);
            String resource = matcher.group(2);
            String tail = matcher.group(3) == null ? "" : matcher.group(3);
            String newPath = "/api/v1/orchestrator/companies/" + companyId + "/accounts/" + accountId
                    + "/" + resource + tail;

            String rawQuery = request.getURI().getRawQuery();
            String newQuery = rawQuery == null ? null : ACCOUNT_ID_PARAM.matcher(rawQuery).replaceFirst("");
            if (newQuery != null) {
                newQuery = newQuery.startsWith("&") ? newQuery.substring(1) : newQuery;
                if (newQuery.isEmpty()) {
                    newQuery = null;
                }
            }

            URI newUri = UriComponentsBuilder.fromUri(request.getURI())
                    .replacePath(newPath)
                    .replaceQuery(newQuery)
                    .build(true)
                    .toUri();
            ServerHttpRequest newRequest = request.mutate().uri(newUri).build();
            return chain.filter(exchange.mutate().request(newRequest).build());
        };
    }
}
