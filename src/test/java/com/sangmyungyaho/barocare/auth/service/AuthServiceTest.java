package com.sangmyungyaho.barocare.auth.service;

import com.sangmyungyaho.barocare.auth.dto.LoginResponseDto;
import com.sangmyungyaho.barocare.auth.dto.OAuth2UserInfo;
import com.sangmyungyaho.barocare.global.security.jwt.JwtProvider;
import com.sangmyungyaho.barocare.global.security.repository.RefreshTokenRepository;
import com.sangmyungyaho.barocare.user.entity.Provider;
import com.sangmyungyaho.barocare.user.entity.User;
import com.sangmyungyaho.barocare.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * fix: 기존 인증 및 사용자 데이터 처리 안정화.
 *
 * AuthService.login()이 응답으로 내려주는 isOnboarded 값이 하드코딩된 false가 아니라
 * 실제 User.isOnboarded()를 반영하는지 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

	@Mock
	private OAuth2ClientService oAuth2ClientService;

	@Mock
	private UserRepository userRepository;

	@Mock
	private JwtProvider jwtProvider;

	@Mock
	private RefreshTokenRepository refreshTokenRepository;

	@InjectMocks
	private AuthService authService;

	@Test
	void 신규_사용자는_isNewUser가_true이고_isOnboarded는_false다() {
		OAuth2UserInfo userInfo = kakaoUserInfo("kakao-1", "닉네임");
		when(oAuth2ClientService.getKakaoAccessToken("code")).thenReturn("kakao-access-token");
		when(oAuth2ClientService.getKakaoUserInfo("kakao-access-token")).thenReturn(userInfo);
		when(userRepository.findByProviderAndSocialId(Provider.KAKAO, "kakao-1")).thenReturn(Optional.empty());
		// 실제 JPA(IDENTITY 전략)는 save() 시 전달받은 엔티티에 생성된 id를 채워 넣는다.
		// 순수 Mockito 목에서는 이 동작이 없으므로, AuthService.login()이 save() 직후 user.getId()를
		// 쓰는 부분(RefreshToken 저장 등)에서 NPE가 나지 않도록 동일하게 흉내낸다.
		when(userRepository.save(any())).thenAnswer(invocation -> {
			User savedUser = invocation.getArgument(0);
			ReflectionTestUtils.setField(savedUser, "id", 10L);
			return savedUser;
		});
		when(jwtProvider.createAccessToken(any())).thenReturn("access-token");
		when(jwtProvider.createRefreshToken(any())).thenReturn("refresh-token");

		LoginResponseDto response = authService.login("kakao", "code");

		assertThat(response.isNewUser()).isTrue();
		assertThat(response.isOnboarded()).isFalse();
	}

	@Test
	void 온보딩을_마친_기존_사용자는_isNewUser가_false이고_isOnboarded는_true다() {
		OAuth2UserInfo userInfo = kakaoUserInfo("kakao-1", "닉네임");
		User existingUser = User.builder().provider(Provider.KAKAO).socialId("kakao-1").nickname("닉네임").build();
		existingUser.completeOnboarding(); // fix: 온보딩 완료 이슈 이후 updateProfile()은 더 이상 isOnboarded를 바꾸지 않으므로 완료 API와 동일하게 completeOnboarding()으로 상태를 만든다.
		ReflectionTestUtils.setField(existingUser, "id", 1L);

		when(oAuth2ClientService.getKakaoAccessToken("code")).thenReturn("kakao-access-token");
		when(oAuth2ClientService.getKakaoUserInfo("kakao-access-token")).thenReturn(userInfo);
		when(userRepository.findByProviderAndSocialId(Provider.KAKAO, "kakao-1")).thenReturn(Optional.of(existingUser));
		when(jwtProvider.createAccessToken(any())).thenReturn("access-token");
		when(jwtProvider.createRefreshToken(any())).thenReturn("refresh-token");

		LoginResponseDto response = authService.login("kakao", "code");

		assertThat(response.isNewUser()).isFalse();
		assertThat(response.isOnboarded()).isTrue();
	}

	@Test
	void 온보딩을_마치지_않은_기존_사용자는_isOnboarded가_false다() {
		OAuth2UserInfo userInfo = kakaoUserInfo("kakao-2", "닉네임2");
		User existingUser = User.builder().provider(Provider.KAKAO).socialId("kakao-2").nickname("닉네임2").build();
		ReflectionTestUtils.setField(existingUser, "id", 2L);

		when(oAuth2ClientService.getKakaoAccessToken("code")).thenReturn("kakao-access-token");
		when(oAuth2ClientService.getKakaoUserInfo("kakao-access-token")).thenReturn(userInfo);
		when(userRepository.findByProviderAndSocialId(Provider.KAKAO, "kakao-2")).thenReturn(Optional.of(existingUser));
		when(jwtProvider.createAccessToken(any())).thenReturn("access-token");
		when(jwtProvider.createRefreshToken(any())).thenReturn("refresh-token");

		LoginResponseDto response = authService.login("kakao", "code");

		assertThat(response.isNewUser()).isFalse();
		assertThat(response.isOnboarded()).isFalse();
	}

	private OAuth2UserInfo kakaoUserInfo(String providerId, String nickname) {
		return new OAuth2UserInfo() {
			@Override
			public String getProvider() {
				return "KAKAO";
			}

			@Override
			public String getProviderId() {
				return providerId;
			}

			@Override
			public String getNickname() {
				return nickname;
			}
		};
	}
}
