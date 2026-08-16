package com.sangmyungyaho.barocare.skin.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sangmyungyaho.barocare.report.entity.ReportChangeStatus;
import com.sangmyungyaho.barocare.skin.entity.SkinAnalysis;
import com.sangmyungyaho.barocare.skin.entity.SkinAnalysisLevel;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

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

	/**
	 * 피부 분석 히스토리 조회(Issue #20) 응답.
	 *
	 * API 명세는 redness_score/trouble_score(숫자 점수) 기준이지만, 현재 SkinAnalysis는
	 * SAFE/CAUTION/DANGER 3단계 등급만 저장하고 숫자 점수 필드가 없다(SkinGradeRubric 참고).
	 * 임의로 점수를 만들어내지 않고, 실제 저장된 등급을 redness_level/trouble_level로 노출한다.
	 * average도 산술 평균이 아니라 조회 기간의 최빈 등급(동률이면 더 위험한 등급 우선)이다.
	 */
	/**
	 * 피부 분석 상세 조회(프론트 화면 연동) 응답. redness/trouble/skinLevel/analyzedAt은 기존 Response와
	 * 동일한 필드를 그대로 재사용하고, baseline 여부와 직전 분석 대비 변화만 추가한다.
	 * previous_skin_analysis_id/change_status는 직전 분석이 없으면(=baseline 그 자신) null이다.
	 */
	@Schema(name = "SkinAnalysisDetailResponse")
	public record DetailResponse(
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
			LocalDateTime analyzedAt,

			@Schema(description = "사용자의 최초(baseline) 분석인지 여부", example = "false")
			@JsonProperty("is_baseline")
			boolean isBaseline,

			@Schema(description = "비교에 사용된 직전 분석 ID. baseline(최초 분석)이라 비교 대상이 없으면 null.", example = "8", nullable = true)
			@JsonProperty("previous_skin_analysis_id")
			Long previousSkinAnalysisId,

			@Schema(description = "직전 분석 대비 붉은기 변화. 비교 대상이 없으면 null.", example = "IMPROVED", nullable = true)
			@JsonProperty("redness_change_status")
			ReportChangeStatus rednessChangeStatus,

			@Schema(description = "직전 분석 대비 트러블 변화. 비교 대상이 없으면 null.", example = "UNCHANGED", nullable = true)
			@JsonProperty("trouble_change_status")
			ReportChangeStatus troubleChangeStatus
	) {
	}

	@Schema(name = "SkinAnalysisHistoryResponse")
	public record HistoryResponse(
			@Schema(description = "조회 기간(일)", example = "28")
			@JsonProperty("period_days")
			Integer periodDays,

			@Schema(description = "조회 기간 내 가장 최근 분석 결과. 기록이 없으면 null.", nullable = true)
			LevelPoint latest,

			@Schema(
					description = "조회 기간 내 대표 등급. 산술 평균이 아니라 최빈 등급이며, "
							+ "동률이면 더 위험한 등급을 우선한다(DANGER > CAUTION > SAFE). 기록이 없으면 null.",
					nullable = true
			)
			LevelPoint average,

			@Schema(description = "조회 기간 내 분석 이력(날짜 오름차순)")
			List<HistoryItem> history,

			@Schema(
					description = "사용자의 최초(baseline) 피부 분석 결과. 조회 기간(period)과 무관하게 전체 이력 기준 "
							+ "첫 분석이며, 분석 기록이 아예 없으면 null.",
					nullable = true
			)
			LevelPoint baseline
	) {

		public static HistoryResponse empty(Integer periodDays, LevelPoint baseline) {
			return new HistoryResponse(periodDays, null, null, List.of(), baseline);
		}
	}

	@Schema(name = "SkinAnalysisLevelPoint")
	public record LevelPoint(
			@Schema(description = "붉은기 등급", example = "CAUTION")
			@JsonProperty("redness_level")
			SkinAnalysisLevel rednessLevel,

			@Schema(description = "트러블 등급", example = "SAFE")
			@JsonProperty("trouble_level")
			SkinAnalysisLevel troubleLevel
	) {

		public static LevelPoint of(SkinAnalysisLevel rednessLevel, SkinAnalysisLevel troubleLevel) {
			return new LevelPoint(rednessLevel, troubleLevel);
		}

		public static LevelPoint from(SkinAnalysis skinAnalysis) {
			return new LevelPoint(skinAnalysis.getRednessLevel(), skinAnalysis.getTroubleLevel());
		}
	}

	@Schema(name = "SkinAnalysisHistoryItem")
	public record HistoryItem(
			@Schema(description = "분석 날짜", example = "2026-08-10")
			LocalDate date,

			@Schema(description = "붉은기 등급", example = "CAUTION")
			@JsonProperty("redness_level")
			SkinAnalysisLevel rednessLevel,

			@Schema(description = "트러블 등급", example = "SAFE")
			@JsonProperty("trouble_level")
			SkinAnalysisLevel troubleLevel
	) {

		public static HistoryItem from(SkinAnalysis skinAnalysis) {
			return new HistoryItem(
					skinAnalysis.getAnalyzedAt().toLocalDate(),
					skinAnalysis.getRednessLevel(),
					skinAnalysis.getTroubleLevel()
			);
		}
	}
}
