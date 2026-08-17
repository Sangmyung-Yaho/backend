package com.sangmyungyaho.barocare.auth.controller;

import com.sangmyungyaho.barocare.auth.dto.LoginResponseDto;
import com.sangmyungyaho.barocare.auth.service.AuthService;
import com.sangmyungyaho.barocare.global.exception.ErrorCode;
import com.sangmyungyaho.barocare.global.exception.GlobalException;
import com.sangmyungyaho.barocare.global.security.jwt.JwtProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * test: 전체 사용자 플로우 E2E 검증 - 소셜 로그인 콜백의 accessToken 발급/리다이렉트 흐름 검증.
 * 실제 카카오/구글 API 호출은 외부 의존이라 AuthService를 목으로 대체한다(토큰 발급 로직 자체는
 * AuthServiceTest가 이미 단위 테스트로 검증함). 이 테스트는 컨트롤러가 그 결과를 프론트 리다이렉트
 * URL에 올바른 쿼리 파라미터로 실어 보내는지만 검증한다.
 */
@WebMvcTest(AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
class AuthControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private AuthService authService;

	@MockitoBean
	private JwtProvider jwtProvider;

	@Test
	void 카카오_로그인_화면으로_리다이렉트한다() throws Exception {
		mockMvc.perform(get("/api/v1/auth/oauth/kakao"))
				.andExpect(status().is3xxRedirection())
				.andExpect(header().string("Location", org.hamcrest.Matchers.containsString("kauth.kakao.com")));
	}

	@Test
	void 지원하지_않는_provider로_로그인_화면_요청시_400을_반환한다() throws Exception {
		mockMvc.perform(get("/api/v1/auth/oauth/naver"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void 콜백_처리_성공시_accessToken_refreshToken_isNewUser_isOnboarded를_담아_프론트로_리다이렉트한다() throws Exception {
		when(authService.login(eq("kakao"), eq("auth-code")))
				.thenReturn(new LoginResponseDto("access-token-value", "refresh-token-value", true, false));

		mockMvc.perform(get("/api/v1/auth/oauth/kakao/callback").param("code", "auth-code"))
				.andExpect(status().is3xxRedirection())
				.andExpect(header().string("Location", org.hamcrest.Matchers.allOf(
						org.hamcrest.Matchers.containsString("accessToken=access-token-value"),
						org.hamcrest.Matchers.containsString("refreshToken=refresh-token-value"),
						org.hamcrest.Matchers.containsString("isNewUser=true"),
						org.hamcrest.Matchers.containsString("isOnboarded=false")
				)));
	}

	@Test
	void 콜백_처리시_지원하지_않는_provider면_400을_반환한다() throws Exception {
		when(authService.login(eq("naver"), eq("auth-code")))
				.thenThrow(new GlobalException(ErrorCode.OAUTH_PROVIDER_NOT_SUPPORTED));

		mockMvc.perform(get("/api/v1/auth/oauth/naver/callback").param("code", "auth-code"))
				.andExpect(status().isBadRequest());
	}
}
