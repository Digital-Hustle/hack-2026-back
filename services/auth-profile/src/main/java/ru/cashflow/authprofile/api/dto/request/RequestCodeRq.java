package ru.cashflow.authprofile.api.dto.request;

import static ru.cashflow.authprofile.features.security.constants.SecurityConstants.MAX_EMAIL_LENGTH;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import lombok.Builder;

@Builder
public record RequestCodeRq(@NotBlank @Email @Size(max = MAX_EMAIL_LENGTH) String email) {}
