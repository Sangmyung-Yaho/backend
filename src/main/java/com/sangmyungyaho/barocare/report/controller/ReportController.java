package com.sangmyungyaho.barocare.report.controller;

import com.sangmyungyaho.barocare.global.exception.ErrorResponse;
import com.sangmyungyaho.barocare.report.dto.ReportDto;
import com.sangmyungyaho.barocare.report.service.ReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Tag(name = "Report", description = "피부 변화 원인 분석 리포트 API")
public class ReportController {

	private final ReportService reportService;

	@Operation(
			summary = "최신 피부 변화 원인 리포트 조회",
			description = "가장 최근 SkinAnalysis와 그 직전 SkinAnalysis를 비교하고, 체크인 데이터를 근거로 "
					+ "피부 변화의 주요 원인 후보를 분석해 반환한다. "
					+ "같은 최신 SkinAnalysis를 기준으로 이미 생성된 리포트가 있으면 재사용하고(OpenAI 재호출 없음), "
					+ "없으면 새로 분석해서 저장한 뒤 반환한다."
	)
	@ApiResponses({
			@ApiResponse(
					responseCode = "200", description = "조회 성공(기존 리포트 재사용 또는 신규 생성 후 반환)",
					content = @Content(schema = @Schema(implementation = ReportDto.Response.class))
			),
			@ApiResponse(
					responseCode = "404", description = "피부 분석 또는 체크인 기록이 없습니다.",
					content = @Content(
							schema = @Schema(implementation = ErrorResponse.class),
							examples = {
									@ExampleObject(
											name = "SKIN_ANALYSIS_NOT_FOUND",
											value = "{\"error\":{\"code\":\"SKIN_ANALYSIS_NOT_FOUND\",\"message\":\"존재하지 않는 피부 분석입니다.\"}}"
									),
									@ExampleObject(
											name = "CHECKIN_NOT_FOUND",
											value = "{\"error\":{\"code\":\"CHECKIN_NOT_FOUND\",\"message\":\"원인 분석에 필요한 체크인 기록이 없습니다.\"}}"
									)
							}
					)
			),
			@ApiResponse(
					responseCode = "422", description = "피부 변화를 비교하기 위한 기록이 충분하지 않습니다(SkinAnalysis 2건 미만).",
					content = @Content(
							schema = @Schema(implementation = ErrorResponse.class),
							examples = @ExampleObject(
									name = "INSUFFICIENT_ANALYSIS_DATA",
									value = "{\"error\":{\"code\":\"INSUFFICIENT_ANALYSIS_DATA\",\"message\":\"피부 변화를 비교하기 위한 기록이 충분하지 않습니다.\"}}"
							)
					)
			),
			@ApiResponse(
					responseCode = "502", description = "AI 분석에 실패했습니다. 잠시 후 다시 시도해주세요.",
					content = @Content(
							schema = @Schema(implementation = ErrorResponse.class),
							examples = @ExampleObject(
									name = "AI_ANALYSIS_FAILED",
									value = "{\"error\":{\"code\":\"AI_ANALYSIS_FAILED\",\"message\":\"AI 분석에 실패했습니다. 잠시 후 다시 시도해주세요.\"}}"
							)
					)
			)
	})
	@GetMapping("/api/v1/reports/skin/latest")
	public ResponseEntity<ReportDto.Response> getLatestSkinReport() {
		return ResponseEntity.ok(reportService.getLatestSkinReport());
	}
}
