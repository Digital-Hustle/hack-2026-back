package ru.cashflow.orchestrator.features.integrations.bitrix.gateway;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import ru.cashflow.orchestrator.api.error.ApiException;
import ru.cashflow.orchestrator.features.integrations.bitrix.model.BitrixTypes.Portal;

/**
 * Validates a Bitrix24 incoming webhook URL ({@code https://<portal>/rest/<userId>/<token>/}). The server calls this
 * URL itself, so only HTTPS on the default port and hosts from a configured allowlist are accepted (no SSRF to
 * internal hosts). Error messages never echo the URL: it contains the access token.
 */
public final class BitrixWebhook {

    private static final Pattern PATH = Pattern.compile("^/rest/\\d{1,10}/[A-Za-z0-9]{6,64}/?$");
    private static final Pattern HOST = Pattern.compile("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$");
    private static final int MAX_HOST_LENGTH = 90;

    private BitrixWebhook() {
    }

    public static Portal parse(String url, List<String> allowedHostSuffixes) {
        if (url == null || url.isBlank()) {
            throw invalid("Не указан адрес входящего вебхука Битрикс24");
        }
        URI uri;
        try {
            uri = new URI(url.strip());
        } catch (URISyntaxException exception) {
            throw invalid("Адрес вебхука Битрикс24 не является корректным URL");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw invalid("Вебхук Битрикс24 должен использовать https");
        }
        if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw invalid("Адрес вебхука не должен содержать логин, параметры или фрагмент");
        }
        if (uri.getPort() != -1 && uri.getPort() != 443) {
            throw invalid("Вебхук Битрикс24 должен использовать стандартный порт https");
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (host.length() > MAX_HOST_LENGTH || !HOST.matcher(host).matches() || !allowed(host, allowedHostSuffixes)) {
            throw invalid("Домен портала не входит в список разрешённых доменов Битрикс24");
        }
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        if (!PATH.matcher(path).matches()) {
            throw invalid("Ожидается адрес вида https://<портал>/rest/<id пользователя>/<код вебхука>/");
        }
        String base = "https://" + host + (path.endsWith("/") ? path : path + "/");
        return new Portal(host, base);
    }

    static boolean allowed(String host, List<String> suffixes) {
        for (String raw : suffixes) {
            String suffix = raw.strip().toLowerCase(Locale.ROOT);
            if (suffix.isEmpty()) {
                continue;
            }
            String dotted = suffix.startsWith(".") ? suffix : "." + suffix;
            if (host.endsWith(dotted) && host.length() > dotted.length()) {
                return true;
            }
        }
        return false;
    }

    private static ApiException invalid(String message) {
        return ApiException.unprocessable("BITRIX_WEBHOOK_INVALID", message);
    }
}
