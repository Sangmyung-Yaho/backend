package com.sangmyungyaho.barocare.routine.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 추천 성분 + 관련 제품(ISSUE-30, GET /api/v1/routines/today의 recommended_ingredients/recommended_products).
 *
 * 오늘 SkinAnalysis 기준으로 딱 1건만 생성된다(같은 skinAnalysisId로 재생성하지 않도록 유니크 제약).
 * ingredients/products 둘 다 API 응답과 동일한 구조(IngredientRecommendationDto.IngredientItem/ProductItem
 * 목록)를 JSON 문자열로 직렬화해 저장한다 - Report가 primaryCauses를 저장하는 것과 같은 방식이다.
 *
 * productsJson은 제품 웹 검색이 실패해도 항상 채워진다(검색 실패 시 빈 배열 "[]") - 성분 추천은 살리고
 * 제품만 비우기 위해서다(웹 검색 재시도는 이 행을 지우고 다시 생성해야 하는데, 오늘의 루틴/추천은
 * 하루 1회만 생성되는 흐름이라 이번 범위에서는 별도 재시도 로직을 두지 않는다).
 */
@Entity
@Table(
		name = "ingredient_recommendation",
		uniqueConstraints = @UniqueConstraint(name = "uk_ingredient_recommendation_skin_analysis", columnNames = "skin_analysis_id")
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IngredientRecommendation {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "ingredient_recommendation_id")
	private Long id;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	// FK 제약 없이 참조만 유지(프로젝트 컨벤션 - Report/Routine과 동일).
	@Column(name = "skin_analysis_id", nullable = false)
	private Long skinAnalysisId;

	// GET /api/v1/routines/today가 "오늘자" 추천만 조회할 때 쓰는 필터 기준(Routine.routineDate와 동일 목적).
	@Column(name = "recommendation_date", nullable = false)
	private LocalDate recommendationDate;

	@Column(name = "ingredients_json", nullable = false, columnDefinition = "TEXT")
	private String ingredientsJson;

	@Column(name = "products_json", nullable = false, columnDefinition = "TEXT")
	private String productsJson;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	public IngredientRecommendation(
			Long userId, Long skinAnalysisId, LocalDate recommendationDate, String ingredientsJson, String productsJson
	) {
		this.userId = userId;
		this.skinAnalysisId = skinAnalysisId;
		this.recommendationDate = recommendationDate;
		this.ingredientsJson = ingredientsJson;
		this.productsJson = productsJson;
	}
}
