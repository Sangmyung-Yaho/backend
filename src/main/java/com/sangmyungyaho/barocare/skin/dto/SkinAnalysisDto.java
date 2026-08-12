package com.sangmyungyaho.barocare.skin.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sangmyungyaho.barocare.skin.entity.SkinAnalysis;
import com.sangmyungyaho.barocare.skin.entity.SkinAnalysisLevel;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

public class SkinAnalysisDto {

	@Schema(name = "SkinAnalysisRequest")
	public record Request(
			@Schema(description = "분석할 SkinImage ID(사전에 /api/v1/skin-images로 업로드된 이미지)", example = "55")
			@NotNull(message = "skin_image_id는 필수입니다.")
			@JsonProperty("skin_image_id")
			Long skinImageId
	) {
	}

	@Schema(name = "SkinAnalysisResponse")
	public record Response(
			@Schema(description = "피부 분석 ID", example = "12")
			@JsonProperty("skin_analysis_id")
			Long skinAnalysisId,

			@Schema(description = "분석에 사용된 SkinImage ID", example = "55")
			@JsonProperty("skin_image_id")
			Long skinImageId,

			@Schema(description = "붉은기 등급", example = "CAUTION")
			SkinAnalysisLevel redness,

			@Schema(description = "트러블 등급", example = "SAFE")
			SkinAnalysisLevel trouble,

			@Schema(description = "최종 피부 등급(redness/trouble 중 더 높은 위험도)", example = "CAUTION")
			@JsonProperty("skin_level")
			SkinAnalysisLevel skinLevel,

			@Schema(description = "분석 일시", example = "2026-08-12T17:30:00")
			@JsonProperty("analyzed_at")
			LocalDateTime analyzedAt
	) {

		public static Response from(SkinAnalysis skinAnalysis) {
			return new Response(
					skinAnalysis.getId(),
					skinAnalysis.getSkinImage().getId(),
					skinAnalysis.getRednessLevel(),
					skinAnalysis.getTroubleLevel(),
					skinAnalysis.getSkinLevel(),
					skinAnalysis.getAnalyzedAt()
			);
		}
	}
}
