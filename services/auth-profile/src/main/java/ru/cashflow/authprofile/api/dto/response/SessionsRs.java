package ru.cashflow.authprofile.api.dto.response;

import lombok.Builder;

import java.util.List;

@Builder
public record SessionsRs(List<SessionRs> items, int total) {}
