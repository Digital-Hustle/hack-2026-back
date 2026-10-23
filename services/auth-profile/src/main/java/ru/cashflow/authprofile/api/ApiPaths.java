package ru.cashflow.authprofile.api;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class ApiPaths {

    public static final String BASE = "/api/v1/auth-profile";
    public static final String AUTH = BASE + "/auth";
    public static final String PROFILE = BASE + "/profile";
    public static final String REFRESH_COOKIE = "__Secure-cashflow-refresh";
}
