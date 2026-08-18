package com.sangmyungyaho.barocare.routine.service;

import com.sangmyungyaho.barocare.checkin.entity.Checkin;
import com.sangmyungyaho.barocare.checkin.repository.CheckinRepository;
import com.sangmyungyaho.barocare.report.entity.Report;
import com.sangmyungyaho.barocare.report.entity.ReportCauseFactor;
import com.sangmyungyaho.barocare.report.service.ReportService;
import com.sangmyungyaho.barocare.routine.entity.Routine;
import com.sangmyungyaho.barocare.routine.repository.RoutineRepository;
import com.sangmyungyaho.barocare.skin.entity.SkinAnalysis;
import com.sangmyungyaho.barocare.skin.entity.SkinAnalysisLevel;
import com.sangmyungyaho.barocare.user.entity.Provider;
import com.sangmyungyaho.barocare.user.entity.User;
import com.sangmyungyaho.barocare.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * "오늘의 케어 및 루틴 연동" 단위 테스트.
 *
 * 생성 트리거는 더 이상 체크인 저장이 아니라 SkinAnalysisService.analyzeSkin() 완료 시점이며(오늘
 * SkinAnalysis/오늘 Report를 이미 인자로 받는다), 원인 후보는 reportService.tryGetLatestSkinReport()로
 * 다시 조회하지 않고 방금 생성/재사용된 Report에서 직접(getPrimaryCauseFactors) 가져온다. 같은 날
 * 두 번째로 호출되면(재분석 등) 이미 루틴이 있으므로 재생성하지 않는다(멱등성).
 */
@ExtendWith(MockitoExtension.class)
class RoutineServiceTest {

	private static final Long USER_ID = 1L;

	@Mock
	private RoutineRepository routineRepository;
	@Mock
	private CheckinRepository checkinRepository;
	@Mock
	private UserRepository userRepository;
	@Mock
	private ReportService reportService;

	@InjectMocks
	private RoutineService routineService;

	@Test
	void 오늘의_루틴_조회는_저장된_루틴만_읽고_원인_분석_AI를_전혀_호출하지_않는다() {
		// "오늘의 케어 추천 조회"는 GET마다 OpenAI를 재호출하면 안 된다는 요구사항의 핵심 검증.
		// getTodayRoutines()는 이미 저장된 Routine만 읽어야 하며 reportService(원인 분석/AI 호출 경로)와는
		// 전혀 상호작용하지 않아야 한다.
		when(checkinRepository.existsByUserIdAndCheckedDate(USER_ID, LocalDate.now())).thenReturn(true);
		when(routineRepository.countByUserIdAndRoutineDate(USER_ID, LocalDate.now())).thenReturn(2L);
		when(routineRepository.countByUserIdAndRoutineDateAndIsCompletedTrue(USER_ID, LocalDate.now())).thenReturn(1L);
		Routine routine = new Routine(USER_ID, "수면", "저녁 시간에 카페인 피하기", "저강도", LocalDate.now(), 1);
		when(routineRepository.findAllByUserIdAndRoutineDate(USER_ID, LocalDate.now())).thenReturn(List.of(routine));

		var response = routineService.getTodayRoutines(USER_ID);

		assertThat(response.isCheckinCompleted()).isTrue();
		assertThat(response.totalCount()).isEqualTo(2L);
		assertThat(response.completedCount()).isEqualTo(1L);
		assertThat(response.todayProgressPercent()).isEqualTo(50);
		assertThat(response.routines()).hasSize(1);
		verifyNoInteractions(reportService, userRepository);
	}

	@Test
	void 원인_후보가_없으면_기존_규칙_기반_루틴만_그대로_생성된다() {
		mockUser(2000);
		when(reportService.getPrimaryCauseFactors(any())).thenReturn(List.of());

		// 수면 6.5h(저강도 구간), 수분 1900ml(목표 2000ml의 95%, 임계값 미달 아님 -> 미생성), 스트레스 2(구간 미달 -> 미생성)
		Checkin checkin = checkin(6.5, 2, 1900);
		SkinAnalysis skinAnalysis = skinAnalysisWithLevel(SkinAnalysisLevel.SAFE);

		routineService.generateRoutines(USER_ID, checkin, skinAnalysis, mockReport());

		List<Routine> saved = captureSavedRoutines();
		assertThat(saved).hasSize(1);
		assertThat(saved.get(0).getCategory()).isEqualTo("수면");
		assertThat(saved.get(0).getIntensity()).isEqualTo("저강도");
	}

	@Test
	void 오늘_Report에_SLEEP이_포함되면_수면_저강도_루틴이_적극개입으로_격상된다() {
		mockUser(2000);
		when(reportService.getPrimaryCauseFactors(any())).thenReturn(List.of(ReportCauseFactor.SLEEP));

		Checkin checkin = checkin(6.5, 2, 1900); // 수면만 "저강도"로 생성되는 조건
		SkinAnalysis skinAnalysis = skinAnalysisWithLevel(SkinAnalysisLevel.SAFE);

		routineService.generateRoutines(USER_ID, checkin, skinAnalysis, mockReport());

		List<Routine> saved = captureSavedRoutines();
		assertThat(saved).hasSize(1);
		assertThat(saved.get(0).getCategory()).isEqualTo("수면");
		assertThat(saved.get(0).getIntensity()).isEqualTo("적극개입");
	}

