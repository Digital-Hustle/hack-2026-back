package ru.cashflow.authprofile.features.security.infrastructure.service.impl;

import static ru.cashflow.authprofile.features.security.constants.SecurityConstants.HMAC_ALGORITHM;
import static ru.cashflow.authprofile.features.security.constants.SecurityConstants.OTP_CODE_BOUND;
import static ru.cashflow.authprofile.features.security.constants.SecurityConstants.OTP_CODE_LENGTH;
import static ru.cashflow.authprofile.features.security.constants.SecurityConstants.RANDOM_TOKEN_BYTES;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import ru.cashflow.authprofile.features.security.infrastructure.service.CredentialSecrets;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.SecretKey;

@Component
@RequiredArgsConstructor
public class CredentialSecretsImpl implements CredentialSecrets {

    private final SecureRandom random = new SecureRandom();
    private final SecretKey key;

    @Override
    public String randomToken() {
        byte[] bytes = new byte[RANDOM_TOKEN_BYTES];
        random.nextBytes(bytes);

        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @Override
    public String code() {
        return String.format(
                Locale.ROOT, "%0" + OTP_CODE_LENGTH + "d", random.nextInt(OTP_CODE_BOUND));
    }

    @Override
    public String hmac(String purpose, String value) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(key);

            return HexFormat.of()
                    .formatHex(
                            mac.doFinal((purpose + ":" + value).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC unavailable");
        }
    }

    @Override
    public String refreshHash(String value) {
        return hmac("refresh", value);
    }

    @Override
    public boolean matches(String left, String right) {
        return left != null
                && right != null
                && MessageDigest.isEqual(
                        left.getBytes(StandardCharsets.US_ASCII),
                        right.getBytes(StandardCharsets.US_ASCII));
    }
}
