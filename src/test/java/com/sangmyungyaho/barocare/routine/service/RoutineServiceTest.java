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
import org.junit.jupiter.api.BeforeEach;
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
import static org.mockito.ArgumentMatchers.eq;
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
	@Mock
	private IngredientRecommendationService ingredientRecommendationService;

	@InjectMocks
	private RoutineService routineService;

	@BeforeEach
	void setUp() {
		// getTodayRoutines()를 검증하지 않는 대부분의 테스트에서는 쓰이지 않는 스텁이라 lenient로 표시한다.
		lenient().when(ingredientRecommendationService.getTodayRecommendation(any()))
				.thenReturn(IngredientRecommendationService.TodayRecommendation.EMPTY);
	}

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

		// 수면 6.5h(개선 구간)만 정상 범위를 벗어나고, 수분 1900ml(목표 2000ml의 95%, 유지)/스트레스 2(유지)/
		// 피부 SAFE(유지)는 전부 정상 범위 -> 4개 카테고리 전부 생성되지만 "유지" 3개 + "저강도" 1개.
		Checkin checkin = checkin(6.5, 2, 1900);
		SkinAnalysis skinAnalysis = skinAnalysisWithLevel(SkinAnalysisLevel.SAFE);

		routineService.generateRoutines(USER_ID, checkin, skinAnalysis, mockReport());

		List<Routine> saved = captureSavedRoutines();
		assertThat(saved).hasSize(4);
		assertThat(saved.get(0).getCategory()).isEqualTo("수면");
		assertThat(saved.get(0).getIntensity()).isEqualTo("저강도");
		// 나머지 3개는 전부 "유지" 루틴(정상 범위여도 루틴 자체는 항상 생성됨).
		assertThat(saved.subList(1, 4)).extracting(Routine::getIntensity).containsOnly("유지");
		assertThat(saved.subList(1, 4)).extracting(Routine::getCategory)
				.containsExactlyInAnyOrder("피부", "수분", "스트레스");
	}

	@Test
	void 오늘_Report에_SLEEP이_포함되면_수면_저강도_루틴이_적극개입으로_격상된다() {
		mockUser(2000);
		when(reportService.getPrimaryCauseFactors(any())).thenReturn(List.of(ReportCauseFactor.SLEEP));

		Checkin checkin = checkin(6.5, 2, 1900); // 수면만 "저강도"로 생성되는 조건, 나머지는 "유지"
		SkinAnalysis skinAnalysis = skinAnalysisWithLevel(SkinAnalysisLevel.SAFE);

		routineService.generateRoutines(USER_ID, checkin, skinAnalysis, mockReport());

		List<Routine> saved = captureSavedRoutines();
		assertThat(saved).hasSize(4);
		// 격상되어 우선순위가 가장 높아지므로 정렬 결과 맨 앞에 온다.
		assertThat(saved.get(0).getCategory()).isEqualTo("수면");
		assertThat(saved.get(0).getIntensity()).isEqualTo("적극개입");
	}

	@Test
	void 오늘_Report에_없는_요인의_저강도_루틴은_격상되지_않는다() {
		mockUser(2000);
		// 수면(6.5h)과 스트레스(3) 둘 다 "저강도" 구간이지만, STRESS만 원인으로 확인됨
		// -> 스트레스만 적극개입으로 격상되고 수면은 그대로 저강도 유지.
		when(reportService.getPrimaryCauseFactors(any())).thenReturn(List.of(ReportCauseFactor.STRESS));

		Checkin checkin = checkin(6.5, 3, 1900);
		SkinAnalysis skinAnalysis = skinAnalysisWithLevel(SkinAnalysisLevel.SAFE);

		routineService.generateRoutines(USER_ID, checkin, skinAnalysis, mockReport());

		List<Routine> saved = captureSavedRoutines();
		assertThat(saved).hasSize(4);
		assertThat(findByCategory(saved, "스트레스").getIntensity()).isEqualTo("적극개입");
		assertThat(findByCategory(saved, "수면").getIntensity()).isEqualTo("저강도");
	}

	@Test
	void 원인_요인_조회가_예외를_던져도_루틴_생성_자체는_성공한다() {
		mockUser(2000);
		when(reportService.getPrimaryCauseFactors(any())).thenThrow(new RuntimeException("예상치 못한 오류"));

		Checkin checkin = checkin(6.5, 2, 1900);
		SkinAnalysis skinAnalysis = skinAnalysisWithLevel(SkinAnalysisLevel.SAFE);

		routineService.generateRoutines(USER_ID, checkin, skinAnalysis, mockReport());

		List<Routine> saved = captureSavedRoutines();
		assertThat(saved).hasSize(4);
		assertThat(findByCategory(saved, "수면").getIntensity()).isEqualTo("저강도"); // 격상되지 않고 기존 규칙 그대로
	}

	@Test
	void 오늘_SkinAnalysis가_DANGER면_피부_루틴이_최우선으로_노출된다() {
		mockUser(2000);
		when(reportService.getPrimaryCauseFactors(any())).thenReturn(List.of());

		Checkin checkin = checkin(8.0, 1, 2000); // 다른 요인은 전부 정상 범위(유지) -> 피부만 "적극개입"
		SkinAnalysis skinAnalysis = skinAnalysisWithLevel(SkinAnalysisLevel.DANGER);

		routineService.generateRoutines(USER_ID, checkin, skinAnalysis, mockReport());

		List<Routine> saved = captureSavedRoutines();
		assertThat(saved).hasSize(4);
		assertThat(saved.get(0).getCategory()).isEqualTo("피부");
		assertThat(saved.get(0).getIntensity()).isEqualTo("적극개입");
		assertThat(saved.subList(1, 4)).extracting(Routine::getIntensity).containsOnly("유지");
	}

	@Test
	void 모든_지표가_정상_범위여도_유지_루틴이_생성되어_빈_배열이_되지_않는다() {
		// 요구사항 핵심 케이스: 피부 SAFE, 수면 7h 이상, 수분 목표 100%, 스트레스 1 -> 전부 정상 범위.
		mockUser(2000);
		when(reportService.getPrimaryCauseFactors(any())).thenReturn(List.of());

		Checkin checkin = checkin(8.0, 1, 2000);
		SkinAnalysis skinAnalysis = skinAnalysisWithLevel(SkinAnalysisLevel.SAFE);

		routineService.generateRoutines(USER_ID, checkin, skinAnalysis, mockReport());

		List<Routine> saved = captureSavedRoutines();
		assertThat(saved).isNotEmpty();
		assertThat(saved).hasSize(4);
		assertThat(saved).extracting(Routine::getIntensity).containsOnly("유지");
		assertThat(saved).extracting(Routine::getCategory)
				.containsExactlyInAnyOrder("피부", "수면", "수분", "스트레스");
	}

	@Test
	void 수면이_6시간_미만이면_강한_개선_루틴이_생성된다() {
		mockUser(2000);
		when(reportService.getPrimaryCauseFactors(any())).thenReturn(List.of());

		Checkin checkin = checkin(5.0, 1, 2000); // 수면만 5h(강한 개선 구간), 나머지는 정상
		SkinAnalysis skinAnalysis = skinAnalysisWithLevel(SkinAnalysisLevel.SAFE);

		routineService.generateRoutines(USER_ID, checkin, skinAnalysis, mockReport());

		List<Routine> saved = captureSavedRoutines();
		assertThat(saved.get(0).getCategory()).isEqualTo("수면");
		assertThat(saved.get(0).getIntensity()).isEqualTo("적극개입");
	}

	@Test
	void 수분이_목표량의_60퍼센트_미만이면_강한_개선_루틴이_생성된다() {
		mockUser(2000);
		when(reportService.getPrimaryCauseFactors(any())).thenReturn(List.of());

		Checkin checkin = checkin(8.0, 1, 1000); // 1000ml/2000ml = 50%(강한 개선 구간), 나머지는 정상
		SkinAnalysis skinAnalysis = skinAnalysisWithLevel(SkinAnalysisLevel.SAFE);

		routineService.generateRoutines(USER_ID, checkin, skinAnalysis, mockReport());

		List<Routine> saved = captureSavedRoutines();
		assertThat(saved.get(0).getCategory()).isEqualTo("수분");
		assertThat(saved.get(0).getIntensity()).isEqualTo("적극개입");
	}

	@Test
	void 스트레스가_4이상이면_강한_완화_루틴이_생성된다() {
		mockUser(2000);
		when(reportService.getPrimaryCauseFactors(any())).thenReturn(List.of());

		Checkin checkin = checkin(8.0, 4, 2000); // 스트레스만 4(강한 완화 구간), 나머지는 정상
		SkinAnalysis skinAnalysis = skinAnalysisWithLevel(SkinAnalysisLevel.SAFE);

		routineService.generateRoutines(USER_ID, checkin, skinAnalysis, mockReport());

		List<Routine> saved = captureSavedRoutines();
		assertThat(saved.get(0).getCategory()).isEqualTo("스트레스");
		assertThat(saved.get(0).getIntensity()).isEqualTo("적극개입");
	}

	@Test
	void 여러_위험_조건이_동시에_발생해도_우선순위대로_최대_4개까지만_반환된다() {
		mockUser(2000);
		when(reportService.getPrimaryCauseFactors(any())).thenReturn(List.of());

		// 피부 CAUTION(개선), 수면 5h(강한 개선), 수분 1900ml=95%(유지), 스트레스 4(강한 완화)
		Checkin checkin = checkin(5.0, 4, 1900);
		SkinAnalysis skinAnalysis = skinAnalysisWithLevel(SkinAnalysisLevel.CAUTION);

		routineService.generateRoutines(USER_ID, checkin, skinAnalysis, mockReport());

		List<Routine> saved = captureSavedRoutines();
		assertThat(saved).hasSizeLessThanOrEqualTo(4); // 카테고리가 4개뿐이라 자연히 상한을 넘지 않는다.
		assertThat(saved).hasSize(4);
		// "강한 개선/완화"(적극개입) 2개가 "개선"(저강도) 1개, "유지" 1개보다 항상 먼저 온다.
		assertThat(saved.get(0).getIntensity()).isEqualTo("적극개입");
		assertThat(saved.get(1).getIntensity()).isEqualTo("적극개입");
		assertThat(saved.get(2).getIntensity()).isEqualTo("저강도");
		assertThat(saved.get(3).getIntensity()).isEqualTo("유지");
		assertThat(saved.subList(0, 2)).extracting(Routine::getCategory)
				.containsExactlyInAnyOrder("수면", "스트레스");
		assertThat(saved.get(2).getCategory()).isEqualTo("피부");
		assertThat(saved.get(3).getCategory()).isEqualTo("수분");
	}

	@Test
	void 생성된_루틴에는_예상_소요시간이_함께_저장된다() {
		mockUser(2000);
		when(reportService.getPrimaryCauseFactors(any())).thenReturn(List.of());

		Checkin checkin = checkin(6.5, 2, 1900);
		SkinAnalysis skinAnalysis = skinAnalysisWithLevel(SkinAnalysisLevel.SAFE);

		routineService.generateRoutines(USER_ID, checkin, skinAnalysis, mockReport());

		List<Routine> saved = captureSavedRoutines();
		assertThat(saved).allSatisfy(routine -> assertThat(routine.getEstimatedMinutes()).isNotNull().isPositive());
	}

	@Test
	void 루틴_생성_자체는_더_이상_추천_성분_생성을_트리거하지_않는다() {
		// 피부 분석 응답 속도 개선: 추천 성분/제품 생성 트리거는 SkinAnalysisService.analyzeSkin()으로
		// 옮겨져 루틴 생성과 완전히 분리됐다. generateRoutines()는 이제 ingredientRecommendationService를
		// 전혀 호출하지 않는다(getTodayRoutines() 조회 경로만 계속 사용한다).
		mockUser(2000);
		when(reportService.getPrimaryCauseFactors(any())).thenReturn(List.of());

		Checkin checkin = checkin(6.5, 2, 1900);
		SkinAnalysis skinAnalysis = skinAnalysisWithLevel(SkinAnalysisLevel.SAFE);

		routineService.generateRoutines(USER_ID, checkin, skinAnalysis, mockReport());

		verifyNoInteractions(ingredientRecommendationService);
	}

	@Test
	void 오늘의_루틴_조회_응답에_저장된_추천_성분과_제품이_함께_담긴다() {
		when(checkinRepository.existsByUserIdAndCheckedDate(USER_ID, LocalDate.now())).thenReturn(true);
		when(routineRepository.countByUserIdAndRoutineDate(USER_ID, LocalDate.now())).thenReturn(0L);
		when(routineRepository.countByUserIdAndRoutineDateAndIsCompletedTrue(USER_ID, LocalDate.now())).thenReturn(0L);
		when(routineRepository.findAllByUserIdAndRoutineDate(USER_ID, LocalDate.now())).thenReturn(List.of());

		var ingredient = new com.sangmyungyaho.barocare.routine.dto.IngredientRecommendationDto.IngredientItem("판테놀", "진정과 보습에 도움을 줄 수 있습니다.");
		var product = new com.sangmyungyaho.barocare.routine.dto.IngredientRecommendationDto.ProductItem(
				"라로슈포제", "시카플라스트 밤 B5+", "판테놀", "추천 성분인 판테놀과 관련된 실제 제품입니다.", "https://www.laroche-posay.co.kr/product/a");
		when(ingredientRecommendationService.getTodayRecommendation(USER_ID))
				.thenReturn(new IngredientRecommendationService.TodayRecommendation(List.of(ingredient), List.of(product)));

		var response = routineService.getTodayRoutines(USER_ID);

		assertThat(response.recommendedIngredients()).containsExactly(ingredient);
		assertThat(response.recommendedProducts()).containsExactly(product);
	}

	@Test
	void 추천이_아직_생성되지_않았으면_빈_배열을_반환한다() {
		when(checkinRepository.existsByUserIdAndCheckedDate(USER_ID, LocalDate.now())).thenReturn(false);
		when(routineRepository.countByUserIdAndRoutineDate(USER_ID, LocalDate.now())).thenReturn(0L);
		when(routineRepository.countByUserIdAndRoutineDateAndIsCompletedTrue(USER_ID, LocalDate.now())).thenReturn(0L);
		when(routineRepository.findAllByUserIdAndRoutineDate(USER_ID, LocalDate.now())).thenReturn(List.of());
		// setUp()의 기본 스텁(TodayRecommendation.EMPTY)을 그대로 사용.

		var response = routineService.getTodayRoutines(USER_ID);

		assertThat(response.recommendedIngredients()).isEmpty();
		assertThat(response.recommendedProducts()).isEmpty();
	}

	@Test
	void 오늘자_루틴이_이미_있으면_재생성하지_않는다() {
		// 멱등성: 같은 날 피부 분석 API가 여러 번 호출돼도(재분석 등) 루틴이 중복 생성되면 안 된다.
		when(routineRepository.existsByUserIdAndRoutineDate(USER_ID, LocalDate.of(2026, 8, 10))).thenReturn(true);

		Checkin checkin = checkin(6.5, 2, 1900);
		SkinAnalysis skinAnalysis = skinAnalysisWithLevel(SkinAnalysisLevel.SAFE);

		routineService.generateRoutines(USER_ID, checkin, skinAnalysis, mockReport());

		verify(routineRepository, never()).saveAll(any());
		verifyNoInteractions(userRepository, reportService, ingredientRecommendationService);
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

	private Routine findByCategory(List<Routine> routines, String category) {
		return routines.stream()
				.filter(routine -> routine.getCategory().equals(category))
				.findFirst()
				.orElseThrow(() -> new AssertionError("category=" + category + " 루틴을 찾을 수 없습니다: " + routines));
	}
}
