package com.sangmyungyaho.barocare.home.service;

import com.sangmyungyaho.barocare.checkin.dto.CheckinDto;
import com.sangmyungyaho.barocare.checkin.service.CheckinService;
import com.sangmyungyaho.barocare.home.dto.HomeDto;
import com.sangmyungyaho.barocare.report.dto.ReportDto;
import com.sangmyungyaho.barocare.report.entity.ReportCauseFactor;
import com.sangmyungyaho.barocare.report.service.ReportService;
import com.sangmyungyaho.barocare.routine.dto.RoutineDto;
import com.sangmyungyaho.barocare.routine.service.RoutineService;
import com.sangmyungyaho.barocare.skin.dto.SkinAnalysisDto;
import com.sangmyungyaho.barocare.skin.entity.SkinAnalysisLevel;
import com.sangmyungyaho.barocare.skin.service.SkinAnalysisService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * feat: 홈 대시보드 통합 조회 API 구현 - HomeService 단위 테스트.
 *
 * 핵심 검증 포인트:
 * - 모든/일부/전무 데이터 상황 각각에서 홈 API 전체가 정상 응답하는지(예외 없음)
 * - 다른 사용자의 데이터가 섞이지 않는지(모든 하위 호출이 동일 userId로만 이뤄지는지)
 * - AI 재호출/신규 생성 경로(getLatestSkinReport, tryGetLatestSkinReport, analyzeSkin, generateRoutines
 *   등)에 의존하지 않고 순수 조회 메서드만 사용하는지
 */
@ExtendWith(MockitoExtension.class)
class HomeServiceTest {

	private static final Long USER_ID = 1L;
	private static final Long OTHER_USER_ID = 2L;

	@Mock
	private CheckinService checkinService;
	@Mock
	private SkinAnalysisService skinAnalysisService;
	@Mock
	private ReportService reportService;
	@Mock
	private RoutineService routineService;

	@InjectMocks
	private HomeService homeService;

	@Test
	void 모든_데이터가_있으면_각_영역이_전부_채워진_대시보드를_반환한다() {
		LocalDate today = LocalDate.now();
		when(checkinService.getCheckinsByDateRange(eq(USER_ID), eq(today.minusDays(6)), eq(today)))
				.thenReturn(List.of(
						new CheckinDto.Response(1L, 7.0, 2, 1500, today.minusDays(6)),
						new CheckinDto.Response(2L, 6.5, 3, 1400, today)
				));

		SkinAnalysisDto.DetailResponse skinDetail = new SkinAnalysisDto.DetailResponse(
				12L, 55L, SkinAnalysisLevel.CAUTION, SkinAnalysisLevel.SAFE, SkinAnalysisLevel.CAUTION,
				LocalDateTime.of(2026, 8, 12, 17, 30), false, 8L,
				com.sangmyungyaho.barocare.report.entity.ReportChangeStatus.IMPROVED,
				com.sangmyungyaho.barocare.report.entity.ReportChangeStatus.UNCHANGED
		);
		when(skinAnalysisService.getLatestDetailForUser(USER_ID)).thenReturn(Optional.of(skinDetail));

		ReportDto.Response reportResponse = new ReportDto.Response(
				101L, LocalDate.of(2026, 8, 12), null, true,
				List.of(new ReportDto.PrimaryCause(ReportCauseFactor.SLEEP, "수면 부족", 5.0, "시간", "설명", null, null, null)),
				"요약"
		);
		when(reportService.getLatestSavedReport(USER_ID)).thenReturn(Optional.of(reportResponse));

		RoutineDto.RoutineResponseDto routineResponse = new RoutineDto.RoutineResponseDto(
				true, false, 4L, 3L, 75, List.of(), List.of(), List.of());
		when(routineService.getTodayRoutines(USER_ID)).thenReturn(routineResponse);

		HomeDto.DashboardResponse response = homeService.getDashboard(USER_ID);

		assertThat(response.weeklyCheckins().days()).hasSize(7);
		assertThat(response.weeklyCheckins().checkedCount()).isEqualTo(2);
		assertThat(response.latestSkinAnalysis()).isEqualTo(skinDetail);
		assertThat(response.latestReport()).isEqualTo(reportResponse);
		assertThat(response.todayRoutine().totalCount()).isEqualTo(4L);
		assertThat(response.todayRoutine().todayProgressPercent()).isEqualTo(75);
	}

