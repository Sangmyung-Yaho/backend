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
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Issue #27: 고위험 조합 경고(getLatestCauseWarnings) 및
 * Issue #28: 복합 원인 분석 및 피부 변화 설명(getLatestCauseInteractions, getLatestSkinSignal) 단위 테스트.
 *
 * 최신 원인 리포트 계산(getLatestSkinReport, SkinAnalysis/Checkin 조회·AI 호출 등)은 이미
 * REP-101에서 검증된 별도 로직이므로, getLatestSkinReport()를 직접 호출하는 API는 스텁으로
 * 대체하고 위임 결과만 검증한다. skin-signal은 Report 엔티티(연관 SkinAnalysis, status)가
 * 그대로 필요하므로 find-or-create 경로를 리포지토리 레벨에서 스텁한다.
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

	@Test
	void 고위험_조합이_없으면_에러_없이_빈_warnings를_반환한다() {
		doReturn(latestReportWithCauses(ReportCauseFactor.WATER_INTAKE)).when(reportService).getLatestSkinReport(USER_ID);

		ReportDto.WarningsResponse response = reportService.getLatestCauseWarnings(USER_ID);

		assertThat(response.warnings()).isEmpty();
	}

	@Test
	void SLEEP과_STRESS만_있으면_해당_조합_경고_하나만_반환한다() {
		doReturn(latestReportWithCauses(ReportCauseFactor.SLEEP, ReportCauseFactor.STRESS))
				.when(reportService).getLatestSkinReport(USER_ID);

		ReportDto.WarningsResponse response = reportService.getLatestCauseWarnings(USER_ID);

		assertThat(response.warnings()).hasSize(1);
		assertThat(response.warnings().get(0).level()).isEqualTo(WarningLevel.HIGH);
		assertThat(response.warnings().get(0).factors())
				.containsExactly(ReportCauseFactor.SLEEP, ReportCauseFactor.STRESS);
	}

	@Test
	void 세_요인이_모두_있으면_3요인_경고_하나만_반환하고_2요인_경고와_중복되지_않는다() {
		doReturn(latestReportWithCauses(ReportCauseFactor.SLEEP, ReportCauseFactor.STRESS, ReportCauseFactor.WATER_INTAKE))
				.when(reportService).getLatestSkinReport(USER_ID);

		ReportDto.WarningsResponse response = reportService.getLatestCauseWarnings(USER_ID);

		assertThat(response.warnings()).hasSize(1);
		assertThat(response.warnings().get(0).factors())
				.containsExactly(ReportCauseFactor.SLEEP, ReportCauseFactor.STRESS, ReportCauseFactor.WATER_INTAKE);
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
	void 세_요인이_모두_있으면_상호작용도_3요인_설명_하나만_반환하고_2요인_설명과_중복되지_않는다() {
		doReturn(latestReportWithCauses(ReportCauseFactor.SLEEP, ReportCauseFactor.STRESS, ReportCauseFactor.WATER_INTAKE))
				.when(reportService).getLatestSkinReport(USER_ID);

		ReportDto.InteractionsResponse response = reportService.getLatestCauseInteractions(USER_ID);

		assertThat(response.interactions()).hasSize(1);
		assertThat(response.interactions().get(0).factors())
				.containsExactly(ReportCauseFactor.SLEEP, ReportCauseFactor.STRESS, ReportCauseFactor.WATER_INTAKE);
	}

	@Test
	void SkinComparison이_있으면_그_ChangeDirection을_그대로_사용한다() {
		SkinAnalysis current = skinAnalysisWithId(2L);
		SkinAnalysis previous = skinAnalysisWithId(1L);
		Report report = reportOf(current, previous, ReportChangeStatus.IMPROVED, ReportChangeStatus.IMPROVED);
		stubExistingReport(current, previous, report);

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
		stubExistingReport(current, previous, report);

		when(skinComparisonRepository.findByCurrentSkinAnalysis_IdAndPreviousSkinAnalysis_Id(eq(4L), eq(3L)))
				.thenReturn(Optional.empty());

		ReportDto.SkinSignalResponse response = reportService.getLatestSkinSignal(USER_ID);

		assertThat(response.redness().direction()).isEqualTo(ChangeDirection.DECREASED);
		assertThat(response.redness().message()).isEqualTo("이전보다 붉은기가 감소했어요.");
		assertThat(response.trouble().direction()).isEqualTo(ChangeDirection.INCREASED);
		assertThat(response.trouble().message()).isEqualTo("이전보다 트러블이 증가했어요.");
	}

	@Test
	void 등급_변화가_없으면_STABLE_신호를_반환한다() {
		SkinAnalysis current = skinAnalysisWithId(6L);
		SkinAnalysis previous = skinAnalysisWithId(5L);
		Report report = reportOf(current, previous, ReportChangeStatus.UNCHANGED, ReportChangeStatus.UNCHANGED);
		stubExistingReport(current, previous, report);

		when(skinComparisonRepository.findByCurrentSkinAnalysis_IdAndPreviousSkinAnalysis_Id(eq(6L), eq(5L)))
				.thenReturn(Optional.empty());

		ReportDto.SkinSignalResponse response = reportService.getLatestSkinSignal(USER_ID);

		assertThat(response.redness().direction()).isEqualTo(ChangeDirection.STABLE);
		assertThat(response.redness().message()).isEqualTo("붉은기가 이전과 비슷한 상태예요.");
		assertThat(response.trouble().direction()).isEqualTo(ChangeDirection.STABLE);
		assertThat(response.trouble().message()).isEqualTo("트러블이 이전과 비슷한 상태예요.");
	}

	@Test
	void date_파라미터가_없으면_전체_리포트를_최신순으로_조회한다() {
		Report report = reportSummaryOf(101L, LocalDate.of(2026, 8, 10), SkinAnalysisLevel.CAUTION, "요약1");
		when(reportRepository.findAllByOrderByReportDateDescIdDesc()).thenReturn(List.of(report));

		ReportDto.ListResponse response = reportService.getReports(USER_ID, null);

		assertThat(response.reports()).hasSize(1);
		ReportDto.ReportListItem item = response.reports().get(0);
		assertThat(item.reportId()).isEqualTo(101L);
		assertThat(item.reportDate()).isEqualTo(LocalDate.of(2026, 8, 10));
		assertThat(item.skinLevel()).isEqualTo(SkinAnalysisLevel.CAUTION);
		assertThat(item.summary()).isEqualTo("요약1");
		verify(reportRepository).findAllByOrderByReportDateDescIdDesc();
		verifyNoMoreInteractions(reportRepository);
	}

	@Test
	void date_파라미터가_있으면_해당_날짜의_리포트만_조회한다() {
		LocalDate date = LocalDate.of(2026, 8, 7);
		Report report = reportSummaryOf(55L, date, SkinAnalysisLevel.SAFE, "요약2");
		when(reportRepository.findByReportDateOrderByIdDesc(date)).thenReturn(List.of(report));

		ReportDto.ListResponse response = reportService.getReports(USER_ID, date);

		assertThat(response.reports()).hasSize(1);
		assertThat(response.reports().get(0).reportId()).isEqualTo(55L);
		verify(reportRepository).findByReportDateOrderByIdDesc(date);
		verifyNoMoreInteractions(reportRepository);
	}

	@Test
	void 조회_결과가_없으면_빈_배열을_반환한다() {
		when(reportRepository.findAllByOrderByReportDateDescIdDesc()).thenReturn(List.of());

		ReportDto.ListResponse response = reportService.getReports(USER_ID, null);

		assertThat(response.reports()).isEmpty();
	}

	@Test
	void 존재하는_reportId면_기존_ReportDto_Response를_그대로_반환한다() {
		Report report = mock(Report.class);
		when(report.getId()).thenReturn(7L);
		when(report.getReportDate()).thenReturn(LocalDate.of(2026, 8, 1));
		when(report.getRednessPreviousScore()).thenReturn(1);
		when(report.getRednessCurrentScore()).thenReturn(0);
		when(report.getRednessStatus()).thenReturn(ReportChangeStatus.IMPROVED);
		when(report.getTroublePreviousScore()).thenReturn(0);
		when(report.getTroubleCurrentScore()).thenReturn(0);
		when(report.getTroubleStatus()).thenReturn(ReportChangeStatus.UNCHANGED);
		when(report.getPrimaryCausesJson()).thenReturn("[]");
		when(report.getSummary()).thenReturn("요약3");
		when(reportRepository.findById(7L)).thenReturn(Optional.of(report));
		when(objectMapper.readValue(eq("[]"), org.mockito.ArgumentMatchers.<tools.jackson.core.type.TypeReference<List<ReportDto.PrimaryCause>>>any()))
				.thenReturn(List.of());

		ReportDto.Response response = reportService.getReport(7L);

		assertThat(response.reportId()).isEqualTo(7L);
		assertThat(response.summary()).isEqualTo("요약3");
		assertThat(response.primaryCauses()).isEmpty();
	}

	@Test
	void 리포트_생성시_생활습관_요인판정_결과와_목표_음수량을_반영해_AiClient에_전달한다() {
		// feat: 개인화 피부 원인 분석 - GPT에 raw 체크인 수치만 넘기지 않고, LifestyleFactorRubric이
		// 먼저 판정한 GOOD/MODERATE/POOR와 개인 기준선 사용 여부를 CheckinInput에 담아 전달하는지 검증한다.
		Long currentId = 20L;
		SkinAnalysis current = mock(SkinAnalysis.class);
		SkinAnalysis previous = mock(SkinAnalysis.class);
		when(current.getId()).thenReturn(currentId);
		when(current.getAnalyzedAt()).thenReturn(LocalDateTime.of(2026, 8, 10, 9, 0));
		when(current.getRednessLevel()).thenReturn(SkinAnalysisLevel.SAFE);
		when(current.getTroubleLevel()).thenReturn(SkinAnalysisLevel.SAFE);
		when(previous.getRednessLevel()).thenReturn(SkinAnalysisLevel.CAUTION);
		when(previous.getTroubleLevel()).thenReturn(SkinAnalysisLevel.CAUTION);
		when(skinAnalysisRepository.findTop2ByUserIdOrderByAnalyzedAtDesc(USER_ID)).thenReturn(List.of(current, previous));
		when(reportRepository.findByCurrentSkinAnalysis_Id(currentId)).thenReturn(Optional.empty());

		// 최근 체크인은 수면 부족/스트레스 높음/수분 부족, 이전 7일은 모두 양호 -> 개인 기준선 기준으로 POOR가 나와야 한다.
		Checkin latestCheckin = new Checkin(USER_ID, 5.0, 4, 800, LocalDate.of(2026, 8, 10));
		List<Checkin> allCheckins = new ArrayList<>();
		allCheckins.add(latestCheckin);
		for (int i = 1; i <= 7; i++) {
			allCheckins.add(new Checkin(USER_ID, 7.0, 2, 2000, LocalDate.of(2026, 8, 10).minusDays(i)));
		}
		when(checkinRepository.findAllByUserIdAndCheckedDateLessThanEqualOrderByCheckedDateDesc(USER_ID, LocalDate.of(2026, 8, 10)))
				.thenReturn(allCheckins);

		User user = mock(User.class);
		when(user.getWaterGoalMl()).thenReturn(2000);
		when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

		ArgumentCaptor<AiDto.SkinChangeInput> skinChangeInputCaptor = ArgumentCaptor.forClass(AiDto.SkinChangeInput.class);
		ArgumentCaptor<AiDto.CheckinInput> checkinInputCaptor = ArgumentCaptor.forClass(AiDto.CheckinInput.class);
		when(aiClient.analyzeSkinChangeCauses(skinChangeInputCaptor.capture(), checkinInputCaptor.capture()))
				.thenReturn(new AiDto.CauseAnalysisResult(List.of(), "요약"));
		when(objectMapper.writeValueAsString(any())).thenReturn("[]");
		when(reportRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

		reportService.getLatestSkinReport(USER_ID);

		AiDto.CheckinInput capturedCheckin = checkinInputCaptor.getValue();
		assertThat(capturedCheckin.personalBaselineUsed()).isTrue();
		assertThat(capturedCheckin.sleepLevel()).isEqualTo(LifestyleFactorLevel.POOR);
		assertThat(capturedCheckin.stressLevel()).isEqualTo(LifestyleFactorLevel.POOR);
		assertThat(capturedCheckin.waterLevel()).isEqualTo(LifestyleFactorLevel.POOR);
		// #3: 세 요인 모두 POOR이므로 셋 다 "주요 위험 요인 후보"로 확정되어 GPT에 전달돼야 한다.
		assertThat(capturedCheckin.candidateFactors())
				.containsExactlyInAnyOrder(ReportCauseFactor.SLEEP, ReportCauseFactor.STRESS, ReportCauseFactor.WATER_INTAKE);
		verify(userRepository).findById(USER_ID);

		// #1: SkinComparison/baseline이 없으므로(스텁하지 않음) 등급 변화(status)로부터 결정적으로 유도된
		// 값이어야 한다. current(SAFE=0) vs previous(CAUTION=1) -> change=-1 -> IMPROVED -> DECREASED.
		AiDto.SkinChangeInput capturedSkinChange = skinChangeInputCaptor.getValue();
		assertThat(capturedSkinChange.rednessDirection()).isEqualTo(ChangeDirection.DECREASED);
		assertThat(capturedSkinChange.troubleDirection()).isEqualTo(ChangeDirection.DECREASED);
		assertThat(capturedSkinChange.comparedAgainstBaseline()).isFalse();
		assertThat(capturedSkinChange.baselineRednessLevel()).isNull();
		assertThat(capturedSkinChange.baselineTroubleLevel()).isNull();
	}

	@Test
	void 이미_계산된_SkinComparison이_있으면_원인_분석_입력도_그_ChangeDirection을_재사용한다() {
		// #1: 피부 변화 비교는 기존 SkinComparisonService/SkinComparison을 재사용해야 하며, 새 AI 호출을
		// 유발하지 않는다(skin-signal과 동일한 fallback 규칙).
		Long currentId = 21L;
		SkinAnalysis current = skinAnalysisWithId(currentId);
		SkinAnalysis previous = skinAnalysisWithId(11L);
		lenient().when(current.getAnalyzedAt()).thenReturn(LocalDateTime.of(2026, 8, 10, 9, 0));
		lenient().when(current.getRednessLevel()).thenReturn(SkinAnalysisLevel.SAFE);
		lenient().when(current.getTroubleLevel()).thenReturn(SkinAnalysisLevel.SAFE);
		lenient().when(previous.getRednessLevel()).thenReturn(SkinAnalysisLevel.SAFE);
		lenient().when(previous.getTroubleLevel()).thenReturn(SkinAnalysisLevel.SAFE);
		when(skinAnalysisRepository.findTop2ByUserIdOrderByAnalyzedAtDesc(USER_ID)).thenReturn(List.of(current, previous));
		when(reportRepository.findByCurrentSkinAnalysis_Id(currentId)).thenReturn(Optional.empty());

		SkinComparison comparison = mock(SkinComparison.class);
		when(comparison.getRednessChange()).thenReturn(ChangeDirection.INCREASED);
		when(comparison.getTroubleChange()).thenReturn(ChangeDirection.STABLE);
		when(skinComparisonRepository.findByCurrentSkinAnalysis_IdAndPreviousSkinAnalysis_Id(currentId, 11L))
				.thenReturn(Optional.of(comparison));

		Checkin latestCheckin = new Checkin(USER_ID, 8.0, 1, 2000, LocalDate.of(2026, 8, 10));
		when(checkinRepository.findAllByUserIdAndCheckedDateLessThanEqualOrderByCheckedDateDesc(USER_ID, LocalDate.of(2026, 8, 10)))
				.thenReturn(List.of(latestCheckin));
		when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

		ArgumentCaptor<AiDto.SkinChangeInput> skinChangeInputCaptor = ArgumentCaptor.forClass(AiDto.SkinChangeInput.class);
		when(aiClient.analyzeSkinChangeCauses(skinChangeInputCaptor.capture(), any()))
				.thenReturn(new AiDto.CauseAnalysisResult(List.of(), "요약"));
		when(objectMapper.writeValueAsString(any())).thenReturn("[]");
		when(reportRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

		reportService.getLatestSkinReport(USER_ID);

		AiDto.SkinChangeInput captured = skinChangeInputCaptor.getValue();
		// status만 보면 둘 다 UNCHANGED(등급 변화 없음)라 STABLE이어야 하지만, 이미 계산된 SkinComparison이
		// 있으므로 그 값(INCREASED/STABLE)이 우선한다 - fallback으로 덮이지 않아야 한다.
		assertThat(captured.rednessDirection()).isEqualTo(ChangeDirection.INCREASED);
		assertThat(captured.troubleDirection()).isEqualTo(ChangeDirection.STABLE);
	}

	@Test
	void baseline이_직전_분석과_다르면_baseline_등급을_참고정보로_함께_전달한다() {
		// #1: 세 번째 이상 분석이면 baseline(최초 분석)이 previous보다 더 이전 시점이다. 이 경우
		// comparedAgainstBaseline=false이고, baseline 등급을 참고 정보로 함께 전달해야 한다.
		Long currentId = 33L;
		SkinAnalysis current = skinAnalysisWithId(currentId);
		SkinAnalysis previous = skinAnalysisWithId(22L);
		SkinAnalysis baseline = skinAnalysisWithId(11L);
		lenient().when(current.getAnalyzedAt()).thenReturn(LocalDateTime.of(2026, 8, 10, 9, 0));
		lenient().when(current.getRednessLevel()).thenReturn(SkinAnalysisLevel.SAFE);
		lenient().when(current.getTroubleLevel()).thenReturn(SkinAnalysisLevel.SAFE);
		lenient().when(previous.getRednessLevel()).thenReturn(SkinAnalysisLevel.SAFE);
		lenient().when(previous.getTroubleLevel()).thenReturn(SkinAnalysisLevel.SAFE);
		lenient().when(baseline.getRednessLevel()).thenReturn(SkinAnalysisLevel.DANGER);
		lenient().when(baseline.getTroubleLevel()).thenReturn(SkinAnalysisLevel.CAUTION);
		when(skinAnalysisRepository.findTop2ByUserIdOrderByAnalyzedAtDesc(USER_ID)).thenReturn(List.of(current, previous));
		when(skinAnalysisRepository.findFirstByUserIdOrderByAnalyzedAtAsc(USER_ID)).thenReturn(Optional.of(baseline));
		when(reportRepository.findByCurrentSkinAnalysis_Id(currentId)).thenReturn(Optional.empty());

		Checkin latestCheckin = new Checkin(USER_ID, 8.0, 1, 2000, LocalDate.of(2026, 8, 10));
		when(checkinRepository.findAllByUserIdAndCheckedDateLessThanEqualOrderByCheckedDateDesc(USER_ID, LocalDate.of(2026, 8, 10)))
				.thenReturn(List.of(latestCheckin));
		when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

		ArgumentCaptor<AiDto.SkinChangeInput> skinChangeInputCaptor = ArgumentCaptor.forClass(AiDto.SkinChangeInput.class);
		when(aiClient.analyzeSkinChangeCauses(skinChangeInputCaptor.capture(), any()))
				.thenReturn(new AiDto.CauseAnalysisResult(List.of(), "요약"));
		when(objectMapper.writeValueAsString(any())).thenReturn("[]");
		when(reportRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

		reportService.getLatestSkinReport(USER_ID);

		AiDto.SkinChangeInput captured = skinChangeInputCaptor.getValue();
		assertThat(captured.comparedAgainstBaseline()).isFalse();
		assertThat(captured.baselineRednessLevel()).isEqualTo(SkinAnalysisLevel.DANGER);
		assertThat(captured.baselineTroubleLevel()).isEqualTo(SkinAnalysisLevel.CAUTION);
	}

	@Test
	void AI가_후보_밖_요인을_causes에_포함시켜도_최종_응답에서는_제외된다() {
		// #3: GPT가 candidateFactors 지침을 어기고 정상(POOR 아님) 요인을 causes에 넣어도, ReportService가
		// candidateFactors 기준으로 다시 걸러내 최종 primaryCauses에는 절대 남지 않아야 한다(코드 레벨 강제).
		Long currentId = 40L;
		SkinAnalysis current = skinAnalysisWithId(currentId);
		SkinAnalysis previous = skinAnalysisWithId(39L);
		lenient().when(current.getAnalyzedAt()).thenReturn(LocalDateTime.of(2026, 8, 10, 9, 0));
		lenient().when(current.getRednessLevel()).thenReturn(SkinAnalysisLevel.SAFE);
		lenient().when(current.getTroubleLevel()).thenReturn(SkinAnalysisLevel.SAFE);
		lenient().when(previous.getRednessLevel()).thenReturn(SkinAnalysisLevel.SAFE);
		lenient().when(previous.getTroubleLevel()).thenReturn(SkinAnalysisLevel.SAFE);
		when(skinAnalysisRepository.findTop2ByUserIdOrderByAnalyzedAtDesc(USER_ID)).thenReturn(List.of(current, previous));
		when(reportRepository.findByCurrentSkinAnalysis_Id(currentId)).thenReturn(Optional.empty());

		// 수면만 POOR(5h), 스트레스/수분은 고정 기준표상 GOOD -> candidateFactors=[SLEEP]뿐이어야 한다.
		Checkin latestCheckin = new Checkin(USER_ID, 5.0, 1, 2000, LocalDate.of(2026, 8, 10));
		when(checkinRepository.findAllByUserIdAndCheckedDateLessThanEqualOrderByCheckedDateDesc(USER_ID, LocalDate.of(2026, 8, 10)))
				.thenReturn(List.of(latestCheckin));
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
		// objectMapper는 목이라 실제 JSON 직렬화/역직렬화를 하지 않으므로, 응답 DTO를 통해 최종 결과를
		// 검증하는 대신 toJson()에 실제로 넘어온(=필터링을 거친) primaryCauses 인자를 직접 캡처해서 검증한다.
		ArgumentCaptor<Object> primaryCausesToSerializeCaptor = ArgumentCaptor.forClass(Object.class);
		when(objectMapper.writeValueAsString(primaryCausesToSerializeCaptor.capture())).thenReturn("[]");
		when(reportRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

		reportService.getLatestSkinReport(USER_ID);

		@SuppressWarnings("unchecked")
		List<ReportDto.PrimaryCause> savedPrimaryCauses = (List<ReportDto.PrimaryCause>) primaryCausesToSerializeCaptor.getValue();
		assertThat(savedPrimaryCauses).hasSize(1);
		assertThat(savedPrimaryCauses.get(0).factor()).isEqualTo(ReportCauseFactor.SLEEP);
	}

	@Test
	void 존재하지_않는_reportId면_REPORT_NOT_FOUND_예외를_던진다() {
		when(reportRepository.findById(999L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> reportService.getReport(999L))
				.isInstanceOf(GlobalException.class)
				.extracting("errorCode")
				.isEqualTo(ErrorCode.REPORT_NOT_FOUND);
	}

	@Test
	void tryGetLatestSkinReport은_정상_조회되면_Optional로_감싸서_반환한다() {
		ReportDto.Response response = latestReportWithCauses(ReportCauseFactor.SLEEP);
		doReturn(response).when(reportService).getLatestSkinReport(USER_ID);

		Optional<ReportDto.Response> result = reportService.tryGetLatestSkinReport(USER_ID);

		assertThat(result).contains(response);
	}

	@Test
	void tryGetLatestSkinReport은_데이터_부족_예외를_흡수하고_빈_Optional을_반환한다() {
		// 루틴 생성(RoutineService) 등 호출부가 데이터 부족으로 실패하지 않도록 하는 안전 조회.
		doThrow(new GlobalException(ErrorCode.INSUFFICIENT_ANALYSIS_DATA)).when(reportService).getLatestSkinReport(USER_ID);

		Optional<ReportDto.Response> result = reportService.tryGetLatestSkinReport(USER_ID);

		assertThat(result).isEmpty();
	}

	@Test
	void tryGetLatestSkinReport은_AI_분석_실패도_흡수하고_빈_Optional을_반환한다() {
		doThrow(new GlobalException(ErrorCode.AI_ANALYSIS_FAILED)).when(reportService).getLatestSkinReport(USER_ID);

		Optional<ReportDto.Response> result = reportService.tryGetLatestSkinReport(USER_ID);

		assertThat(result).isEmpty();
	}

	private Report reportSummaryOf(Long id, LocalDate reportDate, SkinAnalysisLevel skinLevel, String summary) {
		SkinAnalysis currentSkinAnalysis = mock(SkinAnalysis.class);
		when(currentSkinAnalysis.getSkinLevel()).thenReturn(skinLevel);

		Report report = mock(Report.class);
		when(report.getId()).thenReturn(id);
		when(report.getReportDate()).thenReturn(reportDate);
		when(report.getCurrentSkinAnalysis()).thenReturn(currentSkinAnalysis);
		when(report.getSummary()).thenReturn(summary);
		return report;
	}

	private void stubExistingReport(SkinAnalysis current, SkinAnalysis previous, Report report) {
		when(skinAnalysisRepository.findTop2ByUserIdOrderByAnalyzedAtDesc(USER_ID)).thenReturn(List.of(current, previous));
		when(reportRepository.findByCurrentSkinAnalysis_Id(current.getId())).thenReturn(Optional.of(report));
	}

	private SkinAnalysis skinAnalysisWithId(Long id) {
		SkinAnalysis skinAnalysis = mock(SkinAnalysis.class);
		when(skinAnalysis.getId()).thenReturn(id);
		return skinAnalysis;
	}

	private Report reportOf(
			SkinAnalysis current, SkinAnalysis previous, ReportChangeStatus rednessStatus, ReportChangeStatus troubleStatus
	) {
		Report report = mock(Report.class);
		when(report.getId()).thenReturn(99L);
		when(report.getCurrentSkinAnalysis()).thenReturn(current);
		when(report.getPreviousSkinAnalysis()).thenReturn(previous);
		// SkinComparison이 존재하는 테스트에서는 fallback 분기(getRednessStatus/getTroubleStatus)가
		// 호출되지 않으므로 lenient로 표시한다 - strict stub 검증에서 미사용 스텁 오류가 나지 않게 한다.
		lenient().when(report.getRednessStatus()).thenReturn(rednessStatus);
		lenient().when(report.getTroubleStatus()).thenReturn(troubleStatus);
		return report;
	}

	private ReportDto.Response latestReportWithCauses(ReportCauseFactor... factors) {
		List<ReportDto.PrimaryCause> causes = List.of(factors).stream()
				.map(factor -> new ReportDto.PrimaryCause(factor, factor.name(), 1.0, "unit", "설명"))
				.toList();
		return new ReportDto.Response(1L, LocalDate.of(2026, 8, 10), null, causes, "summary");
	}
}
