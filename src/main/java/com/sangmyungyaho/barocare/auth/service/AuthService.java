package com.sangmyungyaho.barocare.auth.service;

import com.sangmyungyaho.barocare.auth.dto.LoginResponseDto;
import com.sangmyungyaho.barocare.auth.dto.OAuth2UserInfo;
import com.sangmyungyaho.barocare.user.entity.Provider;
import com.sangmyungyaho.barocare.user.entity.User;
import com.sangmyungyaho.barocare.user.entity.UserStatus;
import com.sangmyungyaho.barocare.user.repository.UserRepository;
import com.sangmyungyaho.barocare.global.security.entity.RefreshToken;
import com.sangmyungyaho.barocare.global.security.jwt.JwtProvider;
import com.sangmyungyaho.barocare.global.security.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final OAuth2ClientService oAuth2ClientService;
    private final UserRepository userRepository;
    private final JwtProvider jwtProvider;
    private final RefreshTokenRepository refreshTokenRepository;

    @Transactional
    public LoginResponseDto login(String providerStr, String code) {
        OAuth2UserInfo userInfo = null;

        if ("kakao".equalsIgnoreCase(providerStr)) {
            String kakaoAccessToken = oAuth2ClientService.getKakaoAccessToken(code);
            userInfo = oAuth2ClientService.getKakaoUserInfo(kakaoAccessToken);
        } else if ("google".equalsIgnoreCase(providerStr)) {
            String googleIdToken = oAuth2ClientService.getGoogleIdToken(code);
            userInfo = oAuth2ClientService.getGoogleUserInfo(googleIdToken);
        } else {
            throw new IllegalArgumentException("지원하지 않는 소셜 플랫폼입니다.");
        }

        boolean isNewUser = false;
        User user = userRepository.findByProviderAndSocialId(
                Provider.valueOf(userInfo.getProvider()),
                userInfo.getProviderId()
        ).orElse(null);

        if (user == null) {
            user = User.builder()
                    .provider(Provider.valueOf(userInfo.getProvider()))
                    .socialId(userInfo.getProviderId())
                    .nickname(userInfo.getNickname())
                    .build();
            userRepository.save(user);
            isNewUser = true;
        }

        String accessToken = jwtProvider.createAccessToken(user.getId());
        String refreshToken = jwtProvider.createRefreshToken(user.getId());

        RefreshToken redisToken = RefreshToken.builder()
                .id(user.getId().toString())
                .refreshToken(refreshToken)
                .expiration(1209600L) // 14일
                .build();
        refreshTokenRepository.save(redisToken);

        return new LoginResponseDto(accessToken, refreshToken, isNewUser, false);
    }

    @Transactional
    public void logout(Long userId) {
        refreshTokenRepository.deleteById(userId.toString());
    }
}
