package com.sangmyungyaho.barocare.skin.repository;

import com.sangmyungyaho.barocare.skin.entity.SkinAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SkinAnalysisRepository extends JpaRepository<SkinAnalysis, Long> {

	// 피부 변화 리포트(REP-101)에서 최신 분석과 그 직전 분석을 비교하기 위한 조회.
	// 2건 미만이면 비교 대상이 부족한 것으로 판단한다.
	List<SkinAnalysis> findTop2ByUserIdOrderByAnalyzedAtDesc(Long userId);

	List<SkinAnalysis> findAllByUserIdAndAnalyzedAtBetweenOrderByAnalyzedAtAsc(Long userId, LocalDateTime start, LocalDateTime end);

	// 개인화 피부 원인 분석: 사용자의 최초(baseline) 피부 분석을 명확하게 구분해서 조회한다.
	// 조회 기간(period)과 무관하게 사용자 전체 이력 기준 첫 분석이다.
	Optional<SkinAnalysis> findFirstByUserIdOrderByAnalyzedAtAsc(Long userId);

	// 피부 분석 상세 조회(프론트 화면 연동): 특정 분석 기준으로 그 직전(바로 이전) 분석을 조회한다.
	// findTop2ByUserIdOrderByAnalyzedAtDesc는 항상 "최신" 기준이라, 임의의(과거) 분석 기준으로는 쓸 수 없다.
	Optional<SkinAnalysis> findTopByUserIdAndAnalyzedAtLessThanOrderByAnalyzedAtDesc(Long userId, LocalDateTime analyzedAt);
}
