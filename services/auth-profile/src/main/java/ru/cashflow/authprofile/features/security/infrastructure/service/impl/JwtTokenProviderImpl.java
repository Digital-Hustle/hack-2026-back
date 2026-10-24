package ru.cashflow.authprofile.features.security.infrastructure.service.impl;

import lombok.RequiredArgsConstructor;

import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

import ru.cashflow.authprofile.config.properties.AuthProperties;
import ru.cashflow.authprofile.features.security.infrastructure.service.JwtTokenProvider;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class JwtTokenProviderImpl implements JwtTokenProvider {

    private final JwtEncoder encoder;
    private final AuthProperties properties;

    @Override
    public String createAccessToken(UUID userId, UUID sessionId, Instant now, Instant expiresAt) {
        var claims =
                JwtClaimsSet.builder()
                        .issuer(properties.jwt().issuer())
                        .audience(List.of(properties.jwt().audience()))
                        .subject(userId.toString())
                        .id(UUID.randomUUID().toString())
                        .issuedAt(now)
                        .notBefore(now)
                        .expiresAt(expiresAt)
                        .claim("sid", sessionId.toString())
                        .claim("roles", List.of("USER"))
                        .build();
        var header =
                JwsHeader.with(SignatureAlgorithm.RS256).keyId(properties.jwt().keyId()).build();

        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
