package ru.zuzex.practice.gatewayserver.security.jwt;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwsHeader;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ru.zuzex.practice.gatewayserver.dto.RequestOwner;

import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

@Component
public class JwtTokenUtils {

    private static final int MIN_RSA_KEY_BITS = 2048;

    private final JwtParser parser;

    public JwtTokenUtils(
            @Value("${security.jwt.public-key}") String encodedKey,
            @Value("${security.jwt.issuer}") String issuer,
            @Value("${security.jwt.audience}") String audience,
            @Value("${security.jwt.key-id}") String keyId,
            Clock clock) {
        try {
            var key = (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(encodedKey)));
            if (key.getModulus().bitLength() < MIN_RSA_KEY_BITS) {
                throw new IllegalArgumentException("RSA key is too short");
            }
            parser = Jwts.parser()
                    .keyLocator(header -> {
                        if (!(header instanceof JwsHeader signedHeader)
                                || !Jwts.SIG.RS256.getId().equals(header.getAlgorithm())
                                || !keyId.equals(signedHeader.getKeyId())) {
                            throw new JwtException("Invalid access token header");
                        }
                        return key;
                    })
                    .requireIssuer(issuer)
                    .requireAudience(audience)
                    .clock(() -> Date.from(clock.instant()))
                    .build();
        } catch (Exception exception) {
            throw new IllegalArgumentException("Configure GATEWAY_JWT_PUBLIC_KEY as base64 DER X.509 RSA", exception);
        }
    }

    public RequestOwner extractRequestOwner(String token) {
        var signed = parser.parseSignedClaims(token);
        var claims = signed.getPayload();
        if (claims.getExpiration() == null) {
            throw new JwtException("Invalid access token metadata");
        }

        return RequestOwner.builder()
                .userId(uuid(claims.getSubject()))
                .sessionId(uuid(claims.get("sid", String.class)))
                .build();
    }

    private UUID uuid(String value) {
        if (value == null) {
            throw new JwtException("Missing identity claim");
        }
        UUID id = UUID.fromString(value);
        if (!id.toString().equalsIgnoreCase(value)) {
            throw new JwtException("Invalid identity claim");
        }
        return id;
    }
}
