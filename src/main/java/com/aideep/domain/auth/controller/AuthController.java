package com.aideep.domain.auth.controller;

import com.aideep.domain.auth.config.AuthProperties;
import com.aideep.domain.auth.dto.OAuthResult;
import com.aideep.domain.auth.dto.request.LoginRequest;
import com.aideep.domain.auth.dto.request.OAuthSignupRequest;
import com.aideep.domain.auth.dto.request.PasswordRequest;
import com.aideep.domain.auth.dto.request.RefreshRequest;
import com.aideep.domain.auth.dto.request.SendEmailRequest;
import com.aideep.domain.auth.dto.request.SignupRequest;
import com.aideep.domain.auth.dto.request.VerifyEmailRequest;
import com.aideep.domain.auth.dto.response.MailSentResponse;
import com.aideep.domain.auth.dto.response.OAuthLinkResponse;
import com.aideep.domain.auth.dto.response.TokensResponse;
import com.aideep.domain.auth.exception.AuthError;
import com.aideep.domain.auth.security.CurrentUser;
import com.aideep.domain.auth.service.AuthService;
import com.aideep.domain.auth.service.OAuthService;
import com.aideep.domain.auth.service.RedisAuthStore;
import com.aideep.domain.auth.service.VerificationMailService;
import com.aideep.global.exception.BusinessException;
import com.aideep.global.response.ResponseHandler;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.util.List;

@Slf4j
@RestController
@Tag(name = "Auth 컨트롤러")
@RequestMapping("/v1/aideep/api/auth")
public class AuthController {
    private final AuthService authService;
    private final OAuthService oAuthService;
    private final VerificationMailService verificationMailService;
    private final RedisAuthStore redisAuthStore;
    private final AuthProperties authProperties;

    public AuthController(AuthService authService, OAuthService oAuthService,
                          VerificationMailService verificationMailService, RedisAuthStore redisAuthStore,
                          AuthProperties authProperties) {
        this.authService = authService;
        this.oAuthService = oAuthService;
        this.verificationMailService = verificationMailService;
        this.redisAuthStore = redisAuthStore;
        this.authProperties = authProperties;
    }

    @PostMapping("/login")
    @ResponseStatus(HttpStatus.CREATED)
    @SecurityRequirements
    @Operation(summary = "이메일/비밀번호 로그인")
    public TokensResponse login(@Valid @RequestBody LoginRequest body) {
        return authService.login(body);
    }

    @PostMapping("/refresh")
    @ResponseStatus(HttpStatus.CREATED)
    @SecurityRequirements
    @Operation(summary = "토큰 재발급")
    public TokensResponse refresh(@Valid @RequestBody RefreshRequest body) {
        return authService.refresh(body.refreshToken());
    }

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    @SecurityRequirements
    @Operation(summary = "이메일 인증 후 회원가입")
    public ResponseHandler<String> signup(@Valid @RequestBody SignupRequest body) {
        authService.signup(body);
        return ResponseHandler.success("회원가입 성공");
    }

    @PostMapping("/email/send")
    @ResponseStatus(HttpStatus.CREATED)
    @SecurityRequirements
    @Operation(summary = "이메일 인증번호 전송")
    public MailSentResponse send(@Valid @RequestBody SendEmailRequest body) {
        verificationMailService.send(body.email());
        return new MailSentResponse(true);
    }

    @PostMapping("/email/verify")
    @ResponseStatus(HttpStatus.CREATED)
    @SecurityRequirements
    @Operation(summary = "이메일 인증번호 검증")
    public ResponseHandler<String> verify(@Valid @RequestBody VerifyEmailRequest body) {
        redisAuthStore.verifyCode(body.email(), body.code().stripTrailingZeros().toPlainString());
        return ResponseHandler.success("인증에 성공했습니다.");
    }

    @DeleteMapping("/logout")
    @Operation(summary = "로그아웃")

    public ResponseHandler<String> logout(@AuthenticationPrincipal CurrentUser currentUser,
                                          Authentication authentication) {
        authService.logout(currentUser, (Jwt) authentication.getCredentials());
        return ResponseHandler.success("로그아웃 성공");
    }

