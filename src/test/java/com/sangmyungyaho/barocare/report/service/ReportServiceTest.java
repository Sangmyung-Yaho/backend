package com.sangmyungyaho.barocare.report.service;

import com.sangmyungyaho.barocare.ai.client.AiClient;
import com.sangmyungyaho.barocare.ai.dto.AiDto;
import com.sangmyungyaho.barocare.checkin.entity.Checkin;
import com.sangmyungyaho.barocare.checkin.repository.CheckinRepository;
import com.sangmyungyaho.barocare.global.exception.ErrorCode;
import com.sangmyungyaho.barocare.global.exception.GlobalException;
import com.sangmyungyaho.barocare.report.dto.ReportDto;
import com.sangmyungyaho.barocare.report.entity.LifestyleFactorLevel;
import com.sangmyungyaho.barocare.report.entity.Report;
import com.sangmyungyaho.barocare.report.entity.ReportCauseFactor;
import com.sangmyungyaho.barocare.report.entity.ReportChangeStatus;
import com.sangmyungyaho.barocare.report.entity.WarningLevel;
import com.sangmyungyaho.barocare.report.repository.ReportRepository;
import com.sangmyungyaho.barocare.skin.entity.ChangeDirection;
import com.sangmyungyaho.barocare.skin.entity.SkinAnalysis;
import com.sangmyungyaho.barocare.skin.entity.SkinAnalysisLevel;
import com.sangmyungyaho.barocare.skin.entity.SkinComparison;
import com.sangmyungyaho.barocare.skin.repository.SkinAnalysisRepository;
import com.sangmyungyaho.barocare.skin.repository.SkinComparisonRepository;
import com.sangmyungyaho.barocare.user.entity.User;
import com.sangmyungyaho.barocare.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * ReportService 단위 테스트.
 *
 * 생성({@link ReportService#generateTodayReport})과 조회({@link ReportService#getLatestSkinReport} 등)의
 * 책임이 분리되어 있다 - 생성 테스트는 generateTodayReport()를 직접 호출하고, 조회 전용 API(warnings/
 * interactions 등)는 getLatestSkinReport()를 스텁으로 대체해 위임 결과만 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class ReportServiceTest {

	private static final Long USER_ID = 1L;

	@Mock
	private SkinAnalysisRepository skinAnalysisRepository;
	@Mock
	private CheckinRepository checkinRepository;
	@Mock
	private ReportRepository reportRepository;
	@Mock
	private AiClient aiClient;
	@Mock
	private ObjectMapper objectMapper;
	@Mock
	private SkinComparisonRepository skinComparisonRepository;
	@Mock
	private UserRepository userRepository;

	private ReportService reportService;

	@BeforeEach
	void setUp() {
		reportService = spy(new ReportService(
				skinAnalysisRepository, checkinRepository, reportRepository, aiClient, objectMapper,
				new CauseCombinationRubric(), skinComparisonRepository, userRepository, new LifestyleFactorRubric()
		));
	}

	// ---------- 생성(generateTodayReport) ----------

	@Test
	void 직전_SkinAnalysis가_없어도_첫_분석으로_리포트가_생성된다() {
		// 요구사항 #1/#3: 과거 체크인이 0~6건이거나 피부 분석이 처음이어도 리포트 생성을 막으면 안 된다.
		Long todayId = 20L;
		SkinAnalysis today = skinAnalysisWithId(todayId);
		lenient().when(today.getAnalyzedAt()).thenReturn(LocalDateTime.of(2026, 8, 10, 9, 0));
		lenient().when(today.getRednessLevel()).thenReturn(SkinAnalysisLevel.SAFE);
		lenient().when(today.getTroubleLevel()).thenReturn(SkinAnalysisLevel.SAFE);
		when(skinAnalysisRepository.findTopByUserIdAndAnalyzedAtLessThanOrderByAnalyzedAtDesc(USER_ID, today.getAnalyzedAt()))
				.thenReturn(Optional.empty());
		when(reportRepository.findByCurrentSkinAnalysis_Id(todayId)).thenReturn(Optional.empty());
		when(checkinRepository.findAllByUserIdAndCheckedDateLessThanOrderByCheckedDateDesc(USER_ID, LocalDate.of(2026, 8, 10)))
				.thenReturn(List.of()); // 과거 체크인 없음(0건) -> 고정 기준표
		when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

		Checkin todayCheckin = new Checkin(USER_ID, 5.0, 4, 800, LocalDate.of(2026, 8, 10));

		ArgumentCaptor<AiDto.SkinChangeInput> skinChangeCaptor = ArgumentCaptor.forClass(AiDto.SkinChangeInput.class);
		when(aiClient.analyzeSkinChangeCauses(skinChangeCaptor.capture(), any()))
				.thenReturn(new AiDto.CauseAnalysisResult(List.of(), "요약"));
		when(objectMapper.writeValueAsString(any())).thenReturn("[]");
		ArgumentCaptor<Report> reportCaptor = ArgumentCaptor.forClass(Report.class);
		when(reportRepository.save(reportCaptor.capture())).thenAnswer(invocation -> invocation.getArgument(0));

		Report result = reportService.generateTodayReport(USER_ID, today, todayCheckin);

		assertThat(result).isNotNull();
		assertThat(reportCaptor.getValue().getPreviousSkinAnalysis()).isNull();
		assertThat(reportCaptor.getValue().getRednessPreviousScore()).isNull();
		assertThat(reportCaptor.getValue().getRednessStatus()).isNull();
		AiDto.SkinChangeInput captured = skinChangeCaptor.getValue();
		assertThat(captured.rednessChange()).isNull();
		assertThat(captured.rednessStatus()).isNull();
		assertThat(captured.rednessDirection()).isNull();
	}

	@Test
	void 이미_생성된_오늘_리포트가_있으면_재사용하고_AI를_재호출하지_않는다() {
		Long todayId = 21L;
		SkinAnalysis today = skinAnalysisWithId(todayId);
		Report existing = mock(Report.class);
		when(reportRepository.findByCurrentSkinAnalysis_Id(todayId)).thenReturn(Optional.of(existing));

		Checkin todayCheckin = new Checkin(USER_ID, 7.0, 2, 1500, LocalDate.of(2026, 8, 10));

		Report result = reportService.generateTodayReport(USER_ID, today, todayCheckin);

		assertThat(result).isSameAs(existing);
		verifyNoInteractions(aiClient);
		verify(reportRepository, never()).save(any());
	}

	@Test
	void 리포트_생성시_생활습관_요인판정_결과와_목표_음수량을_반영해_AiClient에_전달한다() {
		// GPT에 raw 체크인 수치만 넘기지 않고, LifestyleFactorRubric이 먼저 판정한 GOOD/MODERATE/POOR와
		// 개인 기준선 사용 여부를 CheckinInput에 담아 전달하는지 검증한다.
		Long todayId = 20L;
		SkinAnalysis today = mock(SkinAnalysis.class);
		SkinAnalysis previous = mock(SkinAnalysis.class);
		when(today.getId()).thenReturn(todayId);
		when(today.getAnalyzedAt()).thenReturn(LocalDateTime.of(2026, 8, 10, 9, 0));
		when(today.getRednessLevel()).thenReturn(SkinAnalysisLevel.SAFE);
		when(today.getTroubleLevel()).thenReturn(SkinAnalysisLevel.SAFE);
		when(previous.getId()).thenReturn(19L);
		when(previous.getRednessLevel()).thenReturn(SkinAnalysisLevel.CAUTION);
		when(previous.getTroubleLevel()).thenReturn(SkinAnalysisLevel.CAUTION);
		when(skinAnalysisRepository.findTopByUserIdAndAnalyzedAtLessThanOrderByAnalyzedAtDesc(USER_ID, today.getAnalyzedAt()))
				.thenReturn(Optional.of(previous));
		when(reportRepository.findByCurrentSkinAnalysis_Id(todayId)).thenReturn(Optional.empty());

		// 오늘 체크인은 수면 부족/스트레스 높음/수분 부족, 이전 7건은 모두 양호 -> 개인 기준선 기준으로 POOR가 나와야 한다.
		Checkin todayCheckin = new Checkin(USER_ID, 5.0, 4, 800, LocalDate.of(2026, 8, 10));
		List<Checkin> previousCheckins = new ArrayList<>();
		for (int i = 1; i <= 7; i++) {
			previousCheckins.add(new Checkin(USER_ID, 7.0, 2, 2000, LocalDate.of(2026, 8, 10).minusDays(i)));
		}
		when(checkinRepository.findAllByUserIdAndCheckedDateLessThanOrderByCheckedDateDesc(USER_ID, LocalDate.of(2026, 8, 10)))
				.thenReturn(previousCheckins);

		User user = mock(User.class);
		when(user.getWaterGoalMl()).thenReturn(2000);
		when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

		ArgumentCaptor<AiDto.SkinChangeInput> skinChangeInputCaptor = ArgumentCaptor.forClass(AiDto.SkinChangeInput.class);
		ArgumentCaptor<AiDto.CheckinInput> checkinInputCaptor = ArgumentCaptor.forClass(AiDto.CheckinInput.class);
		when(aiClient.analyzeSkinChangeCauses(skinChangeInputCaptor.capture(), checkinInputCaptor.capture()))
				.thenReturn(new AiDto.CauseAnalysisResult(List.of(), "요약"));
		when(objectMapper.writeValueAsString(any())).thenReturn("[]");
		when(reportRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

		reportService.generateTodayReport(USER_ID, today, todayCheckin);

		AiDto.CheckinInput capturedCheckin = checkinInputCaptor.getValue();
		assertThat(capturedCheckin.personalBaselineUsed()).isTrue();
		assertThat(capturedCheckin.sleepLevel()).isEqualTo(LifestyleFactorLevel.POOR);
		assertThat(capturedCheckin.stressLevel()).isEqualTo(LifestyleFactorLevel.POOR);
		assertThat(capturedCheckin.waterLevel()).isEqualTo(LifestyleFactorLevel.POOR);
		assertThat(capturedCheckin.candidateFactors())
				.containsExactlyInAnyOrder(ReportCauseFactor.SLEEP, ReportCauseFactor.STRESS, ReportCauseFactor.WATER_INTAKE);
		verify(userRepository).findById(USER_ID);

		// current(SAFE=0) vs previous(CAUTION=1) -> change=-1 -> IMPROVED -> DECREASED.
		AiDto.SkinChangeInput capturedSkinChange = skinChangeInputCaptor.getValue();
		assertThat(capturedSkinChange.rednessDirection()).isEqualTo(ChangeDirection.DECREASED);
		assertThat(capturedSkinChange.troubleDirection()).isEqualTo(ChangeDirection.DECREASED);
	}

	// ---------- 리포트 문구(summary) 자연어 생성 검증 ----------
	//
	// summary는 더 이상 AI가 통째로 쓰지 않는다: 붉은기/트러블 변화 문구·전체 평가 문구는 rednessStatus/
	// troubleStatus만으로 ReportService가 결정적으로 조립하고(내부 enum이 새어나갈 여지 자체가 없음),
	// AI는 원인 요인 설명 한 문장만 덧붙인다. 아래 세 테스트는 정확한 문구 조립을, 그 아래 두 테스트는
	// AI가 지침을 어기고 영어 상태값을 그대로 반환했을 때 최종 방어선(validateNoInternalStateLeak)이
	// 작동하는지를 검증한다.

	@Test
	void 개선_케이스면_감소_문구와_좋아지고_있다는_문구와_원인_요인_설명이_조립된다() {
		Long todayId = 60L;
		SkinAnalysis today = skinAnalysisWithId(todayId);
		SkinAnalysis previous = skinAnalysisWithId(59L);
		when(today.getAnalyzedAt()).thenReturn(LocalDateTime.of(2026, 8, 10, 9, 0));
		when(today.getRednessLevel()).thenReturn(SkinAnalysisLevel.SAFE);
		when(today.getTroubleLevel()).thenReturn(SkinAnalysisLevel.SAFE);
		when(previous.getRednessLevel()).thenReturn(SkinAnalysisLevel.CAUTION);
		when(previous.getTroubleLevel()).thenReturn(SkinAnalysisLevel.CAUTION);
		when(skinAnalysisRepository.findTopByUserIdAndAnalyzedAtLessThanOrderByAnalyzedAtDesc(USER_ID, today.getAnalyzedAt()))
				.thenReturn(Optional.of(previous));
		when(reportRepository.findByCurrentSkinAnalysis_Id(todayId)).thenReturn(Optional.empty());
		when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

		Checkin todayCheckin = new Checkin(USER_ID, 5.0, 4, 800, LocalDate.of(2026, 8, 10));
		when(checkinRepository.findAllByUserIdAndCheckedDateLessThanOrderByCheckedDateDesc(USER_ID, LocalDate.of(2026, 8, 10)))
				.thenReturn(List.of());
		when(aiClient.analyzeSkinChangeCauses(any(), any())).thenReturn(new AiDto.CauseAnalysisResult(
				List.of(), "최근 기록에서는 스트레스와 수분 섭취가 피부 변화에 영향을 준 주요 요인으로 보여요."));
		when(objectMapper.writeValueAsString(any())).thenReturn("[]");
		ArgumentCaptor<Report> reportCaptor = ArgumentCaptor.forClass(Report.class);
		when(reportRepository.save(reportCaptor.capture())).thenAnswer(invocation -> invocation.getArgument(0));

		reportService.generateTodayReport(USER_ID, today, todayCheckin);

		assertThat(reportCaptor.getValue().getSummary()).isEqualTo(
				"붉은기와 트러블이 모두 이전보다 감소했어요. 피부 상태가 전반적으로 좋아지고 있어요. "
						+ "최근 기록에서는 스트레스와 수분 섭취가 피부 변화에 영향을 준 주요 요인으로 보여요."
		);
	}

	@Test
	void 변화없음_케이스면_큰_변화없다는_문구와_비슷하게_유지된다는_문구가_조립된다() {
		Long todayId = 61L;
		SkinAnalysis today = skinAnalysisWithId(todayId);
		SkinAnalysis previous = skinAnalysisWithId(60L);
		when(today.getAnalyzedAt()).thenReturn(LocalDateTime.of(2026, 8, 10, 9, 0));
		when(today.getRednessLevel()).thenReturn(SkinAnalysisLevel.SAFE);
		when(today.getTroubleLevel()).thenReturn(SkinAnalysisLevel.SAFE);
		when(previous.getRednessLevel()).thenReturn(SkinAnalysisLevel.SAFE);
		when(previous.getTroubleLevel()).thenReturn(SkinAnalysisLevel.SAFE);
		when(skinAnalysisRepository.findTopByUserIdAndAnalyzedAtLessThanOrderByAnalyzedAtDesc(USER_ID, today.getAnalyzedAt()))
				.thenReturn(Optional.of(previous));
		when(reportRepository.findByCurrentSkinAnalysis_Id(todayId)).thenReturn(Optional.empty());
		when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

		Checkin todayCheckin = new Checkin(USER_ID, 7.0, 2, 1500, LocalDate.of(2026, 8, 10));
		when(checkinRepository.findAllByUserIdAndCheckedDateLessThanOrderByCheckedDateDesc(USER_ID, LocalDate.of(2026, 8, 10)))
				.thenReturn(List.of());
		when(aiClient.analyzeSkinChangeCauses(any(), any())).thenReturn(new AiDto.CauseAnalysisResult(
				List.of(), "최근 기록에서는 특별히 두드러진 위험 요인은 보이지 않아요."));
		when(objectMapper.writeValueAsString(any())).thenReturn("[]");
		ArgumentCaptor<Report> reportCaptor = ArgumentCaptor.forClass(Report.class);
		when(reportRepository.save(reportCaptor.capture())).thenAnswer(invocation -> invocation.getArgument(0));

		reportService.generateTodayReport(USER_ID, today, todayCheckin);

		assertThat(reportCaptor.getValue().getSummary()).isEqualTo(
				"붉은기와 트러블에 큰 변화가 없어요. 현재 피부 상태가 비슷하게 유지되고 있어요. "
						+ "최근 기록에서는 특별히 두드러진 위험 요인은 보이지 않아요."
		);
	}

	@Test
	void 악화_케이스면_증가_문구와_다소_악화된_모습이라는_문구가_조립된다() {
		Long todayId = 62L;
		SkinAnalysis today = skinAnalysisWithId(todayId);
		SkinAnalysis previous = skinAnalysisWithId(61L);
		when(today.getAnalyzedAt()).thenReturn(LocalDateTime.of(2026, 8, 10, 9, 0));
		when(today.getRednessLevel()).thenReturn(SkinAnalysisLevel.CAUTION);
		when(today.getTroubleLevel()).thenReturn(SkinAnalysisLevel.CAUTION);
		when(previous.getRednessLevel()).thenReturn(SkinAnalysisLevel.SAFE);
		when(previous.getTroubleLevel()).thenReturn(SkinAnalysisLevel.SAFE);
		when(skinAnalysisRepository.findTopByUserIdAndAnalyzedAtLessThanOrderByAnalyzedAtDesc(USER_ID, today.getAnalyzedAt()))
				.thenReturn(Optional.of(previous));
		when(reportRepository.findByCurrentSkinAnalysis_Id(todayId)).thenReturn(Optional.empty());
		when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

		Checkin todayCheckin = new Checkin(USER_ID, 5.0, 4, 800, LocalDate.of(2026, 8, 10));
		when(checkinRepository.findAllByUserIdAndCheckedDateLessThanOrderByCheckedDateDesc(USER_ID, LocalDate.of(2026, 8, 10)))
				.thenReturn(List.of());
		when(aiClient.analyzeSkinChangeCauses(any(), any())).thenReturn(new AiDto.CauseAnalysisResult(
				List.of(), "최근 기록에서는 수면 부족과 스트레스가 피부 변화에 영향을 준 요인으로 보여요."));
		when(objectMapper.writeValueAsString(any())).thenReturn("[]");
		ArgumentCaptor<Report> reportCaptor = ArgumentCaptor.forClass(Report.class);
		when(reportRepository.save(reportCaptor.capture())).thenAnswer(invocation -> invocation.getArgument(0));

		reportService.generateTodayReport(USER_ID, today, todayCheckin);

		assertThat(reportCaptor.getValue().getSummary()).isEqualTo(
				"붉은기와 트러블이 모두 이전보다 증가했어요. 최근 피부 상태가 다소 악화된 모습이에요. "
						+ "최근 기록에서는 수면 부족과 스트레스가 피부 변화에 영향을 준 요인으로 보여요."
		);
	}

	@Test
	void 붉은기와_트러블_상태가_서로_다르면_각각_따로_서술한다() {
		// 리뷰 중 발견: 이 분기(둘의 상태가 다른 경우)가 테스트로 커버되지 않았었다. changeDescription()이
		// 명사형("이전보다 감소")을 반환하고 buildChangeSentence가 그 뒤에 "이에요"를 붙이던 예전 구현은
		// "트러블은 이전보다 증가이에요."처럼 문법이 깨졌다 - 지금은 동사형을 직접 반환하도록 고쳤다.
		Long todayId = 63L;
		SkinAnalysis today = skinAnalysisWithId(todayId);
		SkinAnalysis previous = skinAnalysisWithId(62L);
		when(today.getAnalyzedAt()).thenReturn(LocalDateTime.of(2026, 8, 10, 9, 0));
		when(today.getRednessLevel()).thenReturn(SkinAnalysisLevel.SAFE);
		when(today.getTroubleLevel()).thenReturn(SkinAnalysisLevel.CAUTION);
		when(previous.getRednessLevel()).thenReturn(SkinAnalysisLevel.CAUTION);
		when(previous.getTroubleLevel()).thenReturn(SkinAnalysisLevel.SAFE);
		when(skinAnalysisRepository.findTopByUserIdAndAnalyzedAtLessThanOrderByAnalyzedAtDesc(USER_ID, today.getAnalyzedAt()))
				.thenReturn(Optional.of(previous));
		when(reportRepository.findByCurrentSkinAnalysis_Id(todayId)).thenReturn(Optional.empty());
		when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

		Checkin todayCheckin = new Checkin(USER_ID, 7.0, 2, 1500, LocalDate.of(2026, 8, 10));
		when(checkinRepository.findAllByUserIdAndCheckedDateLessThanOrderByCheckedDateDesc(USER_ID, LocalDate.of(2026, 8, 10)))
				.thenReturn(List.of());
		when(aiClient.analyzeSkinChangeCauses(any(), any())).thenReturn(new AiDto.CauseAnalysisResult(
				List.of(), "최근 기록에서는 특별히 두드러진 위험 요인은 보이지 않아요."));
		when(objectMapper.writeValueAsString(any())).thenReturn("[]");
		ArgumentCaptor<Report> reportCaptor = ArgumentCaptor.forClass(Report.class);
		when(reportRepository.save(reportCaptor.capture())).thenAnswer(invocation -> invocation.getArgument(0));

		reportService.generateTodayReport(USER_ID, today, todayCheckin);

		// redness: CAUTION(1)->SAFE(0) 개선, trouble: SAFE(0)->CAUTION(1) 악화. 붉은기가 SAFE(개선)이므로
		// buildOverallSentence는 WORSENED를 우선해 "다소 악화된 모습" 문구를 고른다.
		assertThat(reportCaptor.getValue().getSummary()).isEqualTo(
				"붉은기는 이전보다 감소했어요, 트러블은 이전보다 증가했어요. 최근 피부 상태가 다소 악화된 모습이에요. "
						+ "최근 기록에서는 특별히 두드러진 위험 요인은 보이지 않아요."
		);
	}

	@Test
	void AI가_summary에_영어_상태값을_그대로_반환하면_AI_ANALYSIS_FAILED로_거부되고_저장되지_않는다() {
		Long todayId = 70L;
		SkinAnalysis today = skinAnalysisWithId(todayId);
		lenient().when(today.getAnalyzedAt()).thenReturn(LocalDateTime.of(2026, 8, 10, 9, 0));
		lenient().when(today.getRednessLevel()).thenReturn(SkinAnalysisLevel.SAFE);
		lenient().when(today.getTroubleLevel()).thenReturn(SkinAnalysisLevel.SAFE);
		when(skinAnalysisRepository.findTopByUserIdAndAnalyzedAtLessThanOrderByAnalyzedAtDesc(USER_ID, today.getAnalyzedAt()))
				.thenReturn(Optional.empty());
		when(reportRepository.findByCurrentSkinAnalysis_Id(todayId)).thenReturn(Optional.empty());
		when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

		Checkin todayCheckin = new Checkin(USER_ID, 5.0, 1, 2000, LocalDate.of(2026, 8, 10));
		when(checkinRepository.findAllByUserIdAndCheckedDateLessThanOrderByCheckedDateDesc(USER_ID, LocalDate.of(2026, 8, 10)))
				.thenReturn(List.of());
		// AI가 프롬프트 지침을 어기고 내부 상태값을 그대로 섞어 반환한 상황을 시뮬레이션한다.
		when(aiClient.analyzeSkinChangeCauses(any(), any())).thenReturn(new AiDto.CauseAnalysisResult(
				List.of(), "최근 수면 판정이 POOR이라 피부 변화에 영향을 준 것으로 보여요."));

		assertThatThrownBy(() -> reportService.generateTodayReport(USER_ID, today, todayCheckin))
				.isInstanceOf(GlobalException.class)
				.extracting("errorCode")
				.isEqualTo(ErrorCode.AI_ANALYSIS_FAILED);
		verify(reportRepository, never()).save(any());
	}

	@Test
	void AI가_cause_description에_영어_상태값을_그대로_반환하면_AI_ANALYSIS_FAILED로_거부되고_저장되지_않는다() {
		Long todayId = 71L;
		SkinAnalysis today = skinAnalysisWithId(todayId);
		lenient().when(today.getAnalyzedAt()).thenReturn(LocalDateTime.of(2026, 8, 10, 9, 0));
		lenient().when(today.getRednessLevel()).thenReturn(SkinAnalysisLevel.SAFE);
		lenient().when(today.getTroubleLevel()).thenReturn(SkinAnalysisLevel.SAFE);
		when(skinAnalysisRepository.findTopByUserIdAndAnalyzedAtLessThanOrderByAnalyzedAtDesc(USER_ID, today.getAnalyzedAt()))
				.thenReturn(Optional.empty());
		when(reportRepository.findByCurrentSkinAnalysis_Id(todayId)).thenReturn(Optional.empty());
		when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

		Checkin todayCheckin = new Checkin(USER_ID, 5.0, 1, 2000, LocalDate.of(2026, 8, 10));
		when(checkinRepository.findAllByUserIdAndCheckedDateLessThanOrderByCheckedDateDesc(USER_ID, LocalDate.of(2026, 8, 10)))
				.thenReturn(List.of());
		AiDto.CauseAnalysisResult leaking = new AiDto.CauseAnalysisResult(
				List.of(new AiDto.Cause(ReportCauseFactor.SLEEP, "수면 부족", "수면 판정이 POOR로 나왔어요.")),
				"최근 기록에서는 수면 부족이 피부 변화에 영향을 준 요인으로 보여요."
		);
		when(aiClient.analyzeSkinChangeCauses(any(), any())).thenReturn(leaking);

		assertThatThrownBy(() -> reportService.generateTodayReport(USER_ID, today, todayCheckin))
				.isInstanceOf(GlobalException.class)
				.extracting("errorCode")
				.isEqualTo(ErrorCode.AI_ANALYSIS_FAILED);
		verify(reportRepository, never()).save(any());
	}

	@Test
	void AI가_후보_밖_요인을_causes에_포함시켜도_최종_응답에서는_제외된다() {
		// GPT가 candidateFactors 지침을 어기고 정상(POOR 아님) 요인을 causes에 넣어도, ReportService가
		// candidateFactors 기준으로 다시 걸러내 최종 primaryCauses에는 절대 남지 않아야 한다(코드 레벨 강제).
		Long todayId = 40L;
		SkinAnalysis today = skinAnalysisWithId(todayId);
		lenient().when(today.getAnalyzedAt()).thenReturn(LocalDateTime.of(2026, 8, 10, 9, 0));
		lenient().when(today.getRednessLevel()).thenReturn(SkinAnalysisLevel.SAFE);
		lenient().when(today.getTroubleLevel()).thenReturn(SkinAnalysisLevel.SAFE);
		when(skinAnalysisRepository.findTopByUserIdAndAnalyzedAtLessThanOrderByAnalyzedAtDesc(USER_ID, today.getAnalyzedAt()))
				.thenReturn(Optional.empty());
		when(reportRepository.findByCurrentSkinAnalysis_Id(todayId)).thenReturn(Optional.empty());

		// 수면만 POOR(5h), 스트레스/수분은 고정 기준표상 GOOD -> candidateFactors=[SLEEP]뿐이어야 한다.
		Checkin todayCheckin = new Checkin(USER_ID, 5.0, 1, 2000, LocalDate.of(2026, 8, 10));
		when(checkinRepository.findAllByUserIdAndCheckedDateLessThanOrderByCheckedDateDesc(USER_ID, LocalDate.of(2026, 8, 10)))
				.thenReturn(List.of());
		when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

		// AI가 지침을 어기고 STRESS(후보 밖)까지 causes에 포함시켜 반환한 상황을 시뮬레이션한다.
		AiDto.CauseAnalysisResult causeAnalysisResult = new AiDto.CauseAnalysisResult(
				List.of(
						new AiDto.Cause(ReportCauseFactor.SLEEP, "수면 부족", "설명"),
						new AiDto.Cause(ReportCauseFactor.STRESS, "스트레스", "설명")
				),
				"요약"
		);
		when(aiClient.analyzeSkinChangeCauses(any(), any())).thenReturn(causeAnalysisResult);
		ArgumentCaptor<Object> primaryCausesToSerializeCaptor = ArgumentCaptor.forClass(Object.class);
		when(objectMapper.writeValueAsString(primaryCausesToSerializeCaptor.capture())).thenReturn("[]");
		when(reportRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

		reportService.generateTodayReport(USER_ID, today, todayCheckin);

		@SuppressWarnings("unchecked")
		List<ReportDto.PrimaryCause> savedPrimaryCauses = (List<ReportDto.PrimaryCause>) primaryCausesToSerializeCaptor.getValue();
		assertThat(savedPrimaryCauses).hasSize(1);
		assertThat(savedPrimaryCauses.get(0).factor()).isEqualTo(ReportCauseFactor.SLEEP);
	}

	// ---------- 조회(순수 읽기, 생성/AI 호출 없음) ----------

	@Test
	void 고위험_조합이_없으면_에러_없이_빈_warnings를_반환한다() {
		doReturn(latestReportWithCauses(ReportCauseFactor.WATER_INTAKE)).when(reportService).getLatestSkinReport(USER_ID);

		ReportDto.WarningsResponse response = reportService.getLatestCauseWarnings(USER_ID);

		assertThat(response.warnings()).isEmpty();
	}

	@Test
	void 두_요인만_있으면_경고는_비어있고_상호작용으로만_노출된다() {
		// 고위험 경고는 3요인(수면+스트레스+수분)이 모두 있을 때만 발동한다 - 2요인 조합은 경고에
		// 노출되지 않고 상호작용(interactions)으로만 노출되어 두 응답이 같은 조합을 중복 노출하지 않는다.
		doReturn(latestReportWithCauses(ReportCauseFactor.SLEEP, ReportCauseFactor.STRESS))
				.when(reportService).getLatestSkinReport(USER_ID);

		ReportDto.WarningsResponse warnings = reportService.getLatestCauseWarnings(USER_ID);
		ReportDto.InteractionsResponse interactions = reportService.getLatestCauseInteractions(USER_ID);

		assertThat(warnings.warnings()).isEmpty();
		assertThat(interactions.interactions()).hasSize(1);
	}

	@Test
	void 세_요인이_모두_있으면_고위험_경고_하나를_반환하고_헤드라인과_요인별_실측값을_포함한다() {
		doReturn(latestReportWithCauses(ReportCauseFactor.SLEEP, ReportCauseFactor.STRESS, ReportCauseFactor.WATER_INTAKE))
				.when(reportService).getLatestSkinReport(USER_ID);

		ReportDto.WarningsResponse response = reportService.getLatestCauseWarnings(USER_ID);

		assertThat(response.warnings()).hasSize(1);
		ReportDto.Warning warning = response.warnings().get(0);
		assertThat(warning.level()).isEqualTo(WarningLevel.HIGH);
		assertThat(warning.factors())
				.containsExactly(ReportCauseFactor.SLEEP, ReportCauseFactor.STRESS, ReportCauseFactor.WATER_INTAKE);
		assertThat(warning.headline()).isEqualTo("오늘은 몸을 쉬게 해주세요.");
		assertThat(warning.factorValues()).hasSize(3);
	}

	@Test
	void 함께_관찰된_요인이_없으면_에러_없이_빈_interactions를_반환한다() {
		doReturn(latestReportWithCauses(ReportCauseFactor.WATER_INTAKE)).when(reportService).getLatestSkinReport(USER_ID);

		ReportDto.InteractionsResponse response = reportService.getLatestCauseInteractions(USER_ID);

		assertThat(response.interactions()).isEmpty();
	}

	@Test
	void SLEEP과_STRESS가_함께_있으면_의료적_인과관계_표현_없이_상호작용_설명을_반환한다() {
		doReturn(latestReportWithCauses(ReportCauseFactor.SLEEP, ReportCauseFactor.STRESS))
				.when(reportService).getLatestSkinReport(USER_ID);

		ReportDto.InteractionsResponse response = reportService.getLatestCauseInteractions(USER_ID);

		assertThat(response.interactions()).hasSize(1);
		ReportDto.Interaction interaction = response.interactions().get(0);
		assertThat(interaction.factors()).containsExactly(ReportCauseFactor.SLEEP, ReportCauseFactor.STRESS);
		assertThat(interaction.message())
				.contains("함께 관찰되었어요")
				.contains("가능성이 있어요")
				.doesNotContain("때문에");
	}

	@Test
	void SkinComparison이_있으면_그_ChangeDirection을_그대로_사용한다() {
		SkinAnalysis current = skinAnalysisWithId(2L);
		SkinAnalysis previous = skinAnalysisWithId(1L);
		Report report = reportOf(current, previous, ReportChangeStatus.IMPROVED, ReportChangeStatus.IMPROVED);
		when(reportRepository.findTopByCurrentSkinAnalysis_UserIdOrderByReportDateDescIdDesc(USER_ID))
				.thenReturn(Optional.of(report));

		SkinComparison comparison = mock(SkinComparison.class);
		when(comparison.getRednessChange()).thenReturn(ChangeDirection.INCREASED);
		when(comparison.getTroubleChange()).thenReturn(ChangeDirection.STABLE);
		when(skinComparisonRepository.findByCurrentSkinAnalysis_IdAndPreviousSkinAnalysis_Id(eq(2L), eq(1L)))
				.thenReturn(Optional.of(comparison));

		ReportDto.SkinSignalResponse response = reportService.getLatestSkinSignal(USER_ID);

		// Report의 status는 둘 다 IMPROVED(=DECREASED에 대응)이지만, SkinComparison이 있으므로
		// 그 값(INCREASED/STABLE)이 우선한다 - fallback으로 덮이지 않아야 한다.
		assertThat(response.redness().direction()).isEqualTo(ChangeDirection.INCREASED);
		assertThat(response.redness().message()).isEqualTo("이전보다 붉은기가 증가했어요.");
		assertThat(response.trouble().direction()).isEqualTo(ChangeDirection.STABLE);
		assertThat(response.trouble().message()).isEqualTo("트러블이 이전과 비슷한 상태예요.");
	}

	@Test
	void SkinComparison이_없으면_Report의_등급_변화를_대체_신호로_사용한다() {
		SkinAnalysis current = skinAnalysisWithId(4L);
		SkinAnalysis previous = skinAnalysisWithId(3L);
		Report report = reportOf(current, previous, ReportChangeStatus.IMPROVED, ReportChangeStatus.WORSENED);
		when(reportRepository.findTopByCurrentSkinAnalysis_UserIdOrderByReportDateDescIdDesc(USER_ID))
				.thenReturn(Optional.of(report));

		when(skinComparisonRepository.findByCurrentSkinAnalysis_IdAndPreviousSkinAnalysis_Id(eq(4L), eq(3L)))
				.thenReturn(Optional.empty());

		ReportDto.SkinSignalResponse response = reportService.getLatestSkinSignal(USER_ID);

		assertThat(response.redness().direction()).isEqualTo(ChangeDirection.DECREASED);
		assertThat(response.redness().message()).isEqualTo("이전보다 붉은기가 감소했어요.");
		assertThat(response.trouble().direction()).isEqualTo(ChangeDirection.INCREASED);
		assertThat(response.trouble().message()).isEqualTo("이전보다 트러블이 증가했어요.");
	}

	@Test
	void 직전_분석이_없는_리포트면_비교_없음_안내_신호를_반환한다() {
		// 첫 피부 분석 리포트(previousSkinAnalysis=null)는 skin-signal도 비교 불가 안내로 응답해야 한다.
		SkinAnalysis current = skinAnalysisWithId(9L);
		Report report = mock(Report.class);
		when(report.getPreviousSkinAnalysis()).thenReturn(null);
		when(reportRepository.findTopByCurrentSkinAnalysis_UserIdOrderByReportDateDescIdDesc(USER_ID))
				.thenReturn(Optional.of(report));

		ReportDto.SkinSignalResponse response = reportService.getLatestSkinSignal(USER_ID);

		assertThat(response.redness().direction()).isEqualTo(ChangeDirection.STABLE);
		assertThat(response.redness().message()).contains("첫 피부 분석");
		verifyNoInteractions(skinComparisonRepository);
	}

	@Test
	void 파라미터가_전부_없으면_최근_30일_범위로_로그인_사용자의_리포트만_조회한다() {
		// 요구사항: date/startDate/endDate가 전부 없으면 "최근 30일(오늘 포함)"이 기본 동작이어야 하고,
		// 반드시 userId로 스코프된 쿼리를 써야 한다(전체 사용자 대상 findAllByOrderByReportDateDescIdDesc는 쓰면 안 됨).
		Report report = reportSummaryOf(101L, LocalDate.of(2026, 8, 10), SkinAnalysisLevel.CAUTION, "요약1");
		LocalDate today = LocalDate.now();
		LocalDate expectedStart = today.minusDays(29);
		ArgumentCaptor<LocalDate> startCaptor = ArgumentCaptor.forClass(LocalDate.class);
		ArgumentCaptor<LocalDate> endCaptor = ArgumentCaptor.forClass(LocalDate.class);
		when(reportRepository.findAllByCurrentSkinAnalysis_UserIdAndReportDateBetweenOrderByReportDateDescIdDesc(
				eq(USER_ID), startCaptor.capture(), endCaptor.capture())).thenReturn(List.of(report));

		ReportDto.ListResponse response = reportService.getReports(USER_ID, null, null, null);

		assertThat(response.reports()).hasSize(1);
		ReportDto.ReportListItem item = response.reports().get(0);
		assertThat(item.reportId()).isEqualTo(101L);
		assertThat(item.reportDate()).isEqualTo(LocalDate.of(2026, 8, 10));
		assertThat(item.skinLevel()).isEqualTo(SkinAnalysisLevel.CAUTION);
		assertThat(item.summary()).isEqualTo("요약1");
		assertThat(startCaptor.getValue()).isEqualTo(expectedStart);
		assertThat(endCaptor.getValue()).isEqualTo(today);
		verify(reportRepository, never()).findAllByOrderByReportDateDescIdDesc();
		verifyNoMoreInteractions(reportRepository);
	}

	@Test
	void startDate_endDate가_주어지면_해당_범위로_사용자_스코프_조회한다() {
		LocalDate start = LocalDate.of(2026, 7, 1);
		LocalDate end = LocalDate.of(2026, 7, 15);
		Report report = reportSummaryOf(102L, LocalDate.of(2026, 7, 10), SkinAnalysisLevel.SAFE, "요약1-1");
		when(reportRepository.findAllByCurrentSkinAnalysis_UserIdAndReportDateBetweenOrderByReportDateDescIdDesc(USER_ID, start, end))
				.thenReturn(List.of(report));

		ReportDto.ListResponse response = reportService.getReports(USER_ID, null, start, end);

		assertThat(response.reports()).hasSize(1);
		verify(reportRepository).findAllByCurrentSkinAnalysis_UserIdAndReportDateBetweenOrderByReportDateDescIdDesc(USER_ID, start, end);
	}

	@Test
	void date_파라미터가_있으면_startDate_endDate를_무시하고_해당_날짜의_사용자_리포트만_조회한다() {
		LocalDate date = LocalDate.of(2026, 8, 7);
		Report report = reportSummaryOf(55L, date, SkinAnalysisLevel.SAFE, "요약2");
		when(reportRepository.findAllByCurrentSkinAnalysis_UserIdAndReportDateOrderByIdDesc(USER_ID, date))
				.thenReturn(List.of(report));

		ReportDto.ListResponse response = reportService.getReports(
				USER_ID, date, LocalDate.of(2020, 1, 1), LocalDate.of(2020, 1, 2));

		assertThat(response.reports()).hasSize(1);
		assertThat(response.reports().get(0).reportId()).isEqualTo(55L);
		verify(reportRepository).findAllByCurrentSkinAnalysis_UserIdAndReportDateOrderByIdDesc(USER_ID, date);
		verify(reportRepository, never()).findAllByCurrentSkinAnalysis_UserIdAndReportDateBetweenOrderByReportDateDescIdDesc(any(), any(), any());
		verifyNoMoreInteractions(reportRepository);
	}

	@Test
	void 조회_결과가_없으면_빈_배열을_반환한다() {
		when(reportRepository.findAllByCurrentSkinAnalysis_UserIdAndReportDateBetweenOrderByReportDateDescIdDesc(eq(USER_ID), any(), any()))
				.thenReturn(List.of());

		ReportDto.ListResponse response = reportService.getReports(USER_ID, null, null, null);

		assertThat(response.reports()).isEmpty();
	}

	@Test
	void 본인_소유_reportId_상세조회는_정상_반환된다() {
		Report report = mock(Report.class);
		SkinAnalysis owner = mock(SkinAnalysis.class);
		when(owner.getUserId()).thenReturn(USER_ID);
		when(report.getId()).thenReturn(7L);
		when(report.getReportDate()).thenReturn(LocalDate.of(2026, 8, 1));
		when(report.getRednessPreviousScore()).thenReturn(1);
		when(report.getRednessCurrentScore()).thenReturn(0);
		when(report.getRednessStatus()).thenReturn(ReportChangeStatus.IMPROVED);
		when(report.getTroublePreviousScore()).thenReturn(0);
		when(report.getTroubleCurrentScore()).thenReturn(0);
		when(report.getTroubleStatus()).thenReturn(ReportChangeStatus.UNCHANGED);
		when(report.getPreviousSkinAnalysis()).thenReturn(mock(SkinAnalysis.class));
		when(report.getCurrentSkinAnalysis()).thenReturn(owner);
		when(report.getPrimaryCausesJson()).thenReturn("[]");
		when(report.getSummary()).thenReturn("요약3");
		when(reportRepository.findById(7L)).thenReturn(Optional.of(report));
		when(objectMapper.readValue(eq("[]"), org.mockito.ArgumentMatchers.<tools.jackson.core.type.TypeReference<List<ReportDto.PrimaryCause>>>any()))
				.thenReturn(List.of());

		ReportDto.Response response = reportService.getReport(USER_ID, 7L);

		assertThat(response.reportId()).isEqualTo(7L);
		assertThat(response.summary()).isEqualTo("요약3");
		assertThat(response.primaryCauses()).isEmpty();
	}

	@Test
	void 다른_사용자_소유_reportId_상세조회시_FORBIDDEN을_던진다() {
		Long otherUserId = 999L;
		Report report = mock(Report.class);
		SkinAnalysis owner = mock(SkinAnalysis.class);
		when(owner.getUserId()).thenReturn(otherUserId);
		when(report.getCurrentSkinAnalysis()).thenReturn(owner);
		when(reportRepository.findById(7L)).thenReturn(Optional.of(report));

		assertThatThrownBy(() -> reportService.getReport(USER_ID, 7L))
				.isInstanceOf(GlobalException.class)
				.extracting("errorCode")
				.isEqualTo(ErrorCode.FORBIDDEN);
		verifyNoInteractions(objectMapper);
	}

	@Test
	void 존재하지_않는_reportId면_REPORT_NOT_FOUND_예외를_던진다() {
		when(reportRepository.findById(999L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> reportService.getReport(USER_ID, 999L))
				.isInstanceOf(GlobalException.class)
				.extracting("errorCode")
				.isEqualTo(ErrorCode.REPORT_NOT_FOUND);
	}

	@Test
	void 저장된_리포트가_없으면_getLatestSkinReport은_REPORT_NOT_FOUND를_던진다() {
		when(reportRepository.findTopByCurrentSkinAnalysis_UserIdOrderByReportDateDescIdDesc(USER_ID))
				.thenReturn(Optional.empty());

		assertThatThrownBy(() -> reportService.getLatestSkinReport(USER_ID))
				.isInstanceOf(GlobalException.class)
				.extracting("errorCode")
				.isEqualTo(ErrorCode.REPORT_NOT_FOUND);
		verifyNoInteractions(aiClient);
	}

	@Test
	void tryGetLatestSkinReport은_정상_조회되면_Optional로_감싸서_반환한다() {
		ReportDto.Response response = latestReportWithCauses(ReportCauseFactor.SLEEP);
		doReturn(response).when(reportService).getLatestSkinReport(USER_ID);

		Optional<ReportDto.Response> result = reportService.tryGetLatestSkinReport(USER_ID);

		assertThat(result).contains(response);
	}

	@Test
	void tryGetLatestSkinReport은_저장된_리포트가_없어도_흡수하고_빈_Optional을_반환한다() {
		doThrow(new GlobalException(ErrorCode.REPORT_NOT_FOUND)).when(reportService).getLatestSkinReport(USER_ID);

		Optional<ReportDto.Response> result = reportService.tryGetLatestSkinReport(USER_ID);

		assertThat(result).isEmpty();
	}

	@Test
	void getLatestSavedReport은_저장된_리포트가_있으면_반환하고_AiClient를_전혀_호출하지_않는다() {
		Report report = mock(Report.class);
		when(report.getId()).thenReturn(55L);
		when(report.getReportDate()).thenReturn(LocalDate.of(2026, 8, 12));
		when(report.getRednessPreviousScore()).thenReturn(1);
		when(report.getRednessCurrentScore()).thenReturn(0);
		when(report.getRednessStatus()).thenReturn(ReportChangeStatus.IMPROVED);
		when(report.getTroublePreviousScore()).thenReturn(0);
		when(report.getTroubleCurrentScore()).thenReturn(0);
		when(report.getTroubleStatus()).thenReturn(ReportChangeStatus.UNCHANGED);
		when(report.getPreviousSkinAnalysis()).thenReturn(mock(SkinAnalysis.class));
		when(report.getPrimaryCausesJson()).thenReturn("[]");
		when(report.getSummary()).thenReturn("요약4");
		when(reportRepository.findTopByCurrentSkinAnalysis_UserIdOrderByReportDateDescIdDesc(USER_ID))
				.thenReturn(Optional.of(report));
		when(objectMapper.readValue(eq("[]"), org.mockito.ArgumentMatchers.<tools.jackson.core.type.TypeReference<List<ReportDto.PrimaryCause>>>any()))
				.thenReturn(List.of());

		Optional<ReportDto.Response> result = reportService.getLatestSavedReport(USER_ID);

		assertThat(result).isPresent();
		assertThat(result.get().reportId()).isEqualTo(55L);
		assertThat(result.get().summary()).isEqualTo("요약4");
		verifyNoInteractions(aiClient);
	}

	@Test
	void getLatestSavedReport은_레거시_저장데이터에_내부_상태값이_남아있어도_자연어로_치환해_반환한다() {
		// 1차 방어선(validateNoInternalStateLeak) 도입 이전에 저장된 레거시 summary/primary_causes를
		// 흉내낸다 - 조회 시점(UserFacingTextGuard.sanitize)에서 걸러져야 한다.
		Report report = mock(Report.class);
		when(report.getId()).thenReturn(56L);
		when(report.getReportDate()).thenReturn(LocalDate.of(2026, 8, 12));
		when(report.getRednessPreviousScore()).thenReturn(1);
		when(report.getRednessCurrentScore()).thenReturn(0);
		when(report.getRednessStatus()).thenReturn(ReportChangeStatus.IMPROVED);
		when(report.getTroublePreviousScore()).thenReturn(0);
		when(report.getTroubleCurrentScore()).thenReturn(0);
		when(report.getTroubleStatus()).thenReturn(ReportChangeStatus.UNCHANGED);
		when(report.getPreviousSkinAnalysis()).thenReturn(mock(SkinAnalysis.class));
		when(report.getPrimaryCausesJson()).thenReturn("legacy-json");
		when(report.getSummary()).thenReturn(
				"붉은기와 트러블은 모두 1 감소해 IMPROVED 및 DECREASED 상태이며, 주요 위험 요인 후보로 POOR 판정을 받은 스트레스와 수분 섭취가 확인됩니다.");
		when(reportRepository.findTopByCurrentSkinAnalysis_UserIdOrderByReportDateDescIdDesc(USER_ID))
				.thenReturn(Optional.of(report));
		ReportDto.PrimaryCause legacyCause = new ReportDto.PrimaryCause(
				ReportCauseFactor.STRESS, "STRESS 요인", 5.0, "점",
				"POOR 판정을 받아 DANGER 수준입니다.", 3.0, 2.0,
				com.sangmyungyaho.barocare.report.entity.BaselineType.PERSONAL_AVERAGE
		);
		when(objectMapper.readValue(eq("legacy-json"),
				org.mockito.ArgumentMatchers.<tools.jackson.core.type.TypeReference<List<ReportDto.PrimaryCause>>>any()))
				.thenReturn(List.of(legacyCause));

		Optional<ReportDto.Response> result = reportService.getLatestSavedReport(USER_ID);

		assertThat(result).isPresent();
		ReportDto.Response response = result.get();
		assertThat(response.summary())
				.isEqualTo("붉은기와 트러블은 모두 1 감소해 개선됨 및 감소함 상태이며, 주요 위험 요인 후보로 부족 판정을 받은 스트레스와 수분 섭취가 확인됩니다.");
		assertThat(response.primaryCauses()).hasSize(1);
		assertThat(response.primaryCauses().get(0).name()).isEqualTo("스트레스 요인");
		assertThat(response.primaryCauses().get(0).description()).isEqualTo("부족 판정을 받아 위험 수준입니다.");
		assertThat(com.sangmyungyaho.barocare.global.text.UserFacingTextGuard.containsLeak(response.summary())).isFalse();
		assertThat(com.sangmyungyaho.barocare.global.text.UserFacingTextGuard.containsLeak(response.primaryCauses().get(0).description())).isFalse();
	}

	@Test
	void getLatestSavedReport은_저장된_리포트가_없으면_빈_Optional을_반환하고_새로_생성하지_않는다() {
		when(reportRepository.findTopByCurrentSkinAnalysis_UserIdOrderByReportDateDescIdDesc(USER_ID))
				.thenReturn(Optional.empty());

		Optional<ReportDto.Response> result = reportService.getLatestSavedReport(USER_ID);

		assertThat(result).isEmpty();
		verifyNoInteractions(aiClient);
		verify(skinAnalysisRepository, never()).findTopByUserIdAndAnalyzedAtLessThanOrderByAnalyzedAtDesc(any(), any());
		verify(checkinRepository, never()).findAllByUserIdAndCheckedDateLessThanOrderByCheckedDateDesc(any(), any());
	}

	@Test
	void getPrimaryCauseFactors은_저장된_Report의_primaryCauses에서_factor만_뽑아_반환한다() {
		Report report = mock(Report.class);
		when(report.getPrimaryCausesJson()).thenReturn("[]");
		when(objectMapper.readValue(eq("[]"), org.mockito.ArgumentMatchers.<tools.jackson.core.type.TypeReference<List<ReportDto.PrimaryCause>>>any()))
				.thenReturn(List.of(new ReportDto.PrimaryCause(
						ReportCauseFactor.SLEEP, "수면 부족", 5.0, "시간", "설명", 7.0, -2.0, com.sangmyungyaho.barocare.report.entity.BaselineType.RECOMMENDED)));

		List<ReportCauseFactor> factors = reportService.getPrimaryCauseFactors(report);

		assertThat(factors).containsExactly(ReportCauseFactor.SLEEP);
	}

	private Report reportSummaryOf(Long id, LocalDate reportDate, SkinAnalysisLevel skinLevel, String summary) {
		SkinAnalysis currentSkinAnalysis = mock(SkinAnalysis.class);
		lenient().when(currentSkinAnalysis.getSkinLevel()).thenReturn(skinLevel);

		Report report = mock(Report.class);
		lenient().when(report.getId()).thenReturn(id);
		lenient().when(report.getReportDate()).thenReturn(reportDate);
		lenient().when(report.getCurrentSkinAnalysis()).thenReturn(currentSkinAnalysis);
		lenient().when(report.getSummary()).thenReturn(summary);
		return report;
	}

	private SkinAnalysis skinAnalysisWithId(Long id) {
		SkinAnalysis skinAnalysis = mock(SkinAnalysis.class);
		lenient().when(skinAnalysis.getId()).thenReturn(id);
		return skinAnalysis;
	}

	private Report reportOf(
			SkinAnalysis current, SkinAnalysis previous, ReportChangeStatus rednessStatus, ReportChangeStatus troubleStatus
	) {
		Report report = mock(Report.class);
		lenient().when(report.getId()).thenReturn(99L);
		lenient().when(report.getCurrentSkinAnalysis()).thenReturn(current);
		lenient().when(report.getPreviousSkinAnalysis()).thenReturn(previous);
		// SkinComparison이 존재하는 테스트에서는 fallback 분기(getRednessStatus/getTroubleStatus)가
		// 호출되지 않으므로 lenient로 표시한다 - strict stub 검증에서 미사용 스텁 오류가 나지 않게 한다.
		lenient().when(report.getRednessStatus()).thenReturn(rednessStatus);
		lenient().when(report.getTroubleStatus()).thenReturn(troubleStatus);
		return report;
	}

	private ReportDto.Response latestReportWithCauses(ReportCauseFactor... factors) {
		List<ReportDto.PrimaryCause> causes = List.of(factors).stream()
				.map(factor -> new ReportDto.PrimaryCause(factor, factor.name(), 1.0, "unit", "설명", null, null, null))
				.toList();
		return new ReportDto.Response(1L, LocalDate.of(2026, 8, 10), null, true, causes, "summary");
	}
}
