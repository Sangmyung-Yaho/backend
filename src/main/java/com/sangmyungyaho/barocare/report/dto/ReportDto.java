package com.sangmyungyaho.barocare.report.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sangmyungyaho.barocare.report.entity.Report;
import com.sangmyungyaho.barocare.report.entity.ReportCauseFactor;
import com.sangmyungyaho.barocare.report.entity.ReportChangeStatus;
import com.sangmyungyaho.barocare.report.entity.WarningLevel;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;

public class ReportDto {

	@Schema(name = "ReportSkinChangeItemResponse")
	public record SkinChangeItem(
			@Schema(description = "이전 SkinAnalysis 기준 점수(SAFE=0/CAUTION=1/DANGER=2). "
					+ "실제 측정 점수가 아니라 등급을 비교하기 위한 서수값이다.", example = "1")
			@JsonProperty("previous_score")
			Integer previousScore,

			@Schema(description = "현재 SkinAnalysis 기준 점수(SAFE=0/CAUTION=1/DANGER=2)", example = "0")
			@JsonProperty("current_score")
			Integer currentScore,

			@Schema(description = "점수 변화량(current_score - previous_score)", example = "-1")
			Integer change,

			@Schema(description = "변화 상태. previous_score/current_score의 change 값으로만 결정된다"
					+ "(change<0 → IMPROVED, change>0 → WORSENED, change==0 → UNCHANGED).", example = "IMPROVED")
			ReportChangeStatus status
	) {

		public static SkinChangeItem of(Integer previousScore, Integer currentScore, ReportChangeStatus status) {
			return new SkinChangeItem(previousScore, currentScore, currentScore - previousScore, status);
		}
	}

	@Schema(name = "ReportSkinChangeResponse")
	public record SkinChange(
			SkinChangeItem redness,
			SkinChangeItem trouble
	) {
	}

	@Schema(name = "ReportPrimaryCauseResponse")
	public record PrimaryCause(
			@Schema(description = "원인 요인. Checkin에 실제로 존재하는 필드로만 제한된다.", example = "SLEEP")
			ReportCauseFactor factor,

			@Schema(description = "원인 요인 이름(자연어)", example = "수면 부족")
			String name,

			@Schema(description = "해당 요인의 최근 체크인 실측값. AI가 아니라 Checkin 데이터로 채워진다.", example = "5.2")
			@JsonProperty("current_value")
			Double currentValue,

			@Schema(description = "current_value의 단위", example = "시간")
			String unit,

			@Schema(description = "원인 후보에 대한 자연어 설명", example = "최근 평균보다 수면 시간이 부족했어요.")
			String description
	) {
	}

	@Schema(name = "ReportSkinLatestResponse")
	public record Response(
			@Schema(description = "리포트 ID", example = "101")
			@JsonProperty("report_id")
			Long reportId,

			@Schema(description = "리포트 기준일(현재 SkinAnalysis의 분석일)", example = "2026-08-10")
			@JsonProperty("report_date")
			LocalDate reportDate,

			@Schema(description = "붉은기/트러블 지표 변화")
			@JsonProperty("skin_change")
			SkinChange skinChange,

			@Schema(description = "피부 변화의 주요 원인 후보 목록")
			@JsonProperty("primary_causes")
			List<PrimaryCause> primaryCauses,

			@Schema(description = "원인 분석 요약", example = "최근 트러블 증가는 수면 부족과 높은 스트레스의 영향을 받았을 가능성이 있어요.")
			String summary
	) {

		public static Response of(Report report, List<PrimaryCause> primaryCauses) {
			SkinChange skinChange = new SkinChange(
					SkinChangeItem.of(report.getRednessPreviousScore(), report.getRednessCurrentScore(), report.getRednessStatus()),
					SkinChangeItem.of(report.getTroublePreviousScore(), report.getTroubleCurrentScore(), report.getTroubleStatus())
			);
			return new Response(report.getId(), report.getReportDate(), skinChange, primaryCauses, report.getSummary());
		}
	}

	@Schema(name = "ReportCauseWarningResponse")
	public record Warning(
			@Schema(description = "경고 위험도", example = "HIGH")
			WarningLevel level,

			@Schema(description = "이 경고를 발생시킨 원인 요인 조합", example = "[\"SLEEP\", \"STRESS\"]")
			List<ReportCauseFactor> factors,

			@Schema(description = "경고 카드 제목", example = "고위험 조합 감지")
			String title,

			@Schema(description = "경고 카드 본문", example = "수면 부족과 높은 스트레스가 함께 확인됐어요.")
			String message
	) {
	}

	@Schema(name = "ReportCauseWarningsResponse")
	public record WarningsResponse(
			@Schema(description = "최신 원인 리포트 기준으로 감지된 고위험 조합 경고 목록. 없으면 빈 배열.")
			List<Warning> warnings
	) {
	}
}
