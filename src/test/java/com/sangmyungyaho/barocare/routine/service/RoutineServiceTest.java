package com.sangmyungyaho.barocare.routine.service;

import com.sangmyungyaho.barocare.checkin.entity.Checkin;
import com.sangmyungyaho.barocare.checkin.repository.CheckinRepository;
import com.sangmyungyaho.barocare.report.dto.ReportDto;
import com.sangmyungyaho.barocare.report.entity.ReportCauseFactor;
import com.sangmyungyaho.barocare.report.service.ReportService;
import com.sangmyungyaho.barocare.routine.entity.Routine;
import com.sangmyungyaho.barocare.routine.repository.RoutineRepository;
import com.sangmyungyaho.barocare.skin.entity.SkinAnalysis;
import com.sangmyungyaho.barocare.skin.entity.SkinAnalysisLevel;
import com.sangmyungyaho.barocare.skin.repository.SkinAnalysisRepository;
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
import static org.mockito.Mockito.when;

/**
 * feat: 개인화 피부 원인 분석 및 케어 연동 로직 구현 - "오늘의 케어 및 루틴 연동" 단위 테스트.
 *
 * 기존 규칙 기반 루틴 생성(수면/수분/스트레스/피부 위험군)은 그대로 유지되는지(회귀 확인),
 * 그 위에 원인 리포트의 primaryCauses가 있을 때만 해당 요인의 "저강도" 루틴이 "적극개입"으로
 * 격상되는지, 원인 리포트를 구할 수 없거나 조회가 실패해도 루틴 생성 자체는 항상 성공하는지를 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class RoutineServiceTest {

	private static final Long USER_ID = 1L;

	@Mock
	private RoutineRepository routineRepository;
	@Mock
	private CheckinRepository checkinRepository;
	@Mock
	private SkinAnalysisRepository skinAnalysisRepository;
	@Mock
	private UserRepository userRepository;
	@Mock
	private ReportService reportService;

	@InjectMocks
	private RoutineService routineService;

	@Test
	void 원인_리포트가_없으면_기존_규칙_기반_루틴만_그대로_생성된다() {
		mockUser(2000);
		when(skinAnalysisRepository.findTop2ByUserIdOrderByAnalyzedAtDesc(USER_ID)).thenReturn(List.of());
		when(reportService.tryGetLatestSkinReport(USER_ID)).thenReturn(Optional.empty());

		// 수면 6.5h(저강도 구간), 수분 1900ml(목표 2000ml의 95%, 임계값 미달 아님 -> 미생성), 스트레스 2(구간 미달 -> 미생성)
		Checkin checkin = checkin(6.5, 2, 1900);

		routineService.generateRoutines(USER_ID, checkin);

		List<Routine> saved = captureSavedRoutines();
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getCategory()).isEqualTo("수면");
        assertThat(saved.get(0).getIntensity()).isEqualTo("저강도");
	}

	@Test
	void 원인_리포트에_SLEEP이_포함되면_수면_저강도_루틴이_적극개입으로_격상된다() {
		mockUser(2000);
		when(skinAnalysisRepository.findTop2ByUserIdOrderByAnalyzedAtDesc(USER_ID)).thenReturn(List.of());
		when(reportService.tryGetLatestSkinReport(USER_ID)).thenReturn(Optional.of(reportWithCauses(ReportCauseFactor.SLEEP)));

		Checkin checkin = checkin(6.5, 2, 1900); // 수면만 "저강도"로 생성되는 조건

		routineService.generateRoutines(USER_ID, checkin);

		List<Routine> saved = captureSavedRoutines();
		assertThat(saved).hasSize(1);
		assertThat(saved.get(0).getCategory()).isEqualTo("수면");
		assertThat(saved.get(0).getIntensity()).isEqualTo("적극개입");
	}

	@Test
	void 원인_리포트에_없는_요인의_저강도_루틴은_격상되지_않는다() {
		mockUser(2000);
		when(skinAnalysisRepository.findTop2ByUserIdOrderByAnalyzedAtDesc(USER_ID)).thenReturn(List.of());
		// STRESS만 원인으로 확인됨 -> 수면 루틴은 그대로 저강도 유지
		when(reportService.tryGetLatestSkinReport(USER_ID)).thenReturn(Optional.of(reportWithCauses(ReportCauseFactor.STRESS)));

		Checkin checkin = checkin(6.5, 2, 1900);

		routineService.generateRoutines(USER_ID, checkin);

		List<Routine> saved = captureSavedRoutines();
		assertThat(saved).hasSize(1);
		assertThat(saved.get(0).getIntensity()).isEqualTo("저강도");
	}

	@Test
	void 원인_리포트_조회가_예외를_던져도_루틴_생성_자체는_성공한다() {
		mockUser(2000);
		when(skinAnalysisRepository.findTop2ByUserIdOrderByAnalyzedAtDesc(USER_ID)).thenReturn(List.of());
		when(reportService.tryGetLatestSkinReport(USER_ID)).thenThrow(new RuntimeException("예상치 못한 오류"));

		Checkin checkin = checkin(6.5, 2, 1900);

		routineService.generateRoutines(USER_ID, checkin);

		List<Routine> saved = captureSavedRoutines();
		assertThat(saved).hasSize(1);
		assertThat(saved.get(0).getIntensity()).isEqualTo("저강도"); // 격상되지 않고 기존 규칙 그대로
	}

	@Test
	void 최신_분석이_DANGER면_피부_루틴이_최우선으로_추가된다() {
		mockUser(2000);
		SkinAnalysis dangerAnalysis = org.mockito.Mockito.mock(SkinAnalysis.class);
		when(dangerAnalysis.getSkinLevel()).thenReturn(SkinAnalysisLevel.DANGER);
		when(skinAnalysisRepository.findTop2ByUserIdOrderByAnalyzedAtDesc(USER_ID)).thenReturn(List.of(dangerAnalysis));
		when(reportService.tryGetLatestSkinReport(USER_ID)).thenReturn(Optional.empty());

		Checkin checkin = checkin(8.0, 1, 2000); // 다른 요인은 전부 양호 -> 피부 루틴만 생성

		routineService.generateRoutines(USER_ID, checkin);

		List<Routine> saved = captureSavedRoutines();
		assertThat(saved).hasSize(1);
		assertThat(saved.get(0).getCategory()).isEqualTo("피부");
		assertThat(saved.get(0).getIntensity()).isEqualTo("적극개입");
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

	private ReportDto.Response reportWithCauses(ReportCauseFactor... factors) {
		List<ReportDto.PrimaryCause> causes = List.of(factors).stream()
				.map(factor -> new ReportDto.PrimaryCause(factor, factor.name(), 1.0, "unit", "설명"))
				.toList();
		return new ReportDto.Response(1L, LocalDate.of(2026, 8, 10), null, causes, "요약");
	}

	private List<Routine> captureSavedRoutines() {
		ArgumentCaptor<List<Routine>> captor = ArgumentCaptor.forClass(List.class);
		org.mockito.Mockito.verify(routineRepository).saveAll(captor.capture());
		return captor.getValue();
	}
}
