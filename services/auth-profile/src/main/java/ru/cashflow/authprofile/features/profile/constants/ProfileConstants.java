package ru.cashflow.authprofile.features.profile.constants;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class ProfileConstants {

    public static final int MAX_DISPLAY_NAME_LENGTH = 100;
    public static final long MAX_PROFILE_VERSION = 9_007_199_254_740_991L;
}
