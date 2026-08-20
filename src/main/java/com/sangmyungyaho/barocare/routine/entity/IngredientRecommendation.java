package com.sangmyungyaho.barocare.routine.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * 추천 성분 + 관련 제품(ISSUE-30, GET /api/v1/routines/today의 recommended_ingredients/recommended_products;
 * 상태 조회 전용 GET /api/v1/skin-analyses/{id}/ingredients, /products).
 *
 * 오늘 SkinAnalysis 기준으로 딱 1건만 생성된다(같은 skinAnalysisId로 재생성하지 않도록 유니크 제약).
 * ingredients/products 둘 다 API 응답과 동일한 구조(IngredientRecommendationDto.IngredientItem/ProductItem
 * 목록)를 JSON 문자열로 직렬화해 저장한다 - Report가 primaryCauses를 저장하는 것과 같은 방식이다.
 *
 * 성분 추천과 제품 검색은 이제 완전히 백그라운드에서 단계적으로 처리된다(POST /skin-analyses 응답을
 * 기다리지 않음) - 그래서 이 row는 두 작업이 다 끝나기를 기다리지 않고 PENDING/PENDING 상태로 먼저
 * 생성된 뒤, 각 단계가 끝날 때마다 갱신된다. ingredientStatus/productStatus로 지금 어느 단계인지
 * 추적하며, ingredientsJson/productsJson은 해당 단계가 COMPLETED이기 전까지는 null일 수 있다(그래서
 * nullable). 제품 검색은 추천 성분명을 입력으로 쓰는 실제 데이터 의존성이 있어, 성분 추천이 실패하면
 * (failIngredients) 제품도 함께 FAILED로 처리한다 - 완전한 병렬/독립 실행은 이 의존성 때문에 불가능하다.
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

	// COMPLETED 이전에는 null일 수 있다(PENDING/PROCESSING/FAILED 상태에서는 아직 생성된 내용이 없음).
	@Column(name = "ingredients_json", columnDefinition = "TEXT")
	private String ingredientsJson;

	@Column(name = "products_json", columnDefinition = "TEXT")
	private String productsJson;

	// 기존(이번 변경 전) 행은 전부 "끝까지 완료된 뒤에만" 저장됐으므로, schema.sql의 컬럼 기본값도
	// COMPLETED로 맞춰뒀다 - 이 자바 기본값은 새로 만드는 인스턴스(PENDING 생성자)에서 즉시 덮어써진다.
	@Enumerated(EnumType.STRING)
	@Column(name = "ingredient_status", nullable = false, length = 20,
			columnDefinition = "VARCHAR(20) NOT NULL DEFAULT 'COMPLETED'")
	private RecommendationStatus ingredientStatus;

	@Enumerated(EnumType.STRING)
	@Column(name = "product_status", nullable = false, length = 20,
			columnDefinition = "VARCHAR(20) NOT NULL DEFAULT 'COMPLETED'")
	private RecommendationStatus productStatus;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	/**
	 * PENDING/PENDING 상태로 먼저 만들어두는 생성자. POST /skin-analyses의 메인 응답 경로에서 동기로
	 * 호출된다(initializeTodayRecommendation) - 응답 직후 클라이언트가 바로 상태를 조회해도 404가 아니라
	 * PENDING을 받을 수 있게 하기 위함이다.
	 */
	public IngredientRecommendation(Long userId, Long skinAnalysisId, LocalDate recommendationDate) {
		this.userId = userId;
		this.skinAnalysisId = skinAnalysisId;
		this.recommendationDate = recommendationDate;
		this.ingredientStatus = RecommendationStatus.PENDING;
		this.productStatus = RecommendationStatus.PENDING;
	}

	public void markIngredientProcessing() {
		this.ingredientStatus = RecommendationStatus.PROCESSING;
	}

	public void completeIngredients(String ingredientsJson) {
		this.ingredientsJson = ingredientsJson;
		this.ingredientStatus = RecommendationStatus.COMPLETED;
	}

	// 성분 추천이 실패하면 제품 검색은 성분명을 입력으로 못 받아 아예 시도할 수 없으므로, 제품 상태도
	// 함께 FAILED로 확정한다(PROCESSING/PENDING에 무기한 머무르지 않도록).
	public void failIngredients() {
		this.ingredientStatus = RecommendationStatus.FAILED;
		this.productStatus = RecommendationStatus.FAILED;
	}

	public void markProductProcessing() {
		this.productStatus = RecommendationStatus.PROCESSING;
	}

	public void completeProducts(String productsJson) {
		this.productsJson = productsJson;
		this.productStatus = RecommendationStatus.COMPLETED;
	}

	public void failProducts() {
		this.productStatus = RecommendationStatus.FAILED;
	}
}
