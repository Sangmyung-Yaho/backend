package com.sangmyungyaho.barocare.home.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sangmyungyaho.barocare.report.dto.ReportDto;
import com.sangmyungyaho.barocare.routine.dto.RoutineDto;
import com.sangmyungyaho.barocare.skin.dto.SkinAnalysisDto;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;

public class HomeDto {

	/**
	 * 홈 대시보드 통합 조회 응답. 각 영역은 이미 저장된 데이터를 그대로 조합한 것이며, 새로운 분석/생성을
	 * 유발하지 않는다. latestSkinAnalysis/latestReport는 데이터가 없으면 null이고, weeklyCheckins/
	 * todayRoutine은 데이터가 없어도 빈 값(빈 리스트/0건)으로 채워진 객체를 반환한다(에러 아님).
	 */
	@Schema(name = "HomeDashboardResponse")
	public record DashboardResponse(
			@Schema(description = "최근 7일 체크인 현황")
			@JsonProperty("weekly_checkins")
			WeeklyCheckinSummary weeklyCheckins,

			@Schema(description = "가장 최근 피부 분석 결과. 분석 기록이 없으면 null.", nullable = true)
			@JsonProperty("latest_skin_analysis")
			SkinAnalysisDto.DetailResponse latestSkinAnalysis,

			@Schema(description = "가장 최근 원인 리포트 요약. 저장된 리포트가 없으면 null(이 요청으로 새로 생성하지 않음).", nullable = true)
			@JsonProperty("latest_report")
			ReportDto.Response latestReport,

			@Schema(description = "오늘의 루틴 목록 및 달성 현황")
			@JsonProperty("today_routine")
			RoutineDto.RoutineResponseDto todayRoutine
	) {
	}

	@Schema(name = "HomeWeeklyCheckinSummary")
	public record WeeklyCheckinSummary(
			@Schema(description = "최근 7일(오늘 포함) 날짜별 체크인 여부, 날짜 오름차순")
			List<WeeklyCheckinDay> days,

			@Schema(description = "최근 7일 중 체크인한 일수", example = "5")
			@JsonProperty("checked_count")
			int checkedCount
	) {
	}

	@Schema(name = "HomeWeeklyCheckinDay")
	public record WeeklyCheckinDay(
			@Schema(description = "날짜", example = "2026-08-16")
			LocalDate date,

			@Schema(description = "해당 날짜 체크인 여부", example = "true")
			boolean checked
	) {
	}
}
