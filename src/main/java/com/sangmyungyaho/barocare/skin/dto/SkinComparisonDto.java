package com.sangmyungyaho.barocare.skin.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sangmyungyaho.barocare.skin.entity.ChangeDirection;
import com.sangmyungyaho.barocare.skin.entity.SkinComparison;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

public class SkinComparisonDto {

	@Schema(name = "SkinComparisonRequest")
	public record Request(
			@Schema(
					description = "현재 SkinAnalysis ID. "
							+ "주의: POST /api/v1/skin-images 응답의 skin_image_id가 아니라, "
							+ "POST /api/v1/skin-analyses 응답의 skin_analysis_id를 넣어야 한다.",
					example = "12"
			)
			@NotNull(message = "current_skin_analysis_id는 필수입니다.")
			@JsonProperty("current_skin_analysis_id")
			Long currentSkinAnalysisId,

			@Schema(
					description = "비교할 이전 SkinAnalysis ID(마찬가지로 skin_image_id가 아니라 skin_analysis_id). "
							+ "비교할 이전 분석이 없으면(최초 분석) 생략하거나 null로 보낸다.",
					example = "8", nullable = true
			)
			@JsonProperty("previous_skin_analysis_id")
			Long previousSkinAnalysisId
	) {
	}

	@Schema(name = "SkinComparisonResponse")
	public record Response(
			@Schema(description = "비교 결과 ID. 비교 대상(이전 분석)이 없으면 null.", example = "3", nullable = true)
			@JsonProperty("skin_comparison_id")
			Long skinComparisonId,

			@Schema(description = "현재 SkinAnalysis ID", example = "12")
			@JsonProperty("current_skin_analysis_id")
			Long currentSkinAnalysisId,

			@Schema(description = "비교에 사용된 이전 SkinAnalysis ID. 비교 대상이 없으면 null.", example = "8", nullable = true)
			@JsonProperty("previous_skin_analysis_id")
			Long previousSkinAnalysisId,

			@Schema(description = "붉은기 변화 방향. 비교 대상이 없으면 null.", example = "DECREASED", nullable = true)
			@JsonProperty("redness_change")
			ChangeDirection rednessChange,

			@Schema(description = "트러블 변화 방향. 비교 대상이 없으면 null.", example = "STABLE", nullable = true)
			@JsonProperty("trouble_change")
			ChangeDirection troubleChange,

			@Schema(description = "비교 일시. 비교 대상이 없으면 null.", example = "2026-08-13T10:00:00", nullable = true)
			@JsonProperty("compared_at")
			LocalDateTime comparedAt
	) {

		public static Response from(SkinComparison skinComparison) {
			return new Response(
					skinComparison.getId(),
					skinComparison.getCurrentSkinAnalysis().getId(),
					skinComparison.getPreviousSkinAnalysis().getId(),
					skinComparison.getRednessChange(),
					skinComparison.getTroubleChange(),
					skinComparison.getComparedAt()
			);
		}

		// 비교할 이전 분석이 없는 경우(최초 분석): GPT를 호출하지 않고 비교 결과 없음을 명확히 반환한다.
		public static Response withoutPrevious(Long currentSkinAnalysisId) {
			return new Response(null, currentSkinAnalysisId, null, null, null, null);
		}
	}
}
