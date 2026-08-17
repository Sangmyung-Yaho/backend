package com.sangmyungyaho.barocare.report.repository;

import com.sangmyungyaho.barocare.report.entity.Report;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ReportRepository extends JpaRepository<Report, Long> {

	// 같은 currentSkinAnalysis 기준의 리포트가 이미 있으면 재사용하기 위한 조회.
	Optional<Report> findByCurrentSkinAnalysis_Id(Long currentSkinAnalysisId);

	// 리포트 보관함(ISSUE-29) - 전체 리포트 최신순 조회. userId로 스코프되지 않으므로 분석 메인
	// 화면(사용자별 목록)에는 쓰지 않는다 - 대신 아래 findAllByCurrentSkinAnalysis_UserId... 계열을 사용한다.
	// reportDate가 같을 수 있으므로(같은 날짜에 여러 SkinAnalysis 쌍이 비교될 수 있음) id desc를 보조 정렬 기준으로 둔다.
	List<Report> findAllByOrderByReportDateDescIdDesc();

	// 리포트 보관함(ISSUE-29) - 특정 날짜(date 쿼리 파라미터)의 리포트만 조회. userId로 스코프되지 않는다(위와 동일한 이유).
	List<Report> findByReportDateOrderByIdDesc(LocalDate reportDate);

	// 분석 메인 화면(GET /api/v1/reports) - 로그인 사용자 범위로 제한된 목록 조회. Report에는 userId 컬럼이
	// 없으므로 currentSkinAnalysis를 통해 조인한다(findTopByCurrentSkinAnalysis_UserId...와 동일한 패턴).
	List<Report> findAllByCurrentSkinAnalysis_UserIdAndReportDateBetweenOrderByReportDateDescIdDesc(
			Long userId, LocalDate startDate, LocalDate endDate);

	// 분석 메인 화면 - 로그인 사용자 범위로 제한된 단일 날짜(date 쿼리 파라미터) 필터 조회.
	List<Report> findAllByCurrentSkinAnalysis_UserIdAndReportDateOrderByIdDesc(Long userId, LocalDate reportDate);

	// 홈 대시보드 통합 조회: 사용자의 가장 최근 "이미 저장된" 리포트 1건만 조회한다(find-or-create 아님,
	// OpenAI 호출 없음). currentSkinAnalysis를 통해 userId로 조인한다(Report에는 userId 컬럼이 없음).
	Optional<Report> findTopByCurrentSkinAnalysis_UserIdOrderByReportDateDescIdDesc(Long userId);
}