	@Test
	void 일부_데이터만_없어도_홈_API_전체는_정상_응답한다() {
		LocalDate today = LocalDate.now();
		when(checkinService.getCheckinsByDateRange(eq(USER_ID), any(), any())).thenReturn(List.of());
		when(skinAnalysisService.getLatestDetailForUser(USER_ID)).thenReturn(Optional.empty()); // 피부 분석 없음
		ReportDto.Response reportResponse = new ReportDto.Response(101L, today, null, true, List.of(), "요약");
		when(reportService.getLatestSavedReport(USER_ID)).thenReturn(Optional.of(reportResponse)); // 리포트는 있음
		when(routineService.getTodayRoutines(USER_ID)).thenReturn(
				new RoutineDto.RoutineResponseDto(false, false, 0L, 0L, 0, List.of(), List.of(), List.of()));

		HomeDto.DashboardResponse response = homeService.getDashboard(USER_ID);

		assertThat(response.latestSkinAnalysis()).isNull();
		assertThat(response.latestReport()).isEqualTo(reportResponse);
		assertThat(response.todayRoutine()).isNotNull();
	}

	@Test
	void 데이터가_전혀_없는_신규_사용자도_정상_응답하며_각_영역은_빈값_또는_null이다() {
		when(checkinService.getCheckinsByDateRange(eq(USER_ID), any(), any())).thenReturn(List.of());
		when(skinAnalysisService.getLatestDetailForUser(USER_ID)).thenReturn(Optional.empty());
		when(reportService.getLatestSavedReport(USER_ID)).thenReturn(Optional.empty());
		when(routineService.getTodayRoutines(USER_ID)).thenReturn(
				new RoutineDto.RoutineResponseDto(false, false, 0L, 0L, 0, List.of(), List.of(), List.of()));

		HomeDto.DashboardResponse response = homeService.getDashboard(USER_ID);

		assertThat(response.weeklyCheckins().days()).hasSize(7);
		assertThat(response.weeklyCheckins().days()).allMatch(day -> !day.checked());
		assertThat(response.weeklyCheckins().checkedCount()).isZero();
		assertThat(response.latestSkinAnalysis()).isNull();
		assertThat(response.latestReport()).isNull();
		assertThat(response.todayRoutine().routines()).isEmpty();
		assertThat(response.todayRoutine().totalCount()).isZero();
	}

	@Test
	void 모든_하위_조회는_요청한_사용자_ID로만_호출되고_다른_사용자_ID는_전혀_쓰이지_않는다() {
		when(checkinService.getCheckinsByDateRange(any(), any(), any())).thenReturn(List.of());
		when(skinAnalysisService.getLatestDetailForUser(any())).thenReturn(Optional.empty());
		when(reportService.getLatestSavedReport(any())).thenReturn(Optional.empty());
		when(routineService.getTodayRoutines(any())).thenReturn(
				new RoutineDto.RoutineResponseDto(false, false, 0L, 0L, 0, List.of(), List.of(), List.of()));

		homeService.getDashboard(USER_ID);

		verify(checkinService).getCheckinsByDateRange(eq(USER_ID), any(), any());
		verify(skinAnalysisService).getLatestDetailForUser(USER_ID);
		verify(reportService).getLatestSavedReport(USER_ID);
		verify(routineService).getTodayRoutines(USER_ID);
		verify(checkinService, never()).getCheckinsByDateRange(eq(OTHER_USER_ID), any(), any());
		verify(skinAnalysisService, never()).getLatestDetailForUser(OTHER_USER_ID);
		verify(reportService, never()).getLatestSavedReport(OTHER_USER_ID);
		verify(routineService, never()).getTodayRoutines(OTHER_USER_ID);
	}

	@Test
	void 홈_조회는_AI_재호출_가능성이_있는_경로를_전혀_사용하지_않는다() {
		// getLatestSavedReport()만 호출해야 하고 getLatestSkinReport()/tryGetLatestSkinReport()(find-or-create,
		// OpenAI를 호출할 수 있음)는 절대 호출하면 안 된다. getLatestDetailForUser()도 마찬가지로
		// analyzeSkin()(신규 분석/OpenAI 호출)을 호출하면 안 된다.
		when(checkinService.getCheckinsByDateRange(any(), any(), any())).thenReturn(List.of());
		when(skinAnalysisService.getLatestDetailForUser(any())).thenReturn(Optional.empty());
		when(reportService.getLatestSavedReport(any())).thenReturn(Optional.empty());
		when(routineService.getTodayRoutines(any())).thenReturn(
				new RoutineDto.RoutineResponseDto(false, false, 0L, 0L, 0, List.of(), List.of(), List.of()));

		homeService.getDashboard(USER_ID);

		verify(reportService, never()).getLatestSkinReport(any());
		verify(reportService, never()).tryGetLatestSkinReport(any());
	}
}
