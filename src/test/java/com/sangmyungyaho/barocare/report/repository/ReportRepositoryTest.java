package com.sangmyungyaho.barocare.report.repository;

import com.sangmyungyaho.barocare.report.entity.Report;
import com.sangmyungyaho.barocare.report.entity.ReportChangeStatus;
import com.sangmyungyaho.barocare.skin.entity.ImageQualityRating;
import com.sangmyungyaho.barocare.skin.entity.SkinAnalysis;
import com.sangmyungyaho.barocare.skin.entity.SkinAnalysisLevel;
import com.sangmyungyaho.barocare.skin.entity.SkinImage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ISSUE-29: 리포트 보관함(GET /api/v1/reports) 조회 쿼리 검증.
 * ReportService의 find-or-create 경로(AI 호출 등)와 무관하게, ReportRepository의 신규 조회
 * 메서드가 실제 JPA 매핑 위에서 올바른 순서/필터로 동작하는지만 검증한다.
 */
@DataJpaTest
class ReportRepositoryTest {

	@Autowired
	private TestEntityManager entityManager;

	@Autowired
	private ReportRepository reportRepository;

	@Test
	void 전체_리포트를_reportDate_내림차순으로_조회한다() {
		Report older = persistReport(LocalDate.of(2026, 8, 1));
		Report newer = persistReport(LocalDate.of(2026, 8, 10));
		entityManager.flush();

		List<Report> reports = reportRepository.findAllByOrderByReportDateDescIdDesc();

		assertThat(reports).extracting(Report::getId).containsExactly(newer.getId(), older.getId());
	}

	@Test
	void 같은_reportDate면_id_내림차순으로_정렬한다() {
		Report first = persistReport(LocalDate.of(2026, 8, 10));
		Report second = persistReport(LocalDate.of(2026, 8, 10));
		entityManager.flush();

		List<Report> reports = reportRepository.findAllByOrderByReportDateDescIdDesc();

		assertThat(reports).extracting(Report::getId).containsExactly(second.getId(), first.getId());
	}

	@Test
	void date로_필터링하면_해당_날짜의_리포트만_반환한다() {
		Report target = persistReport(LocalDate.of(2026, 8, 7));
		persistReport(LocalDate.of(2026, 8, 8));
		entityManager.flush();

		List<Report> reports = reportRepository.findByReportDateOrderByIdDesc(LocalDate.of(2026, 8, 7));

		assertThat(reports).extracting(Report::getId).containsExactly(target.getId());
	}

	@Test
	void 해당_날짜에_리포트가_없으면_빈_리스트를_반환한다() {
		persistReport(LocalDate.of(2026, 8, 7));
		entityManager.flush();

		List<Report> reports = reportRepository.findByReportDateOrderByIdDesc(LocalDate.of(2026, 1, 1));

		assertThat(reports).isEmpty();
	}

	@Test
	void 저장된_리포트가_없으면_전체_조회도_빈_리스트를_반환한다() {
		List<Report> reports = reportRepository.findAllByOrderByReportDateDescIdDesc();

		assertThat(reports).isEmpty();
	}

	private Report persistReport(LocalDate reportDate) {
		SkinImage skinImage = entityManager.persist(new SkinImage("https://example.com/a.jpg", "stored-a.jpg"));
		SkinAnalysis previous = entityManager.persist(skinAnalysisOf(skinImage, SkinAnalysisLevel.CAUTION));
		SkinAnalysis current = entityManager.persist(skinAnalysisOf(skinImage, SkinAnalysisLevel.SAFE));

		Report report = new Report(
				current, previous, reportDate,
				1, 0, ReportChangeStatus.IMPROVED,
				1, 0, ReportChangeStatus.IMPROVED,
				"[]", "요약"
		);
		return entityManager.persist(report);
	}

	private SkinAnalysis skinAnalysisOf(SkinImage skinImage, SkinAnalysisLevel skinLevel) {
		return new SkinAnalysis(
				skinImage,
				skinLevel, List.of(), null,
				skinLevel, List.of(), null,
				skinLevel,
				ImageQualityRating.GOOD, ImageQualityRating.GOOD, ImageQualityRating.GOOD, ImageQualityRating.GOOD,
				"v-test"
		);
	}
}
