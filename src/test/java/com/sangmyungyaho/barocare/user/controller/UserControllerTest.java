package com.sangmyungyaho.barocare.user.controller;

import com.sangmyungyaho.barocare.global.exception.ErrorCode;
import com.sangmyungyaho.barocare.global.exception.GlobalException;
import com.sangmyungyaho.barocare.global.security.jwt.JwtProvider;
import com.sangmyungyaho.barocare.user.dto.ProfileReadResponseDto;
import com.sangmyungyaho.barocare.user.entity.SkinType;
import com.sangmyungyaho.barocare.user.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * fix: 기존 인증 및 사용자 데이터 처리 안정화.
 *
 * UserService가 사용자 미존재 시 던지는 예외가 GlobalExceptionHandler를 거쳐 프로젝트 표준
 * 에러 응답 형식({"error":{"code":...,"message":...}})의 404로 내려가는지 컨트롤러 레벨에서 검증한다.
 * (ReportControllerTest와 동일한 @WebMvcTest 슬라이스 컨벤션을 따른다.)
 */
@WebMvcTest(UserController.class)
@AutoConfigureMockMvc(addFilters = false)
class UserControllerTest {

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
	void 존재하는_사용자면_프로필을_정상_조회한다() throws Exception {
		ProfileReadResponseDto response = new ProfileReadResponseDto(
				"닉네임", 170.0, 60.0, SkinType.DRY, true, 2000);
		when(userService.getProfile(eq(USER_ID))).thenReturn(response);

		mockMvc.perform(get("/api/v1/users/profile"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.is_success").value(true))
				.andExpect(jsonPath("$.data.nickname").value("닉네임"))
				.andExpect(jsonPath("$.data.water_goal_ml").value(2000));
	}

	@Test
	void 존재하지_않는_사용자면_표준_형식의_404_USER_NOT_FOUND를_반환한다() throws Exception {
		when(userService.getProfile(eq(USER_ID))).thenThrow(new GlobalException(ErrorCode.USER_NOT_FOUND));

		mockMvc.perform(get("/api/v1/users/profile"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("USER_NOT_FOUND"))
				.andExpect(jsonPath("$.error.message").value("존재하지 않는 사용자입니다."));
	}
}
