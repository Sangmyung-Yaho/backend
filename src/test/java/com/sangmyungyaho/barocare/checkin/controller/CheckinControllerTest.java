package com.sangmyungyaho.barocare.checkin.controller;

import com.sangmyungyaho.barocare.checkin.dto.CheckinDto;
import com.sangmyungyaho.barocare.checkin.service.CheckinService;
import com.sangmyungyaho.barocare.global.exception.ErrorCode;
import com.sangmyungyaho.barocare.global.exception.GlobalException;
import com.sangmyungyaho.barocare.global.security.jwt.JwtProvider;
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

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * feat: 프론트 화면 연동을 위한 조회 API 구현 - 오늘의 체크인 조회 / 기간별 체크인 조회 컨트롤러 슬라이스 테스트.
 * ReportControllerTest와 동일한 @WebMvcTest 컨벤션을 따른다.
 */
@WebMvcTest(CheckinController.class)
@AutoConfigureMockMvc(addFilters = false)
class CheckinControllerTest {

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
	private CheckinService checkinService;

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
	void 오늘의_체크인을_조회한다() throws Exception {
		CheckinDto.Response response = new CheckinDto.Response(1L, 7.0, 2, 1500, LocalDate.of(2026, 8, 16));
		when(checkinService.getTodayCheckin(USER_ID)).thenReturn(response);

		mockMvc.perform(get("/api/v1/checkins/today"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.is_success").value(true))
				.andExpect(jsonPath("$.data.sleep_hours").value(7.0));
	}

	@Test
	void 오늘의_체크인이_없으면_404와_TODAY_CHECKIN_NOT_FOUND를_반환한다() throws Exception {
		when(checkinService.getTodayCheckin(USER_ID)).thenThrow(new GlobalException(ErrorCode.TODAY_CHECKIN_NOT_FOUND));

		mockMvc.perform(get("/api/v1/checkins/today"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("TODAY_CHECKIN_NOT_FOUND"));
	}

	@Test
	void 기간별_체크인을_조회한다() throws Exception {
		CheckinDto.Response response = new CheckinDto.Response(1L, 7.0, 2, 1500, LocalDate.of(2026, 8, 10));
		when(checkinService.getCheckinsByDateRange(eq(USER_ID), eq(LocalDate.of(2026, 8, 10)), eq(LocalDate.of(2026, 8, 16))))
				.thenReturn(List.of(response));

		mockMvc.perform(get("/api/v1/checkins")
						.param("startDate", "2026-08-10")
						.param("endDate", "2026-08-16"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[0].checked_date").value("2026-08-10"));
	}

	@Test
	void 기간별_체크인_파라미터가_누락되면_400을_반환한다() throws Exception {
		mockMvc.perform(get("/api/v1/checkins").param("startDate", "2026-08-10"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("BAD_REQUEST"));
	}

	@Test
	void 기간별_체크인_날짜_형식이_올바르지_않으면_스택트레이스_노출없이_400을_반환한다() throws Exception {
		mockMvc.perform(get("/api/v1/checkins")
						.param("startDate", "2026-08-9")
						.param("endDate", "2026-08-16"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("BAD_REQUEST"))
				.andExpect(jsonPath("$.trace").doesNotExist());
	}
}
