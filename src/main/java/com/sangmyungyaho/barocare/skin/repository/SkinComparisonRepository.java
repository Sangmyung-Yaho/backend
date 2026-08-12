package com.sangmyungyaho.barocare.skin.repository;

import com.sangmyungyaho.barocare.skin.entity.SkinComparison;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SkinComparisonRepository extends JpaRepository<SkinComparison, Long> {

	// 같은 (current, previous) 조합의 비교 결과가 이미 있으면 재사용하기 위한 조회.
	Optional<SkinComparison> findByCurrentSkinAnalysis_IdAndPreviousSkinAnalysis_Id(
			Long currentSkinAnalysisId, Long previousSkinAnalysisId
	);
}
