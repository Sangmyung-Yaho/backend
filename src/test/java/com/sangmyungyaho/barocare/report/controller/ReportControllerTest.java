package com.sangmyungyaho.barocare.report.controller;

import com.sangmyungyaho.barocare.global.exception.ErrorCode;
import com.sangmyungyaho.barocare.global.exception.GlobalException;
import com.sangmyungyaho.barocare.global.security.jwt.JwtProvider;
import com.sangmyungyaho.barocare.report.dto.ReportDto;
import com.sangmyungyaho.barocare.report.entity.ReportCauseFactor;
import com.sangmyungyaho.barocare.report.entity.ReportChangeStatus;
import com.sangmyungyaho.barocare.report.service.ReportService;
import com.sangmyungyaho.barocare.skin.entity.SkinAnalysisLevel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

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
 * 담당하며, 이번 이슈에서 그 설정을 변경하지 않았다).
 */
@WebMvcTest(ReportController.class)
@AutoConfigureMockMvc(addFilters = false)
class ReportControllerTest {

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

	@Test
	void date_파라미터_없이_조회하면_전체_목록을_반환한다() throws Exception {
		ReportDto.ReportListItem item = new ReportDto.ReportListItem(
				101L, LocalDate.of(2026, 8, 10), SkinAnalysisLevel.CAUTION, "요약");
		when(reportService.getReports(isNull())).thenReturn(new ReportDto.ListResponse(List.of(item)));

		mockMvc.perform(get("/api/v1/reports"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.is_success").value(true))
				.andExpect(jsonPath("$.data.reports[0].report_id").value(101))
				.andExpect(jsonPath("$.data.reports[0].skin_level").value("CAUTION"));

		verify(reportService).getReports(isNull());
	}

	@Test
	void date_파라미터가_있으면_해당_날짜로_서비스를_호출한다() throws Exception {
		when(reportService.getReports(any())).thenReturn(new ReportDto.ListResponse(List.of()));

		mockMvc.perform(get("/api/v1/reports").param("date", "2026-08-07"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.reports").isEmpty());

		verify(reportService).getReports(eq(LocalDate.of(2026, 8, 7)));
	}

	@Test
	void 조회_결과가_없으면_빈_배열을_반환한다() throws Exception {
		when(reportService.getReports(isNull())).thenReturn(new ReportDto.ListResponse(List.of()));

		mockMvc.perform(get("/api/v1/reports"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.reports").isArray())
				.andExpect(jsonPath("$.data.reports").isEmpty());
	}

	@Test
	void 존재하는_reportId를_조회하면_리포트_상세를_반환한다() throws Exception {
		List<ReportDto.PrimaryCause> causes = List.of(
				new ReportDto.PrimaryCause(ReportCauseFactor.SLEEP, "수면 부족", 5.2, "시간", "설명"));
		ReportDto.SkinChange skinChange = new ReportDto.SkinChange(
				ReportDto.SkinChangeItem.of(1, 0, ReportChangeStatus.IMPROVED),
				ReportDto.SkinChangeItem.of(0, 0, ReportChangeStatus.UNCHANGED)
		);
		ReportDto.Response response = new ReportDto.Response(
				101L, LocalDate.of(2026, 8, 10), skinChange, causes, "요약");
		when(reportService.getReport(101L)).thenReturn(response);

		mockMvc.perform(get("/api/v1/reports/{reportId}", 101L))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.report_id").value(101))
				.andExpect(jsonPath("$.summary").value("요약"))
				.andExpect(jsonPath("$.primary_causes[0].factor").value("SLEEP"));
	}

	@Test
	void 존재하지_않는_reportId면_404와_REPORT_NOT_FOUND를_반환한다() throws Exception {
		when(reportService.getReport(999L)).thenThrow(new GlobalException(ErrorCode.REPORT_NOT_FOUND));

		mockMvc.perform(get("/api/v1/reports/{reportId}", 999L))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("REPORT_NOT_FOUND"));
	}
}
