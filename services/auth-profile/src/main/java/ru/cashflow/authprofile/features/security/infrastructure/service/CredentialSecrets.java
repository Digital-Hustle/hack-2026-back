package ru.cashflow.authprofile.features.security.infrastructure.service;

public interface CredentialSecrets {

    String code();

    String randomToken();

    String hmac(String purpose, String value);

    String refreshHash(String value);

    boolean matches(String left, String right);
}
