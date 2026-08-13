package com.sangmyungyaho.barocare.report.repository;

import com.sangmyungyaho.barocare.report.entity.Report;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ReportRepository extends JpaRepository<Report, Long> {

	// 같은 currentSkinAnalysis 기준의 리포트가 이미 있으면 재사용하기 위한 조회.
	Optional<Report> findByCurrentSkinAnalysis_Id(Long currentSkinAnalysisId);
}
