package ru.cashflow.authprofile.units;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import ru.cashflow.authprofile.api.dto.request.VerifyCodeRq;
import ru.cashflow.authprofile.config.ApplicationConfig;
import ru.cashflow.authprofile.config.properties.AuthProperties;
import ru.cashflow.authprofile.features.security.command.model.IssuedTokens;
import ru.cashflow.authprofile.features.security.command.model.PendingCode;

class CredentialConfigurationTest {

    @ParameterizedTest
    @ValueSource(strings = {"not-base64!", "dG9vLXNob3J0"})
    void rejectsInvalidHmacConfigurationBeforeServingRequests(String secret) {
        // given
        var properties = AuthProperties.builder().otpHmacSecret(secret).build();

        // when / then
        assertThatThrownBy(() -> new ApplicationConfig().otpHmacKey(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(secret);
    }

    @Test
    void generatedBuildersKeepCredentialsOutOfTheirStringRepresentation() {
        // given
        var tokens =
                IssuedTokens.builder()
                        .accessToken("access-sentinel")
                        .refreshToken("refresh-sentinel");
        var code = PendingCode.builder().code("123456");
        var request = VerifyCodeRq.builder().code("123456");
        var properties = AuthProperties.builder().otpHmacSecret("hmac-sentinel");
        var jwt = AuthProperties.Jwt.builder().privateKey("key-sentinel");

        // when / then
        assertThat(tokens.toString()).doesNotContain("access-sentinel", "refresh-sentinel");
        assertThat(tokens.build().toString()).doesNotContain("access-sentinel", "refresh-sentinel");
        assertThat(code.toString()).doesNotContain("123456");
        assertThat(request.toString()).doesNotContain("123456");
        assertThat(properties.toString()).doesNotContain("hmac-sentinel");
        assertThat(jwt.toString()).doesNotContain("key-sentinel");
        assertThat(jwt.build().toString()).doesNotContain("key-sentinel");
    }
}
