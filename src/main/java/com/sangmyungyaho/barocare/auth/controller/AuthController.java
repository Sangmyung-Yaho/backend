package com.sangmyungyaho.barocare.auth.controller;

import com.sangmyungyaho.barocare.auth.dto.LoginResponseDto;
import com.sangmyungyaho.barocare.auth.service.AuthService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;

@Tag(name = "Auth", description = "소셜 로그인 및 인증 관련 API")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Value("${oauth2.frontend.redirect-uri}")
    private String frontendRedirectUri;

    @Value("${oauth2.kakao.client-id}")
    private String kakaoClientId;

    @Value("${oauth2.kakao.redirect-uri}")
    private String kakaoRedirectUri;

    @Value("${oauth2.google.client-id}")
    private String googleClientId;

    @Value("${oauth2.google.redirect-uri}")
    private String googleRedirectUri;

    @Operation(summary = "소셜 로그인 화면으로 이동", description = "카카오 또는 구글 로그인 화면으로 리다이렉트합니다. provider에 kakao 또는 google을 입력하세요.")
    @GetMapping("/oauth/{provider}")
    public void redirectToSocialLogin(
            @PathVariable String provider,
            HttpServletResponse response) throws IOException {

        String redirectUrl;

        if ("kakao".equalsIgnoreCase(provider)) {
            redirectUrl = UriComponentsBuilder
                    .fromUriString("https://kauth.kakao.com/oauth/authorize")
                    .queryParam("response_type", "code")
                    .queryParam("client_id", kakaoClientId)
                    .queryParam("redirect_uri", kakaoRedirectUri)
                    .queryParam("prompt", "login")
                    .build().toUriString();
        } else if ("google".equalsIgnoreCase(provider)) {
            redirectUrl = UriComponentsBuilder
                    .fromUriString("https://accounts.google.com/o/oauth2/v2/auth")
                    .queryParam("response_type", "code")
                    .queryParam("client_id", googleClientId)
                    .queryParam("redirect_uri", googleRedirectUri)
                    .queryParam("scope", "openid email profile")
                    .build().toUriString();
        } else {
            throw new IllegalArgumentException("지원하지 않는 소셜 플랫폼입니다.");
        }

        response.sendRedirect(redirectUrl);
    }

    @Operation(summary = "소셜 로그인 콜백 처리", description = "카카오/구글 로그인 후 인가 코드를 받아 백엔드에서 토큰을 교환하고 프론트엔드로 리다이렉트합니다.")
    @GetMapping("/oauth/{provider}/callback")
    public void loginCallback(
            @PathVariable String provider,
            @RequestParam String code,
            HttpServletResponse response) throws IOException {

        LoginResponseDto loginResult = authService.login(provider, code);

        String redirectUrl = UriComponentsBuilder.fromUriString(frontendRedirectUri)
                .queryParam("accessToken", loginResult.getAccessToken())
                .queryParam("refreshToken", loginResult.getRefreshToken())
                .queryParam("isNewUser", loginResult.isNewUser())
                .queryParam("isOnboarded", loginResult.isOnboarded())
                .build().toUriString();

        response.sendRedirect(redirectUrl);
    }
}
