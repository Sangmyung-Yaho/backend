package com.sangmyungyaho.barocare.report.repository;

import com.sangmyungyaho.barocare.report.entity.Report;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ReportRepository extends JpaRepository<Report, Long> {

	// 같은 currentSkinAnalysis 기준의 리포트가 이미 있으면 재사용하기 위한 조회.
	Optional<Report> findByCurrentSkinAnalysis_Id(Long currentSkinAnalysisId);

	// 리포트 보관함(ISSUE-29, GET /api/v1/reports) - 전체 리포트 최신순 조회.
	// reportDate가 같을 수 있으므로(같은 날짜에 여러 SkinAnalysis 쌍이 비교될 수 있음) id desc를 보조 정렬 기준으로 둔다.
	// TODO: User 연동 후 findAllByUserIdOrderByReportDateDescIdDesc(Long userId)로 변경
	List<Report> findAllByOrderByReportDateDescIdDesc();

	// 리포트 보관함(ISSUE-29) - 특정 날짜(date 쿼리 파라미터)의 리포트만 조회.
	// TODO: User 연동 후 findByUserIdAndReportDateOrderByIdDesc(Long userId, LocalDate reportDate)로 변경
	List<Report> findByReportDateOrderByIdDesc(LocalDate reportDate);
}
