package ru.cashflow.orchestrator.units;

import java.util.List;

import org.junit.jupiter.api.Test;

import ru.cashflow.orchestrator.api.error.ApiException;
import ru.cashflow.orchestrator.features.integrations.bitrix.gateway.BitrixWebhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BitrixWebhookTest {

    private static final List<String> SUFFIXES = List.of(".bitrix24.ru", "bitrix24.com");

    @Test
    void acceptsIncomingWebhookAndNormalizesTrailingSlash() {
        var portal = BitrixWebhook.parse(" https://Acme.Bitrix24.ru/rest/1/abc123xyz ", SUFFIXES);

        assertThat(portal.host()).isEqualTo("acme.bitrix24.ru");
        assertThat(portal.baseUrl()).isEqualTo("https://acme.bitrix24.ru/rest/1/abc123xyz/");
        assertThat(portal.toString()).doesNotContain("abc123xyz");
        assertThat(BitrixWebhook.parse("https://x.bitrix24.com:443/rest/77/Q1w2e3r4/", SUFFIXES).host())
                .isEqualTo("x.bitrix24.com");
    }

    @Test
    void rejectsUrlsThatWouldLetTheServerCallArbitraryHosts() {
        for (String url : List.of("http://acme.bitrix24.ru/rest/1/abc123xyz/",
                "https://169.254.169.254/rest/1/abc123xyz/", "https://localhost/rest/1/abc123xyz/",
                "https://bitrix24.ru/rest/1/abc123xyz/", "https://acme.bitrix24.ru.evil.com/rest/1/abc123xyz/",
                "https://evilbitrix24.ru/rest/1/abc123xyz/", "https://acme.bitrix24.ru:8443/rest/1/abc123xyz/",
                "https://user:pass@acme.bitrix24.ru/rest/1/abc123xyz/", "https://acme.bitrix24.ru/rest/1/abc123xyz/?a=1",
                "https://acme.bitrix24.ru/crm/deal/", "https://acme.bitrix24.ru/rest/1/abc/", "", "not a url")) {
            assertThatThrownBy(() -> BitrixWebhook.parse(url, SUFFIXES)).as(url)
                    .isInstanceOf(ApiException.class)
                    .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("BITRIX_WEBHOOK_INVALID"))
                    .satisfies(e -> assertThat(e.getMessage()).doesNotContain("abc123xyz"));
        }
    }
}
