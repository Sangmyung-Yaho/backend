package com.sangmyungyaho.barocare.skin.repository;

import com.sangmyungyaho.barocare.skin.entity.SkinAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface SkinAnalysisRepository extends JpaRepository<SkinAnalysis, Long> {

	// 히스토리 조회(CHK-... 주간 평균 비교)용. 오름차순이므로 마지막 원소가 곧 최신 분석이다.
	List<SkinAnalysis> findByAnalyzedAtGreaterThanEqualOrderByAnalyzedAtAsc(LocalDateTime from);
}
