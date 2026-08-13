package com.sangmyungyaho.barocare.skin.repository;

import com.sangmyungyaho.barocare.skin.entity.SkinAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SkinAnalysisRepository extends JpaRepository<SkinAnalysis, Long> {

	// 피부 변화 리포트(REP-101)에서 최신 분석과 그 직전 분석을 비교하기 위한 조회.
	// 2건 미만이면 비교 대상이 부족한 것으로 판단한다.
	List<SkinAnalysis> findTop2ByOrderByAnalyzedAtDesc();
}
