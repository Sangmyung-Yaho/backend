package com.sangmyungyaho.barocare.routine.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sangmyungyaho.barocare.routine.entity.Routine;

import java.util.List;

public class RoutineDto {

	public record RoutineItem(
			@JsonProperty("routine_id") Long routineId,
			@JsonProperty("category") String category,
			@JsonProperty("title") String title,
			@JsonProperty("intensity") String intensity,
			@JsonProperty("is_completed") boolean isCompleted
	) {
		public static RoutineItem from(Routine routine) {
			return new RoutineItem(
					routine.getId(),
					routine.getCategory(),
					routine.getTitle(),
					routine.getIntensity(),
					routine.isCompleted()
			);
		}
	}

	public record RoutineResponseDto(
			@JsonProperty("is_checkin_completed") boolean isCheckinCompleted,
			@JsonProperty("is_generating") boolean isGenerating,
			@JsonProperty("total_count") long totalCount,
			@JsonProperty("completed_count") long completedCount,
			@JsonProperty("today_progress_percent") int todayProgressPercent,
			@JsonProperty("routines") List<RoutineItem> routines
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
