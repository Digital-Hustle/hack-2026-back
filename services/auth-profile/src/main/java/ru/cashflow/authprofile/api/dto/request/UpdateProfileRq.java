package ru.cashflow.authprofile.api.dto.request;

import static ru.cashflow.authprofile.features.profile.constants.ProfileConstants.MAX_DISPLAY_NAME_LENGTH;
import static ru.cashflow.authprofile.features.profile.constants.ProfileConstants.MAX_PROFILE_VERSION;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import lombok.Builder;

@Builder
public record UpdateProfileRq(
        @NotBlank @Size(max = MAX_DISPLAY_NAME_LENGTH) String displayName,
        @NotNull @PositiveOrZero @Max(MAX_PROFILE_VERSION) Long version) {}