    @GetMapping("/google")
    @SecurityRequirements
    @Operation(summary = "Google 로그인 시작")
    @ApiResponse(responseCode = "302", description = "Google 인증 페이지")
    public void google(HttpServletResponse response) throws IOException {
        response.sendRedirect(oAuthService.initiate(null));
    }

    @GetMapping("/google/callback")
    @SecurityRequirements
    @Operation(summary = "Google 로그인/가입/연결 콜백")
    @ApiResponse(responseCode = "302", description = "프론트엔드 인증 결과 페이지")
    public void callback(@RequestParam(required = false) String state, @RequestParam(required = false) String code,
                         @RequestParam(required = false) String error, HttpServletResponse response)
            throws IOException {
        String target;
        try {
            target = resultUrl(oAuthService.callback(state, code, error));
        } catch (BusinessException e) {
            log.debug("OAuth 콜백 거절: status={}", e.getErrorCode().getStatus().value());
            String reason = e.getErrorCode() instanceof AuthError authError ? switch (authError) {
                case OAUTH_STATE_MISSING, OAUTH_STATE_INVALID -> "invalid_state";
                case USER_NOT_FOUND -> "user_not_found";
                case GOOGLE_ACCOUNT_ALREADY_LINKED -> "google_account_already_linked";
                case PROVIDER_ALREADY_LINKED -> "provider_already_linked";
                case OAUTH_EMAIL_CONFLICT -> "email_conflict";
                default -> "oauth_failed";
            } : "oauth_failed";
            target = callbackBase().queryParam("kind", "error").queryParam("reason", reason).build().encode()
                    .toUriString();
        } catch (Exception e) {
            log.error("처리되지 않은 OAuth 콜백 예외", e);
            target = callbackBase().queryParam("kind", "error").queryParam("reason", "oauth_failed").build().encode()
                    .toUriString();
        }
        response.sendRedirect(target);
    }

    @PostMapping("/oauth/signup/complete")
    @ResponseStatus(HttpStatus.CREATED)
    @SecurityRequirements
    @Operation(summary = "Google 신규 회원가입 완료")
    public TokensResponse complete(@Valid @RequestBody OAuthSignupRequest body) {
        return oAuthService.complete(body);
    }

    @GetMapping("/oauth/link/google")
    @Operation(summary = "Google 계정 연결 시작")
    @ApiResponse(responseCode = "302", description = "Google 인증 페이지")
    public void link(@AuthenticationPrincipal CurrentUser currentUser, HttpServletResponse response)
            throws IOException {
        response.sendRedirect(oAuthService.initiate(currentUser.userId()));
    }

    @GetMapping("/oauth/links")
    @Operation(summary = "연결된 OAuth 계정 목록")
    public List<OAuthLinkResponse> links(@AuthenticationPrincipal CurrentUser currentUser) {
        return oAuthService.links(currentUser.userId());
    }

    @DeleteMapping("/oauth/link/{provider}")
    @Operation(summary = "OAuth 계정 연결 해제")
    public ResponseHandler<String> unlink(@AuthenticationPrincipal CurrentUser currentUser,
                                          @PathVariable String provider) {
        oAuthService.unlink(currentUser.userId(), provider);
        return ResponseHandler.success("연동이 해제되었습니다.");
    }

    @PatchMapping("/password")
    @Operation(summary = "비밀번호 설정/변경")
    public ResponseHandler<String> password(@AuthenticationPrincipal CurrentUser currentUser,
                                            @Valid @RequestBody PasswordRequest body) {
        authService.password(currentUser.userId(), body);
        return ResponseHandler.success("비밀번호가 설정되었습니다.");
    }

    private UriComponentsBuilder callbackBase() {
        if (authProperties.frontendUrl() == null || authProperties.frontendUrl().isBlank())
            throw new IllegalStateException("FRONTEND_URL is required");
        return UriComponentsBuilder.fromUriString(
                authProperties.frontendUrl().replaceAll("/+$", "") + "/oauth/callback");
    }

    private String resultUrl(OAuthResult result) {
        var url = callbackBase().queryParam("kind", result.kind());
        if (result.tokens() != null) url.queryParam("accessToken", result.tokens().accessToken())
                .queryParam("refreshToken", result.tokens().refreshToken());
        if (result.ticket() != null) url.queryParam("ticket", result.ticket());
        return url.build().encode().toUriString();
    }
}
