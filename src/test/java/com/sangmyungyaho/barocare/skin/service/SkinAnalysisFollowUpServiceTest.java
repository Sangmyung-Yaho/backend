package com.sangmyungyaho.barocare.skin.service;

import com.sangmyungyaho.barocare.checkin.entity.Checkin;
import com.sangmyungyaho.barocare.checkin.repository.CheckinRepository;
import com.sangmyungyaho.barocare.global.exception.ErrorCode;
import com.sangmyungyaho.barocare.global.exception.GlobalException;
import com.sangmyungyaho.barocare.report.entity.Report;
import com.sangmyungyaho.barocare.report.service.ReportService;
import com.sangmyungyaho.barocare.routine.service.RoutineService;
import com.sangmyungyaho.barocare.skin.entity.ImageQualityRating;
import com.sangmyungyaho.barocare.skin.entity.SkinAnalysis;
import com.sangmyungyaho.barocare.skin.entity.SkinAnalysisLevel;
import com.sangmyungyaho.barocare.skin.entity.SkinImage;
import com.sangmyungyaho.barocare.user.entity.User;
import com.sangmyungyaho.barocare.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * SkinAnalysisService.analyzeSkin()에서 분리된 오늘 리포트/루틴 생성(SkinAnalysisFollowUpService)
 * 단위 테스트. 원래 SkinAnalysisServiceTest에 있던 케이스들을 여기로 옮겨왔다 - 로직 자체는 분리 전과
 * 동일하다. 이 메서드는 (한때 @Async였다가) 다시 동기로 호출된다 - "피부 분석 + 원인 분석 + 오늘의
 * 루틴까지" 완료된 뒤에 POST /skin-analyses가 응답해야 하기 때문이다.
 */
@ExtendWith(MockitoExtension.class)
class SkinAnalysisFollowUpServiceTest {

	private static final Long USER_ID = 1L;

	@Mock
	private CheckinRepository checkinRepository;

	@Mock
	private ReportService reportService;

	@Mock
	private RoutineService routineService;

	@Mock
	private UserRepository userRepository;

	@InjectMocks
	private SkinAnalysisFollowUpService skinAnalysisFollowUpService;

	@Test
	void 오늘_체크인이_없으면_리포트_루틴_생성을_건너뛴다() {
		// 정상적인 플로우라면 체크인이 먼저 끝나 있어야 하지만, 순서를 어기고 사진부터 분석을 시도한
		// 경우를 시뮬레이션한다.
		SkinAnalysis todaySkinAnalysis = analysisWithId(10L);
		when(checkinRepository.findByUserIdAndCheckedDate(eq(USER_ID), any())).thenReturn(Optional.empty());

		skinAnalysisFollowUpService.generateTodayReportAndRoutines(USER_ID, todaySkinAnalysis);

		verifyNoInteractions(reportService, routineService, userRepository);
	}

	@Test
	void 오늘_체크인이_있으면_오늘_리포트와_루틴을_생성하고_스트릭을_갱신한다() {
		// 요구사항 #6: Checkin -> SkinImage -> SkinAnalysis -> Report -> Routine 순서로, 피부 분석
		// 완료 시점에 오늘 Report/Routine 생성까지 이어지는지 검증한다.
		SkinAnalysis todaySkinAnalysis = analysisWithId(10L);
		Checkin todayCheckin = new Checkin(USER_ID, 7.0, 2, 1500, LocalDate.now());
		when(checkinRepository.findByUserIdAndCheckedDate(eq(USER_ID), any())).thenReturn(Optional.of(todayCheckin));
		Report report = mock(Report.class);
		when(reportService.generateTodayReport(eq(USER_ID), eq(todaySkinAnalysis), eq(todayCheckin))).thenReturn(report);
		User user = mock(User.class);
		when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

		skinAnalysisFollowUpService.generateTodayReportAndRoutines(USER_ID, todaySkinAnalysis);

		verify(reportService).generateTodayReport(eq(USER_ID), eq(todaySkinAnalysis), eq(todayCheckin));
		verify(routineService).generateRoutines(eq(USER_ID), eq(todayCheckin), eq(todaySkinAnalysis), eq(report));
		verify(user).recordActivity(any());
	}

	@Test
	void 리포트_생성이_실패하면_루틴_생성은_건너뛰고_예외를_흡수한다() {
		SkinAnalysis todaySkinAnalysis = analysisWithId(10L);
		Checkin todayCheckin = new Checkin(USER_ID, 7.0, 2, 1500, LocalDate.now());
		when(checkinRepository.findByUserIdAndCheckedDate(eq(USER_ID), any())).thenReturn(Optional.of(todayCheckin));
		when(reportService.generateTodayReport(eq(USER_ID), eq(todaySkinAnalysis), eq(todayCheckin)))
				.thenThrow(new GlobalException(ErrorCode.AI_ANALYSIS_FAILED));

		// 핵심 검증: 이 호출 자체가 예외를 밖으로 던지지 않아야 한다(백그라운드 실행이므로 던져봐야
		// 아무도 받지 못하고 로그만 남기는 게 맞다).
		skinAnalysisFollowUpService.generateTodayReportAndRoutines(USER_ID, todaySkinAnalysis);

		verify(routineService, never()).generateRoutines(any(), any(), any(), any());
		verifyNoInteractions(userRepository);
	}

	private SkinAnalysis analysisWithId(Long id) {
		SkinImage skinImage = new SkinImage(USER_ID, "http://example.com/image.jpg", "stored.jpg");
		SkinAnalysis skinAnalysis = new SkinAnalysis(
				USER_ID, skinImage,
				SkinAnalysisLevel.SAFE, List.of(), null,
				SkinAnalysisLevel.SAFE, List.of(), null,
				SkinAnalysisLevel.SAFE,
				ImageQualityRating.GOOD, ImageQualityRating.GOOD,
				ImageQualityRating.GOOD, ImageQualityRating.GOOD,
				"v3"
		);
		ReflectionTestUtils.setField(skinAnalysis, "id", id);
		return skinAnalysis;
	}
}