	@Test
	void 오늘_Report에_없는_요인의_저강도_루틴은_격상되지_않는다() {
		mockUser(2000);
		// STRESS만 원인으로 확인됨 -> 수면 루틴은 그대로 저강도 유지
		when(reportService.getPrimaryCauseFactors(any())).thenReturn(List.of(ReportCauseFactor.STRESS));

		Checkin checkin = checkin(6.5, 2, 1900);
		SkinAnalysis skinAnalysis = skinAnalysisWithLevel(SkinAnalysisLevel.SAFE);

		routineService.generateRoutines(USER_ID, checkin, skinAnalysis, mockReport());

		List<Routine> saved = captureSavedRoutines();
		assertThat(saved).hasSize(1);
		assertThat(saved.get(0).getIntensity()).isEqualTo("저강도");
	}

	@Test
	void 원인_요인_조회가_예외를_던져도_루틴_생성_자체는_성공한다() {
		mockUser(2000);
		when(reportService.getPrimaryCauseFactors(any())).thenThrow(new RuntimeException("예상치 못한 오류"));

		Checkin checkin = checkin(6.5, 2, 1900);
		SkinAnalysis skinAnalysis = skinAnalysisWithLevel(SkinAnalysisLevel.SAFE);

		routineService.generateRoutines(USER_ID, checkin, skinAnalysis, mockReport());

		List<Routine> saved = captureSavedRoutines();
		assertThat(saved).hasSize(1);
		assertThat(saved.get(0).getIntensity()).isEqualTo("저강도"); // 격상되지 않고 기존 규칙 그대로
	}

	@Test
	void 오늘_SkinAnalysis가_DANGER면_피부_루틴이_최우선으로_추가된다() {
		mockUser(2000);
		when(reportService.getPrimaryCauseFactors(any())).thenReturn(List.of());

		Checkin checkin = checkin(8.0, 1, 2000); // 다른 요인은 전부 양호 -> 피부 루틴만 생성
		SkinAnalysis skinAnalysis = skinAnalysisWithLevel(SkinAnalysisLevel.DANGER);

		routineService.generateRoutines(USER_ID, checkin, skinAnalysis, mockReport());

		List<Routine> saved = captureSavedRoutines();
		assertThat(saved).hasSize(1);
		assertThat(saved.get(0).getCategory()).isEqualTo("피부");
		assertThat(saved.get(0).getIntensity()).isEqualTo("적극개입");
	}

	@Test
	void 생성된_루틴에는_예상_소요시간이_함께_저장된다() {
		mockUser(2000);
		when(reportService.getPrimaryCauseFactors(any())).thenReturn(List.of());

		// 수면 6.5h(저강도 구간)만 생성되는 조건(기존 "원인_후보가_없으면..." 테스트와 동일한 입력).
		Checkin checkin = checkin(6.5, 2, 1900);
		SkinAnalysis skinAnalysis = skinAnalysisWithLevel(SkinAnalysisLevel.SAFE);

		routineService.generateRoutines(USER_ID, checkin, skinAnalysis, mockReport());

		List<Routine> saved = captureSavedRoutines();
		assertThat(saved).hasSize(1);
		assertThat(saved.get(0).getEstimatedMinutes()).isNotNull().isPositive();
	}

	@Test
	void 오늘자_루틴이_이미_있으면_재생성하지_않는다() {
		// 멱등성: 같은 날 피부 분석 API가 여러 번 호출돼도(재분석 등) 루틴이 중복 생성되면 안 된다.
		when(routineRepository.existsByUserIdAndRoutineDate(USER_ID, LocalDate.of(2026, 8, 10))).thenReturn(true);

		Checkin checkin = checkin(6.5, 2, 1900);
		SkinAnalysis skinAnalysis = skinAnalysisWithLevel(SkinAnalysisLevel.SAFE);

		routineService.generateRoutines(USER_ID, checkin, skinAnalysis, mockReport());

		verify(routineRepository, never()).saveAll(any());
		verifyNoInteractions(userRepository, reportService);
	}

	private void mockUser(int waterGoalMl) {
		User user = User.builder().provider(Provider.KAKAO).socialId("kakao-1").nickname("닉네임").build();
		ReflectionTestUtils.setField(user, "id", USER_ID);
		ReflectionTestUtils.setField(user, "waterGoalMl", waterGoalMl);
		when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
	}

	private Checkin checkin(double sleepHours, int stressLevel, int waterIntakeMl) {
		return new Checkin(USER_ID, sleepHours, stressLevel, waterIntakeMl, LocalDate.of(2026, 8, 10));
	}

	private SkinAnalysis skinAnalysisWithLevel(SkinAnalysisLevel level) {
		SkinAnalysis skinAnalysis = mock(SkinAnalysis.class);
		lenient().when(skinAnalysis.getSkinLevel()).thenReturn(level);
		return skinAnalysis;
	}

	private Report mockReport() {
		Report report = mock(Report.class);
		lenient().when(report.getId()).thenReturn(999L);
		return report;
	}

	private List<Routine> captureSavedRoutines() {
		ArgumentCaptor<List<Routine>> captor = ArgumentCaptor.forClass(List.class);
		verify(routineRepository).saveAll(captor.capture());
		return captor.getValue();
	}
}
