package com.sangmyungyaho.barocare.routine.repository;

import com.sangmyungyaho.barocare.routine.entity.IngredientRecommendation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface IngredientRecommendationRepository extends JpaRepository<IngredientRecommendation, Long> {

	// 같은 skinAnalysisId 기준 추천이 이미 있으면 재사용(AI/웹검색 재호출 방지)하기 위한 조회.
	Optional<IngredientRecommendation> findBySkinAnalysisId(Long skinAnalysisId);

	// GET /api/v1/routines/today - 오늘자 추천 조회(순수 읽기, AI/웹검색 호출 없음).
	Optional<IngredientRecommendation> findByUserIdAndRecommendationDate(Long userId, LocalDate recommendationDate);
}
