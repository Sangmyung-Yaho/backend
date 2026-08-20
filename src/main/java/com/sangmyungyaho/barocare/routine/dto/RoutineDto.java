package com.sangmyungyaho.barocare.routine.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sangmyungyaho.barocare.global.text.UserFacingTextGuard;
import com.sangmyungyaho.barocare.routine.entity.Routine;

import java.util.List;

public class RoutineDto {

	public record RoutineItem(
			@JsonProperty("routine_id") Long routineId,
			@JsonProperty("category") String category,
			@JsonProperty("title") String title,
			@JsonProperty("intensity") String intensity,
			@JsonProperty("is_completed") boolean isCompleted,
			@JsonProperty("estimated_minutes") Integer estimatedMinutes
	) {
		public static RoutineItem from(Routine routine) {
			return new RoutineItem(
					routine.getId(),
					routine.getCategory(),
					// title은 사용자에게 그대로 노출되는 자연어 문구다. 현재는 RoutineService의 하드코딩된
					// 한국어 템플릿에서만 채워지지만, 내부 상태값이 섞여 들어가는 회귀를 막기 위해
					// 방어적으로 sanitize를 거친다(category/intensity는 자연어 문장이 아니라 구조화된
					// 분류값이라 대상이 아니다).
					UserFacingTextGuard.sanitize(routine.getTitle()),
					routine.getIntensity(),
					routine.isCompleted(),
					routine.getEstimatedMinutes()
			);
		}
	}

	public record RoutineResponseDto(
			@JsonProperty("is_checkin_completed") boolean isCheckinCompleted,
			@JsonProperty("is_generating") boolean isGenerating,
			@JsonProperty("total_count") long totalCount,
			@JsonProperty("completed_count") long completedCount,
			@JsonProperty("today_progress_percent") int todayProgressPercent,
			@JsonProperty("routines") List<RoutineItem> routines,
			// ISSUE-30: 추천 성분/제품. 오늘 추천이 아직 생성 전이거나 AI/웹검색이 실패했으면 빈 배열이다
			// (에러 아님 - routines는 항상 정상 반환된다).
			@JsonProperty("recommended_ingredients") List<IngredientRecommendationDto.IngredientItem> recommendedIngredients,
			@JsonProperty("recommended_products") List<IngredientRecommendationDto.ProductItem> recommendedProducts
	) {
	}

	public record CheckRequest(
			@JsonProperty("is_completed") boolean isCompleted
	) {
	}

	public record CheckResponse(
			@JsonProperty("routine_id") Long routineId,
			@JsonProperty("is_completed") boolean isCompleted,
			@JsonProperty("completed_count") long completedCount,
			@JsonProperty("total_count") long totalCount,
			@JsonProperty("today_progress_percent") int todayProgressPercent
	) {
	}
}
