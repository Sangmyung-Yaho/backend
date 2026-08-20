package com.sangmyungyaho.barocare.routine.repository;

import com.sangmyungyaho.barocare.routine.entity.IngredientRecommendation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface IngredientRecommendationRepository extends JpaRepository<IngredientRecommendation, Long> {

	// 같은 skinAnalysisId 기준 추천이 이미 있으면 재사용(AI/웹검색 재호출 방지)하기 위한 조회.
	Optional<IngredientRecommendation> findBySkinAnalysisId(Long skinAnalysisId);

	// GET /api/v1/routines/today - 오늘자 추천 조회(순수 읽기, AI/웹검색 호출 없음). 추천 생성이 이제
	// SkinAnalysis마다(피부 분석 완료 직후) 트리거되므로, 같은 날 재분석하면 skinAnalysisId가 다른
	// row가 여러 건 생길 수 있다(unique 제약은 skin_analysis_id 기준이지 recommendation_date 기준이
	// 아니다) - 그래서 단일 결과를 기대하는 findByUserIdAndRecommendationDate 대신 최신 1건만 집는다.
	Optional<IngredientRecommendation> findTopByUserIdAndRecommendationDateOrderByCreatedAtDesc(Long userId, LocalDate recommendationDate);
}
