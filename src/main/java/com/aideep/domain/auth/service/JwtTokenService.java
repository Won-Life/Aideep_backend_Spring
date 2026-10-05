package com.aideep.domain.auth.service;

import com.aideep.domain.auth.config.AuthProperties;
import com.aideep.domain.auth.dto.Identity;
import com.aideep.domain.auth.dto.response.TokensResponse;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

@Service
public class JwtTokenService {
    private final JwtEncoder jwtEncoder;
    private final NimbusJwtDecoder nimbusJwtDecoder;
    private final Clock clock;

    public JwtTokenService(AuthProperties authProperties, Clock clock) {
        this.clock = clock;
        byte[] secret = authProperties.jwtSecret() == null ? new byte[0]
                : authProperties.jwtSecret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) throw new IllegalStateException(
                "JWT_SECRET must contain at least 32 UTF-8 bytes; use the same secret as NestJS");
        var key = new SecretKeySpec(secret, "HmacSHA256");
        jwtEncoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        nimbusJwtDecoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        var jwtTimestampValidator = new JwtTimestampValidator(Duration.ZERO);
        jwtTimestampValidator.setClock(clock);
        nimbusJwtDecoder.setJwtValidator(jwtTimestampValidator);
    }

    public Jwt decode(String token) {
        Jwt jwt = nimbusJwtDecoder.decode(token);
        try {
            UUID.fromString(jwt.getClaimAsString("user_id"));
            if (jwt.getClaimAsString("email") == null
                    || jwt.getExpiresAt() == null || !jwt.getExpiresAt().isAfter(clock.instant()))
                throw new IllegalArgumentException();
            Object master = jwt.getClaims().get("isMaster");
            if (master != null && !(master instanceof Boolean)) throw new IllegalArgumentException();
        } catch (RuntimeException invalid) {
            throw new BadJwtException("유효하지 않은 토큰입니다.");
        }
        return jwt;
    }

    public TokensResponse issue(Identity user) {
        return new TokensResponse(sign(user, Duration.ofMinutes(15), false), sign(user, Duration.ofDays(7), false));
    }

    private String sign(Identity user, Duration lifetime, boolean master) {
        Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        var claims = JwtClaimsSet.builder().issuedAt(now).expiresAt(now.plus(lifetime))
                .claim("email", user.email()).claim("user_id", user.user_id());
        if (master) claims.claim("isMaster", true);
        return jwtEncoder.encode(
                        JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(), claims.build()))
                .getTokenValue();
    }

    public Identity identity(Jwt jwt) {
        return new Identity(jwt.getClaimAsString("email"),
                jwt.getClaimAsString("user_id"));
    }
}
