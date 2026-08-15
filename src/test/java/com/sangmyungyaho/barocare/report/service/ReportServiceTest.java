package com.sangmyungyaho.barocare.report.service;

import com.sangmyungyaho.barocare.ai.client.AiClient;
import com.sangmyungyaho.barocare.checkin.repository.CheckinRepository;
import com.sangmyungyaho.barocare.report.dto.ReportDto;
import com.sangmyungyaho.barocare.report.entity.ReportCauseFactor;
import com.sangmyungyaho.barocare.report.entity.WarningLevel;
import com.sangmyungyaho.barocare.report.repository.ReportRepository;
import com.sangmyungyaho.barocare.skin.repository.SkinAnalysisRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

/**
 * Issue #27: 고위험 조합 경고(getLatestCauseWarnings) 단위 테스트.
 *
 * 최신 원인 리포트 계산(getLatestSkinReport, SkinAnalysis/Checkin 조회·AI 호출 등)은 이미
 * REP-101에서 검증된 별도 로직이므로 여기서는 스텁으로 대체하고, primaryCauses가 주어졌을 때
 * getLatestCauseWarnings()가 CauseCombinationRubric에 위임해 올바른 WarningsResponse를
 * 만드는지만 검증한다. CauseCombinationRubric은 실제 구현체를 그대로 사용한다.
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

	private ReportService reportService;

	@BeforeEach
	void setUp() {
		reportService = spy(new ReportService(
				skinAnalysisRepository, checkinRepository, reportRepository, aiClient, objectMapper,
				new CauseCombinationRubric()
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

	private ReportDto.Response latestReportWithCauses(ReportCauseFactor... factors) {
		List<ReportDto.PrimaryCause> causes = List.of(factors).stream()
				.map(factor -> new ReportDto.PrimaryCause(factor, factor.name(), 1.0, "unit", "설명"))
				.toList();
		return new ReportDto.Response(1L, LocalDate.of(2026, 8, 10), null, causes, "summary");
	}
}
