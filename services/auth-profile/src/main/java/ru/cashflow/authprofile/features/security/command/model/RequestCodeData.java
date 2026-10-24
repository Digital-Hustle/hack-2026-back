package ru.cashflow.authprofile.features.security.command.model;

import lombok.Builder;

@Builder
public record RequestCodeData(String email, String source) {}
