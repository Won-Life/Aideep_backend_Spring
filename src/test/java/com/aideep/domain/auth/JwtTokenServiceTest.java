package com.aideep.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aideep.domain.auth.config.AuthProperties;
import com.aideep.domain.auth.dto.Identity;
import com.aideep.domain.auth.security.CurrentUser;
import com.aideep.domain.auth.service.JwtTokenService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtException;
import tools.jackson.databind.ObjectMapper;

class JwtTokenServiceTest {
    static final String SECRET = "local-test-secret-at-least-32-bytes-long";
    static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");

    static AuthProperties properties(String secret) {
        return new AuthProperties(secret, "http://frontend.test", "client", "secret", "http://backend.test/callback",
                "http://google.test/auth", "http://google.test/token", "http://google.test/userinfo",
                "mail@example.com", "test-mail-password", "11111111-1111-4111-8111-111111111111", "demo-secret", "");
    }

    final JwtTokenService jwtTokenService = new JwtTokenService(properties(SECRET), Clock.fixed(NOW, ZoneOffset.UTC));
    final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void acceptsIndependentHs256FixtureWithLegacyClaims() throws Exception {
        String payload = "{\"userName\":\"Legacy\",\"email\":\"legacy@example.com\",\"user_id\":\"11111111-1111-4111-8111-111111111111\",\"iat\":1893456000,\"exp\":1893456900}";
        var jwt = jwtTokenService.decode(sign(payload, SECRET));
        assertThat(jwt.getClaimAsString("userName")).isEqualTo("Legacy");
        assertThat(jwt.getExpiresAt()).isEqualTo(NOW.plusSeconds(900));
    }

    @Test
    void acceptsIndependentHs256FixturesWithoutNickname() throws Exception {
        for (String nickname : List.of("", "\"userName\":null,")) {
            String payload = "{" + nickname + "\"email\":\"legacy@example.com\","
                    + "\"user_id\":\"11111111-1111-4111-8111-111111111111\",\"exp\":1893456900}";
            var jwt = jwtTokenService.decode(sign(payload, SECRET));
            assertThat(jwtTokenService.identity(jwt).email()).isEqualTo("legacy@example.com");
        }
    }

    @Test
    void issuedAccessAndRefreshTokensOmitNickname() {
        var pair = jwtTokenService.issue(
                new Identity("legacy@example.com", "11111111-1111-4111-8111-111111111111"));
        for (String token : List.of(pair.accessToken(), pair.refreshToken())) {
            var jwt = jwtTokenService.decode(token);
            assertThat(jwt.getClaims()).doesNotContainKey("userName");
            assertThat(jwtTokenService.identity(jwt).email()).isEqualTo("legacy@example.com");
        }
    }

    @Test
    void outputIsAnOrdinaryHs256JwtWithoutSpringSpecificClaims() throws Exception {
        var pair = jwtTokenService.issue(
                new Identity("legacy@example.com", "11111111-1111-4111-8111-111111111111"));
        String[] parts = pair.accessToken().split("\\.");
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        assertThat(Base64.getUrlDecoder().decode(parts[2])).isEqualTo(
                mac.doFinal((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8)));
        var payload = objectMapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
        assertThat(payload.get("user_id").asString()).isEqualTo("11111111-1111-4111-8111-111111111111");
        assertThat(payload.get("exp").asLong() - payload.get("iat").asLong()).isEqualTo(900);
        Path output = Path.of("build/test-jwt-compat.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, objectMapper.writeValueAsString(pair));
    }

    @Test
    void rejectsWrongSignatureExpiredMissingExpiryAndInvalidClaims() throws Exception {
        String base = "\"userName\":\"Legacy\",\"email\":\"legacy@example.com\",\"user_id\":\"11111111-1111-4111-8111-111111111111\"";
        for (String payload : List.of("{" + base + "}", "{" + base + ",\"exp\":1893456000}",
                "{" + base + ",\"exp\":1893456900,\"isMaster\":\"true\"}", "{\"exp\":1893456900}")) {
            String token = sign(payload, SECRET);
            assertThatThrownBy(() -> jwtTokenService.decode(token)).isInstanceOf(JwtException.class);
        }
        String forged = sign("{" + base + ",\"exp\":1893456900}", "another-test-secret-at-least-32-bytes");
        assertThatThrownBy(() -> jwtTokenService.decode(forged)).isInstanceOf(JwtException.class);
    }

    @Test
    void currentUserCanBeConstructedWithoutNickname() throws Exception {
        var userId = java.util.UUID.fromString("11111111-1111-4111-8111-111111111111");
        CurrentUser currentUser = CurrentUser.class.getConstructor(java.util.UUID.class, String.class, boolean.class)
                .newInstance(userId, "legacy@example.com", false);
        assertThat(currentUser.userId()).isEqualTo(userId);
        assertThat(currentUser.email()).isEqualTo("legacy@example.com");
        assertThat(currentUser.master()).isFalse();
    }

    @Test
    void currentUserIgnoresLegacyNicknameClaim() throws Exception {
        String payload = "{\"userName\":{},\"email\":\"legacy@example.com\","
                + "\"user_id\":\"11111111-1111-4111-8111-111111111111\",\"exp\":1893456900,\"isMaster\":true}";
        var jwt = jwtTokenService.decode(sign(payload, SECRET));
        CurrentUser currentUser = CurrentUser.from(jwt);
        assertThat(currentUser.userId().toString()).isEqualTo("11111111-1111-4111-8111-111111111111");
        assertThat(currentUser.email()).isEqualTo("legacy@example.com");
        assertThat(currentUser.master()).isTrue();
    }

    @Test
    void jwtSecretIsRequiredAndMustSupportHs256() {
        assertThatThrownBy(() -> new JwtTokenService(properties(""), Clock.systemUTC())).isInstanceOf(
                IllegalStateException.class);
        assertThatThrownBy(() -> new JwtTokenService(properties("short"), Clock.systemUTC())).isInstanceOf(
                IllegalStateException.class);
    }

    private String sign(String payload, String secret) throws Exception {
        var encoder = Base64.getUrlEncoder().withoutPadding();
        String input =
                encoder.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8)) + "."
                        + encoder.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return input + "." + encoder.encodeToString(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
    }
}
