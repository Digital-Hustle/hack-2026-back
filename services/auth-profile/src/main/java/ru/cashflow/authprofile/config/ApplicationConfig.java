package ru.cashflow.authprofile.config;

import static ru.cashflow.authprofile.features.security.constants.SecurityConstants.HMAC_ALGORITHM;
import static ru.cashflow.authprofile.features.security.constants.SecurityConstants.MIN_HMAC_KEY_BYTES;
import static ru.cashflow.authprofile.features.security.constants.SecurityConstants.MIN_RSA_KEY_BITS;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;

import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.boot.autoconfigure.jooq.DefaultConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import ru.cashflow.authprofile.api.json.AuthProfileJson;
import ru.cashflow.authprofile.config.properties.AuthProperties;

import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.util.Base64;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

@Configuration
public class ApplicationConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    Jackson2ObjectMapperBuilderCustomizer jsonConfiguration() {
        return builder -> builder.postConfigurer(AuthProfileJson::configure);
    }

    @Bean
    DefaultConfigurationCustomizer jooqRecordMapping() {
        return configuration ->
                configuration
                        .settings()
                        .withMapRecordComponentParameterNames(true)
                        .withMapConstructorParameterNames(true);
    }

    @Bean
    public SecretKey otpHmacKey(AuthProperties properties) {
        try {
            byte[] key = Base64.getDecoder().decode(properties.otpHmacSecret());
            if (key.length < MIN_HMAC_KEY_BYTES) {
                throw new IllegalArgumentException();
            }

            return new SecretKeySpec(key, HMAC_ALGORITHM);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "AUTH_OTP_HMAC_SECRET must encode at least "
                            + MIN_HMAC_KEY_BYTES
                            + " random bytes");
        }
    }

    @Bean
    JwtEncoder jwtEncoder(AuthProperties properties) {
        try {
            var factory = KeyFactory.getInstance("RSA");
            var publicKey =
                    (RSAPublicKey)
                            factory.generatePublic(
                                    new X509EncodedKeySpec(
                                            Base64.getDecoder()
                                                    .decode(properties.jwt().publicKey())));
            var privateKey =
                    (RSAPrivateKey)
                            factory.generatePrivate(
                                    new PKCS8EncodedKeySpec(
                                            Base64.getDecoder()
                                                    .decode(properties.jwt().privateKey())));
            if (publicKey.getModulus().bitLength() < MIN_RSA_KEY_BITS
                    || !publicKey.getModulus().equals(privateKey.getModulus())) {
                throw new IllegalArgumentException();
            }
            var key =
                    new RSAKey.Builder(publicKey)
                            .privateKey(privateKey)
                            .keyID(properties.jwt().keyId())
                            .build();

            return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Configure a matching RSA key pair of at least "
                            + MIN_RSA_KEY_BITS
                            + " bits (base64 DER)");
        }
    }
}
