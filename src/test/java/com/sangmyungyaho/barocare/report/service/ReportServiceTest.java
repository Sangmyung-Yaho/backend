package com.sangmyungyaho.barocare.report.service;

import com.sangmyungyaho.barocare.ai.client.AiClient;
import com.sangmyungyaho.barocare.checkin.repository.CheckinRepository;
import com.sangmyungyaho.barocare.global.exception.ErrorCode;
import com.sangmyungyaho.barocare.global.exception.GlobalException;
import com.sangmyungyaho.barocare.report.dto.ReportDto;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
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

	private ReportService reportService;

	@BeforeEach
	void setUp() {
		reportService = spy(new ReportService(
				skinAnalysisRepository, checkinRepository, reportRepository, aiClient, objectMapper,
				new CauseCombinationRubric(), skinComparisonRepository
		));
	}

	@Test
	void 고위험_조합이_없으면_에러_없이_빈_warnings를_반환한다() {
		doReturn(latestReportWithCauses(ReportCauseFactor.WATER_INTAKE)).when(reportService).getLatestSkinReport();

		ReportDto.WarningsResponse response = reportService.getLatestCauseWarnings();

		assertThat(response.warnings()).isEmpty();
	}

	@Test
	void SLEEP과_STRESS만_있으면_해당_조합_경고_하나만_반환한다() {
		doReturn(latestReportWithCauses(ReportCauseFactor.SLEEP, ReportCauseFactor.STRESS))
				.when(reportService).getLatestSkinReport();

		ReportDto.WarningsResponse response = reportService.getLatestCauseWarnings();

		assertThat(response.warnings()).hasSize(1);
		assertThat(response.warnings().get(0).level()).isEqualTo(WarningLevel.HIGH);
		assertThat(response.warnings().get(0).factors())
				.containsExactly(ReportCauseFactor.SLEEP, ReportCauseFactor.STRESS);
	}

	@Test
	void 세_요인이_모두_있으면_3요인_경고_하나만_반환하고_2요인_경고와_중복되지_않는다() {
		doReturn(latestReportWithCauses(ReportCauseFactor.SLEEP, ReportCauseFactor.STRESS, ReportCauseFactor.WATER_INTAKE))
				.when(reportService).getLatestSkinReport();

		ReportDto.WarningsResponse response = reportService.getLatestCauseWarnings();

		assertThat(response.warnings()).hasSize(1);
		assertThat(response.warnings().get(0).factors())
				.containsExactly(ReportCauseFactor.SLEEP, ReportCauseFactor.STRESS, ReportCauseFactor.WATER_INTAKE);
	}

	@Test
	void 함께_관찰된_요인이_없으면_에러_없이_빈_interactions를_반환한다() {
		doReturn(latestReportWithCauses(ReportCauseFactor.WATER_INTAKE)).when(reportService).getLatestSkinReport();

		ReportDto.InteractionsResponse response = reportService.getLatestCauseInteractions();

		assertThat(response.interactions()).isEmpty();
	}

	@Test
	void SLEEP과_STRESS가_함께_있으면_의료적_인과관계_표현_없이_상호작용_설명을_반환한다() {
		doReturn(latestReportWithCauses(ReportCauseFactor.SLEEP, ReportCauseFactor.STRESS))
				.when(reportService).getLatestSkinReport();

		ReportDto.InteractionsResponse response = reportService.getLatestCauseInteractions();

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
				.when(reportService).getLatestSkinReport();

		ReportDto.InteractionsResponse response = reportService.getLatestCauseInteractions();

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

		ReportDto.SkinSignalResponse response = reportService.getLatestSkinSignal();

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

		ReportDto.SkinSignalResponse response = reportService.getLatestSkinSignal();

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

		ReportDto.SkinSignalResponse response = reportService.getLatestSkinSignal();

		assertThat(response.redness().direction()).isEqualTo(ChangeDirection.STABLE);
		assertThat(response.redness().message()).isEqualTo("붉은기가 이전과 비슷한 상태예요.");
		assertThat(response.trouble().direction()).isEqualTo(ChangeDirection.STABLE);
		assertThat(response.trouble().message()).isEqualTo("트러블이 이전과 비슷한 상태예요.");
	}

	@Test
	void date_파라미터가_없으면_전체_리포트를_최신순으로_조회한다() {
		Report report = reportSummaryOf(101L, LocalDate.of(2026, 8, 10), SkinAnalysisLevel.CAUTION, "요약1");
		when(reportRepository.findAllByOrderByReportDateDescIdDesc()).thenReturn(List.of(report));

		ReportDto.ListResponse response = reportService.getReports(null);

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

		ReportDto.ListResponse response = reportService.getReports(date);

		assertThat(response.reports()).hasSize(1);
		assertThat(response.reports().get(0).reportId()).isEqualTo(55L);
		verify(reportRepository).findByReportDateOrderByIdDesc(date);
		verifyNoMoreInteractions(reportRepository);
	}

	@Test
	void 조회_결과가_없으면_빈_배열을_반환한다() {
		when(reportRepository.findAllByOrderByReportDateDescIdDesc()).thenReturn(List.of());

		ReportDto.ListResponse response = reportService.getReports(null);

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
	void 존재하지_않는_reportId면_REPORT_NOT_FOUND_예외를_던진다() {
		when(reportRepository.findById(999L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> reportService.getReport(999L))
				.isInstanceOf(GlobalException.class)
				.extracting("errorCode")
				.isEqualTo(ErrorCode.REPORT_NOT_FOUND);
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
		when(skinAnalysisRepository.findTop2ByOrderByAnalyzedAtDesc()).thenReturn(List.of(current, previous));
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
