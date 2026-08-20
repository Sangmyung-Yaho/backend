package com.sangmyungyaho.barocare.routine.entity;

/**
 * 추천 성분/추천 제품 생성(백그라운드) 상태. {@link IngredientRecommendation}의 ingredientStatus,
 * productStatus 두 필드가 각각 이 값을 독립적으로 가진다 - 성분 추천과 제품 검색은 서로 다른 시점에
 * 완료/실패할 수 있으므로 상태도 따로 추적한다.
 *
 * PENDING: 아직 생성이 시작되지 않음(피부 분석은 끝났지만 백그라운드 작업이 아직 큐에서 시작 전).
 * PROCESSING: 지금 OpenAI 호출이 진행 중.
 * COMPLETED: 생성 완료, 데이터 조회 가능.
 * FAILED: 생성 실패(다시 시도하려면 새 피부 분석이 필요 - 이번 범위에서는 재시도 API를 따로 두지 않는다).
 */
public enum RecommendationStatus {
	PENDING,
	PROCESSING,
	COMPLETED,
	FAILED
}
