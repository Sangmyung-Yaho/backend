package com.sangmyungyaho.barocare.report.controller;

import com.sangmyungyaho.barocare.global.exception.ErrorCode;
import com.sangmyungyaho.barocare.global.exception.GlobalException;
import com.sangmyungyaho.barocare.global.security.jwt.JwtProvider;
import com.sangmyungyaho.barocare.report.dto.ReportDto;
import com.sangmyungyaho.barocare.report.entity.ReportCauseFactor;
import com.sangmyungyaho.barocare.report.entity.ReportChangeStatus;
import com.sangmyungyaho.barocare.report.service.ReportService;
import com.sangmyungyaho.barocare.skin.entity.SkinAnalysisLevel;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ISSUE-29: 리포트 보관함(GET /api/v1/reports, GET /api/v1/reports/{reportId}) 컨트롤러 슬라이스 테스트.
 *
 * ReportService는 목(mock)으로 대체해 HTTP 매핑/파라미터 바인딩/응답 직렬화/예외 처리(GlobalExceptionHandler)만
 * 검증한다. SecurityConfig/JwtAuthenticationFilter는 이 슬라이스 컨텍스트에 로드되지 않으므로 별도
 * 인증 없이 요청 가능하다(이 API의 실제 인증 요구는 이미 다른 리포트 엔드포인트와 동일한 SecurityConfig가
 * 담당하며, 이번 이슈에서 그 설정을 변경하지 않았다). 다만 GET /api/v1/reports는 컨트롤러 안에서
 * @AuthenticationPrincipal UserDetails로 userId를 직접 꺼내 쓰는데, @WebMvcTest 슬라이스는 실제
 * SecurityConfig(@EnableWebSecurity)를 로드하지 않아 AuthenticationPrincipalArgumentResolver가
 * 등록되지 않는다 - 그 상태로 두면 Spring MVC가 UserDetails를 @ModelAttribute로 오인해 바인딩을
 * 시도하다 IllegalStateException을 던진다. 아래 TestConfig에서 그 리졸버만 최소로 추가 등록하고,
 * SecurityContextHolder에는 userId만 채운 인증 정보를 수동으로 넣는다.
 */
@WebMvcTest(ReportController.class)
@AutoConfigureMockMvc(addFilters = false)
class ReportControllerTest {

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
	private ReportService reportService;

	// SecurityConfig(@WebMvcTest 슬라이스에도 로드됨)가 JwtAuthenticationFilter를 빈으로 구성하기 위해
	// JwtProvider를 필요로 하므로, 컨텍스트 기동을 위해 목으로 등록한다. addFilters=false로 실제 필터
	// 체인은 요청에 적용되지 않으므로 이 API의 인증 자체를 테스트하는 것은 아니다(기존 SecurityConfig는
	// 그대로 두고 변경하지 않았다).
	@MockitoBean
	private JwtProvider jwtProvider;

	@BeforeEach
	void setUpAuthentication() {
		// addFilters=false라 JwtAuthenticationFilter가 채워주는 인증 정보가 없다. 컨트롤러가
		// @AuthenticationPrincipal로 꺼내 쓰는 UserDetails.getUsername()(=userId 문자열)만 최소한으로 채운다.
		User principal = new User(String.valueOf(USER_ID), "N/A", List.of());
		SecurityContextHolder.getContext().setAuthentication(
				new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
	}

	@AfterEach
	void clearAuthentication() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void 파라미터_없이_조회하면_최근_30일_사용자_범위로_서비스를_호출한다() throws Exception {
		ReportDto.ReportListItem item = new ReportDto.ReportListItem(
				101L, LocalDate.of(2026, 8, 10), SkinAnalysisLevel.CAUTION, "요약");
		when(reportService.getReports(eq(USER_ID), isNull(), isNull(), isNull()))
				.thenReturn(new ReportDto.ListResponse(List.of(item)));

		mockMvc.perform(get("/api/v1/reports"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.is_success").value(true))
				.andExpect(jsonPath("$.data.reports[0].report_id").value(101))
				.andExpect(jsonPath("$.data.reports[0].skin_level").value("CAUTION"));

		verify(reportService).getReports(eq(USER_ID), isNull(), isNull(), isNull());
	}

	@Test
	void date_파라미터가_있으면_해당_날짜로_서비스를_호출한다() throws Exception {
		when(reportService.getReports(eq(USER_ID), any(), isNull(), isNull())).thenReturn(new ReportDto.ListResponse(List.of()));

		mockMvc.perform(get("/api/v1/reports").param("date", "2026-08-07"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.reports").isEmpty());

		verify(reportService).getReports(eq(USER_ID), eq(LocalDate.of(2026, 8, 7)), isNull(), isNull());
	}

	@Test
	void startDate_endDate_파라미터가_있으면_그대로_서비스에_전달한다() throws Exception {
		when(reportService.getReports(eq(USER_ID), isNull(), any(), any())).thenReturn(new ReportDto.ListResponse(List.of()));

		mockMvc.perform(get("/api/v1/reports").param("startDate", "2026-07-01").param("endDate", "2026-07-15"))
				.andExpect(status().isOk());

		verify(reportService).getReports(
				eq(USER_ID), isNull(), eq(LocalDate.of(2026, 7, 1)), eq(LocalDate.of(2026, 7, 15)));
	}

	@Test
	void 조회_결과가_없으면_빈_배열을_반환한다() throws Exception {
		when(reportService.getReports(eq(USER_ID), isNull(), isNull(), isNull())).thenReturn(new ReportDto.ListResponse(List.of()));

		mockMvc.perform(get("/api/v1/reports"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.reports").isArray())
				.andExpect(jsonPath("$.data.reports").isEmpty());
	}

	@Test
	void 존재하는_reportId를_조회하면_리포트_상세를_반환한다() throws Exception {
		List<ReportDto.PrimaryCause> causes = List.of(
				new ReportDto.PrimaryCause(ReportCauseFactor.SLEEP, "수면 부족", 5.2, "시간", "설명",
						6.8, -1.6, com.sangmyungyaho.barocare.report.entity.BaselineType.PERSONAL_AVERAGE));
		ReportDto.SkinChange skinChange = new ReportDto.SkinChange(
				ReportDto.SkinChangeItem.of(1, 0, ReportChangeStatus.IMPROVED),
				ReportDto.SkinChangeItem.of(0, 0, ReportChangeStatus.UNCHANGED)
		);
		ReportDto.Response response = new ReportDto.Response(
				101L, LocalDate.of(2026, 8, 10), skinChange, true, causes, "요약");
		when(reportService.getReport(USER_ID, 101L)).thenReturn(response);

		mockMvc.perform(get("/api/v1/reports/{reportId}", 101L))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.report_id").value(101))
				.andExpect(jsonPath("$.summary").value("요약"))
				.andExpect(jsonPath("$.primary_causes[0].factor").value("SLEEP"));
	}

	@Test
	void 존재하지_않는_reportId면_404와_REPORT_NOT_FOUND를_반환한다() throws Exception {
		when(reportService.getReport(USER_ID, 999L)).thenThrow(new GlobalException(ErrorCode.REPORT_NOT_FOUND));

		mockMvc.perform(get("/api/v1/reports/{reportId}", 999L))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("REPORT_NOT_FOUND"));
	}

	@Test
	void 다른_사용자의_reportId를_조회하면_403과_FORBIDDEN을_반환한다() throws Exception {
		when(reportService.getReport(USER_ID, 55L)).thenThrow(new GlobalException(ErrorCode.FORBIDDEN));

		mockMvc.perform(get("/api/v1/reports/{reportId}", 55L))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
	}
}
