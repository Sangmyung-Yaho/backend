package com.sangmyungyaho.barocare.home.controller;

import com.sangmyungyaho.barocare.global.security.jwt.JwtProvider;
import com.sangmyungyaho.barocare.home.dto.HomeDto;
import com.sangmyungyaho.barocare.home.service.HomeService;
import com.sangmyungyaho.barocare.routine.dto.RoutineDto;
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

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * feat: 홈 대시보드 통합 조회 API 구현 - HomeController 슬라이스 테스트.
 */
@WebMvcTest(HomeController.class)
@AutoConfigureMockMvc(addFilters = false)
class HomeControllerTest {

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
	private HomeService homeService;

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
	void 홈_대시보드를_조회한다() throws Exception {
		HomeDto.WeeklyCheckinSummary weeklyCheckins = new HomeDto.WeeklyCheckinSummary(
				List.of(new HomeDto.WeeklyCheckinDay(LocalDate.of(2026, 8, 16), true)), 1);
		RoutineDto.RoutineResponseDto todayRoutine = new RoutineDto.RoutineResponseDto(
				true, false, 4L, 3L, 75, List.of(), List.of(), List.of());
		HomeDto.DashboardResponse response = new HomeDto.DashboardResponse(weeklyCheckins, null, null, todayRoutine);
		when(homeService.getDashboard(USER_ID)).thenReturn(response);

		mockMvc.perform(get("/api/v1/home"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.is_success").value(true))
				.andExpect(jsonPath("$.data.weekly_checkins.checked_count").value(1))
				.andExpect(jsonPath("$.data.latest_skin_analysis").isEmpty())
				.andExpect(jsonPath("$.data.latest_report").isEmpty())
				.andExpect(jsonPath("$.data.today_routine.today_progress_percent").value(75));
	}
}
