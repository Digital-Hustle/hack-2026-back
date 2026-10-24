package ru.zuzex.practice.gatewayserver.dto;

import lombok.Builder;

import java.util.UUID;

@Builder
public record RequestOwner(UUID userId, UUID sessionId) {
}
