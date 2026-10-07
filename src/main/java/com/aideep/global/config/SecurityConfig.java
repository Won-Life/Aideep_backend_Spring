package com.aideep.global.config;

import com.aideep.domain.auth.entity.AuthUser;
import com.aideep.domain.auth.repository.AuthUserRepository;
import com.aideep.domain.auth.security.UserDetail;
import com.aideep.domain.auth.service.JwtTokenService;
import com.aideep.domain.auth.service.RedisAuthStore;
import com.aideep.global.exception.BusinessException;
import com.aideep.global.exception.GlobalErrorCode;
import com.aideep.global.response.ResponseHandler;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@Slf4j
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    static final String WEBHOOK_PATH_PATTERN = "/v1/aideep/api/webhooks/**";

    @Bean
    public JwtDecoder jwtDecoder(JwtTokenService jwtTokenService, RedisAuthStore redisAuthStore) {
        return token -> {
            Jwt jwt = jwtTokenService.decode(token);
            try {
                redisAuthStore.validateToken(token, jwt.getClaimAsString("user_id"),
                        Boolean.TRUE.equals(jwt.getClaims().get("isMaster")));
            } catch (BusinessException e) {
                throw new BadJwtException(e.getMessage());
            }
            return jwt;
        };
    }

    /**
     * Recall 웹훅은 사용자 JWT가 아니라 본문 서명으로 인증하므로 기본 체인의 {@code authenticated()}에서 분리한다. 서명 검증은
     * {@code RecallWebhookVerifier}가 컨트롤러에서 수행한다.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain webhookFilterChain(HttpSecurity httpSecurity) throws Exception {
        httpSecurity.securityMatcher(WEBHOOK_PATH_PATTERN).csrf(csrf -> csrf.disable())
                .cors(cors -> cors.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .httpBasic(basic -> basic.disable()).formLogin(form -> form.disable())
                .logout(logout -> logout.disable());
        return httpSecurity.build();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity httpSecurity, ObjectMapper objectMapper, JwtDecoder jwtDecoder,
                                           AuthUserRepository authUserRepository)
            throws Exception {
        httpSecurity.csrf(csrf -> csrf.disable()).cors(cors -> cors.configurationSource(corsSource()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable()).authorizeHttpRequests(
                        auth -> auth.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                                .requestMatchers(org.springframework.http.HttpMethod.POST, "/v1/aideep/api/auth/login",

                                        "/v1/aideep/api/auth/refresh", "/v1/aideep/api/auth/signup",
                                        "/v1/aideep/api/auth/email/send", "/v1/aideep/api/auth/email/verify",
                                        "/v1/aideep/api/auth/oauth/signup/complete").permitAll()
                                .requestMatchers(org.springframework.http.HttpMethod.GET, "/v1/aideep/api/auth/google",
                                        "/v1/aideep/api/auth/google/callback").permitAll().anyRequest().authenticated())
                .exceptionHandling(errors -> errors.authenticationEntryPoint(
                                (request, response, error) -> writeError(response, objectMapper, 401))
                        .accessDeniedHandler((request, response, error) -> writeError(response, objectMapper, 403)))
                .oauth2ResourceServer(resource -> resource.bearerTokenResolver(SecurityConfig::resolveToken)
                        .jwt(jwt -> jwt.decoder(jwtDecoder)
                                .jwtAuthenticationConverter(token -> authentication(token, authUserRepository)))
                        .authenticationEntryPoint((request, response, error) -> writeError(response, objectMapper, 401))
                        .accessDeniedHandler((request, response, error) -> writeError(response, objectMapper, 403)))
                .httpBasic(basic -> basic.disable()).formLogin(form -> form.disable())
                .logout(logout -> logout.disable());
        httpSecurity.addFilterBefore(new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                try {
                    chain.doFilter(request, response);
                } catch (DataAccessException e) {
                    log.error("인증 저장소 장애", e);
                    response.setStatus(500);
                    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    objectMapper.writeValue(response.getWriter(),
                            ResponseHandler.fail(GlobalErrorCode.INTERNAL_SERVER_ERROR, null));
                }
            }
        }, BearerTokenAuthenticationFilter.class);
        return httpSecurity.build();
    }

    private static AbstractAuthenticationToken authentication(Jwt jwt, AuthUserRepository authUserRepository) {
        boolean master = Boolean.TRUE.equals(jwt.getClaims().get("isMaster"));
        var roles = master ? List.of(new SimpleGrantedAuthority("ROLE_USER"),
                new SimpleGrantedAuthority("ROLE_MASTER")) : List.of(new SimpleGrantedAuthority("ROLE_USER"));
        AuthUser authUser = authUserRepository
                .findByIdAndDeletedAtIsNull(UUID.fromString(jwt.getClaimAsString("user_id")))
                .orElseThrow(() -> new OAuth2AuthenticationException(new OAuth2Error("invalid_token")));
        return new CurrentUserAuthenticationToken(UserDetail.from(authUser, master), jwt, roles);
    }

    static String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String token = header.substring(7).trim();
            if (token.isEmpty() || token.chars().anyMatch(Character::isWhitespace))
                throw new OAuth2AuthenticationException(new OAuth2Error("invalid_token"));
            return token;
        }
        String token = request.getParameter("token");
        return token == null || token.isBlank() ? null : token;
    }

    private CorsConfigurationSource corsSource() {
        var config = new CorsConfiguration();
        config.setAllowedOrigins(List.of("*"));
        config.setAllowedMethods(List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    private void writeError(HttpServletResponse response, ObjectMapper objectMapper, int status) throws IOException {
        log.debug("인증·권한 오류: status={}", status);
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        var error = status == 401 ? GlobalErrorCode.UNAUTHORIZED : GlobalErrorCode.FORBIDDEN;
        objectMapper.writeValue(response.getWriter(), ResponseHandler.fail(error, null));
    }
}
