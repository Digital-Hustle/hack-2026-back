package ru.cashflow.authprofile.features.security.constants;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class SecurityConstants {

    public static final int OTP_CODE_LENGTH = 6;
    public static final int OTP_CODE_BOUND = 1_000_000;
    public static final String OTP_CODE_PATTERN = "[0-9]{" + OTP_CODE_LENGTH + "}";
    public static final int MAX_EMAIL_LENGTH = 254;
    public static final int MAX_CONFIGURED_CODE_ATTEMPTS = 20;
    public static final int RANDOM_TOKEN_BYTES = 32;
    public static final String OPAQUE_TOKEN_PATTERN = "[A-Za-z0-9_-]{43}";
    public static final int MIN_HMAC_KEY_BYTES = 32;
    public static final int MIN_RSA_KEY_BITS = 2048;
    public static final String HMAC_ALGORITHM = "HmacSHA256";
}
