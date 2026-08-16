package com.sangmyungyaho.barocare.user.controller;

import com.sangmyungyaho.barocare.global.exception.ErrorCode;
import com.sangmyungyaho.barocare.global.exception.GlobalException;
import com.sangmyungyaho.barocare.global.security.jwt.JwtProvider;
import com.sangmyungyaho.barocare.user.dto.OnboardingAgreementRequestDto;
import com.sangmyungyaho.barocare.user.dto.OnboardingStatusResponseDto;
import com.sangmyungyaho.barocare.user.dto.PhotoGuideAgreementRequestDto;
import com.sangmyungyaho.barocare.user.dto.SkinCarePauseReasonRequestDto;
import com.sangmyungyaho.barocare.user.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * feat: 온보딩 데이터 저장 및 완료 처리.
 *
 * OnboardingController의 신규 저장/완료 엔드포인트를 ReportControllerTest/UserControllerTest와
 * 동일한 @WebMvcTest 슬라이스 컨벤션으로 검증한다.
 */
@WebMvcTest(OnboardingController.class)
@AutoConfigureMockMvc(addFilters = false)
class OnboardingControllerTest {

	private static final Long USER_ID = 1L;

	@TestConfiguration
	static class TestConfig implements WebMvcConfigurer {
		@Override
		public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
			resolvers.add(new AuthenticationPrincipalArgumentResolver());
		}
	}

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private UserService userService;

	@MockitoBean
	private JwtProvider jwtProvider;

	@BeforeEach
	void setUpAuthentication() {
		User principal = new User(String.valueOf(USER_ID), "N/A", List.of());
		SecurityContextHolder.getContext().setAuthentication(
				new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
	}

	@AfterEach
	void clearAuthentication() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void 필수_약관_동의를_저장한다() throws Exception {
		mockMvc.perform(post("/api/v1/onboarding/agreements")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"terms_agreed\":true,\"privacy_agreed\":true}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.is_success").value(true));

		verify(userService).updateRequiredAgreements(eq(USER_ID), any(OnboardingAgreementRequestDto.class));
	}

	@Test
	void 필수_약관_동의_저장시_필드가_누락되면_400을_반환한다() throws Exception {
		mockMvc.perform(post("/api/v1/onboarding/agreements")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"terms_agreed\":true}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("BAD_REQUEST"));
	}

	@Test
	void 피부_촬영_가이드_동의를_저장한다() throws Exception {
		mockMvc.perform(post("/api/v1/onboarding/photo-guide-agreement")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"photo_guide_agreed\":true}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.is_success").value(true));

		verify(userService).updatePhotoGuideAgreement(eq(USER_ID), any(PhotoGuideAgreementRequestDto.class));
	}

	@Test
	void 피부_관리_중단_이유를_저장한다() throws Exception {
		mockMvc.perform(post("/api/v1/onboarding/pause-reason")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"skin_care_pause_reason\":\"바빠서 중단했어요\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.is_success").value(true));

		verify(userService).updateSkinCarePauseReason(eq(USER_ID), any(SkinCarePauseReasonRequestDto.class));
	}

	@Test
	void 온보딩_완료를_요청하면_isOnboarded가_true인_상태를_반환한다() throws Exception {
		OnboardingStatusResponseDto.UserInfoDto userInfo = new OnboardingStatusResponseDto.UserInfoDto(
				USER_ID, "닉네임", "KAKAO", true, LocalDateTime.of(2026, 8, 1, 0, 0));
		when(userService.completeOnboarding(USER_ID)).thenReturn(new OnboardingStatusResponseDto(userInfo));

		mockMvc.perform(post("/api/v1/onboarding/complete"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.user.id").value(USER_ID))
				// 참고: OnboardingStatusResponseDto.UserInfoDto의 isOnboarded 필드는 @JsonProperty가 없어
				// Jackson이 boolean getter(isOnboarded())의 "is" 접두사를 제거해 "onboarded"로 직렬화한다
				// (이 이슈 범위 밖의 기존 DTO라 네이밍은 그대로 두고, 실제 응답 형태에 맞춰 테스트했다).
				.andExpect(jsonPath("$.user.onboarded").value(true));
	}

	@Test
	void 필수_약관에_동의하지_않은_상태로_온보딩_완료를_요청하면_400과_ONBOARDING_AGREEMENT_REQUIRED를_반환한다() throws Exception {
		when(userService.completeOnboarding(USER_ID)).thenThrow(new GlobalException(ErrorCode.ONBOARDING_AGREEMENT_REQUIRED));

		mockMvc.perform(post("/api/v1/onboarding/complete"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("ONBOARDING_AGREEMENT_REQUIRED"));
	}
}
