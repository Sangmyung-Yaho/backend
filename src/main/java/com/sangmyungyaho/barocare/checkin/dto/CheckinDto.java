package com.sangmyungyaho.barocare.checkin.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sangmyungyaho.barocare.checkin.entity.Checkin;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.LocalDate;

public class CheckinDto {

	@Schema(name = "CheckinRequest")
	public record Request(
			@Schema(description = "수면 시간(시간 단위, 소수 입력 가능)", example = "6")
			@NotNull(message = "수면 시간은 필수입니다.")
			@DecimalMin(value = "0.0", message = "수면 시간은 0 이상이어야 합니다.")
			@JsonProperty("sleep_hours")
			Double sleepHours,

			@Schema(description = "스트레스 지수(1~5)", example = "4")
			@NotNull(message = "스트레스 지수는 필수입니다.")
			@Min(value = 1, message = "스트레스 지수는 1 이상이어야 합니다.")
			@Max(value = 5, message = "스트레스 지수는 5 이하이어야 합니다.")
			@JsonProperty("stress_level")
			Integer stressLevel,

			@Schema(description = "수분 섭취량(ml)", example = "1500")
			@NotNull(message = "수분 섭취량은 필수입니다.")
			@PositiveOrZero(message = "수분 섭취량은 음수일 수 없습니다.")
			@JsonProperty("water_intake_ml")
			Integer waterIntakeMl,

			@Schema(description = "체크인 날짜", example = "2026-08-09")
			@NotNull(message = "체크인 날짜는 필수입니다.")
			@JsonProperty("checked_date")
			LocalDate checkedDate
	) {

		public Checkin toEntity(Long userId) {
			return new Checkin(userId, sleepHours, stressLevel, waterIntakeMl, checkedDate);
		}
	}

	@Schema(name = "CheckinResponse")
	public record Response(
			@Schema(description = "체크인 ID", example = "23")
			@JsonProperty("checkin_id")
			Long checkinId,

			@Schema(description = "수면 시간(시간 단위)", example = "6")
			@JsonProperty("sleep_hours")
			Double sleepHours,

			@Schema(description = "스트레스 지수(1~5)", example = "4")
			@JsonProperty("stress_level")
			Integer stressLevel,

			@Schema(description = "수분 섭취량(ml)", example = "1500")
			@JsonProperty("water_intake_ml")
			Integer waterIntakeMl,

			@Schema(description = "체크인 날짜", example = "2026-08-09")
			@JsonProperty("checked_date")
			LocalDate checkedDate
	) {

		public static Response from(Checkin checkin) {
			return new Response(
					checkin.getId(),
					checkin.getSleepHours(),
					checkin.getStressLevel(),
					checkin.getWaterIntakeMl(),
					checkin.getCheckedDate()
			);
		}
	}
}
