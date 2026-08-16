package com.sangmyungyaho.barocare.skin.controller;

import com.sangmyungyaho.barocare.global.exception.ErrorResponse;
import com.sangmyungyaho.barocare.skin.dto.SkinAnalysisDto;
import com.sangmyungyaho.barocare.skin.service.SkinAnalysisService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Tag(name = "SkinAnalysis", description = "AI 피부 지표 분석 API")
public class SkinAnalysisController {

	private final SkinAnalysisService skinAnalysisService;

	@Operation(
			summary = "AI 피부 지표 분석",
			description = "이미 업로드된 SkinImage를 조회해 이미지를 읽고, OpenAI로 붉은기/트러블 지표를 분석해 저장한다."
	)
	@ApiResponses({
			@ApiResponse(
					responseCode = "201", description = "분석 성공",
					content = @Content(schema = @Schema(implementation = SkinAnalysisDto.Response.class))
			),
			@ApiResponse(
					responseCode = "400", description = "입력값이 올바르지 않거나, 이미지 품질/분석 신뢰도가 부족해 재촬영이 필요합니다.",
					content = @Content(
							schema = @Schema(implementation = ErrorResponse.class),
							examples = {
									@ExampleObject(
											name = "BAD_REQUEST",
											value = "{\"error\":{\"code\":\"BAD_REQUEST\",\"message\":\"입력값이 올바르지 않습니다.\"}}"
									),
									@ExampleObject(
											name = "SKIN_IMAGE_QUALITY_INSUFFICIENT",
											summary = "재촬영 필요 - AI API 호출 자체는 성공했지만 이미지 품질/분석 신뢰도가 부족한 경우(502 AI_ANALYSIS_FAILED와 구분됨)",
											value = "{\"error\":{\"code\":\"SKIN_IMAGE_QUALITY_INSUFFICIENT\",\"message\":\"이미지 품질 또는 분석 신뢰도가 부족합니다. 다시 촬영해주세요.\"}}"
									)
							}
					)
			),
			@ApiResponse(
					responseCode = "403", description = "다른 사용자가 업로드한 이미지입니다.",
					content = @Content(
							schema = @Schema(implementation = ErrorResponse.class),
							examples = @ExampleObject(
									name = "FORBIDDEN",
									value = "{\"error\":{\"code\":\"FORBIDDEN\",\"message\":\"해당 리소스에 접근할 권한이 없습니다.\"}}"
							)
					)
			),
			@ApiResponse(
					responseCode = "404", description = "존재하지 않는 피부 이미지입니다.",
					content = @Content(
							schema = @Schema(implementation = ErrorResponse.class),
							examples = @ExampleObject(
									name = "SKIN_IMAGE_NOT_FOUND",
									value = "{\"error\":{\"code\":\"SKIN_IMAGE_NOT_FOUND\",\"message\":\"존재하지 않는 피부 이미지입니다.\"}}"
							)
					)
			),
			@ApiResponse(
					responseCode = "502",
					description = "AI API 호출 자체가 실패했습니다(네트워크/OpenAI 오류, 응답 파싱 실패 등). "
							+ "400 SKIN_IMAGE_QUALITY_INSUFFICIENT(재촬영 필요)와는 원인이 다르므로 구분해서 처리해야 합니다.",
					content = @Content(
							schema = @Schema(implementation = ErrorResponse.class),
							examples = @ExampleObject(
									name = "AI_ANALYSIS_FAILED",
									value = "{\"error\":{\"code\":\"AI_ANALYSIS_FAILED\",\"message\":\"AI 분석에 실패했습니다. 잠시 후 다시 시도해주세요.\"}}"
							)
					)
			)
	})
	@PostMapping("/api/v1/skin-analyses")
	public org.springframework.http.ResponseEntity<com.sangmyungyaho.barocare.global.response.ApiResponse<SkinAnalysisDto.Response>> analyzeSkin(
			@org.springframework.security.core.annotation.AuthenticationPrincipal org.springframework.security.core.userdetails.UserDetails userDetails,
			@Valid @RequestBody SkinAnalysisDto.Request request
	) {
		Long userId = Long.parseLong(userDetails.getUsername());
		SkinAnalysisDto.Response response = skinAnalysisService.analyzeSkin(userId, request);
		return org.springframework.http.ResponseEntity.status(org.springframework.http.HttpStatus.CREATED).body(com.sangmyungyaho.barocare.global.response.ApiResponse.success("피부 분석이 완료되었습니다.", response));
	}

	@Operation(
			summary = "피부 분석 히스토리 조회",
			description = "조회 기간(기본 28일) 내 피부 분석 이력을 날짜 오름차순으로 반환한다. "
					+ "latest는 기간 내 가장 최근 분석 결과, average는 기간 내 등급의 최빈값이다 "
					+ "(산술 평균이 아니다 — 현재 SkinAnalysis는 SAFE/CAUTION/DANGER 등급만 저장하고 숫자 점수를 저장하지 않는다. "
					+ "동률이면 더 위험한 등급을 우선한다: DANGER > CAUTION > SAFE). "
					+ "baseline은 조회 기간(period)과 무관하게 사용자의 최초 분석 결과를 그대로 반환한다(개인화 원인 분석의 기준점). "
					+ "조회 기간 내 분석 기록이 없으면 latest/average는 null, history는 빈 배열로 반환한다(baseline은 전체 이력 기준이라 기간 밖 최초 분석이 있으면 계속 채워진다)."
	)
	@ApiResponses({
			@ApiResponse(
					responseCode = "200", description = "조회 성공",
					content = @Content(
							schema = @Schema(implementation = SkinAnalysisDto.HistoryResponse.class),
							examples = {
									@ExampleObject(
											name = "히스토리 있음",
											value = "{\"period_days\":28,"
													+ "\"latest\":{\"redness_level\":\"CAUTION\",\"trouble_level\":\"SAFE\"},"
													+ "\"average\":{\"redness_level\":\"CAUTION\",\"trouble_level\":\"SAFE\"},"
													+ "\"history\":["
													+ "{\"date\":\"2026-08-01\",\"redness_level\":\"DANGER\",\"trouble_level\":\"SAFE\"},"
													+ "{\"date\":\"2026-08-10\",\"redness_level\":\"CAUTION\",\"trouble_level\":\"SAFE\"}"
													+ "],"
													+ "\"baseline\":{\"redness_level\":\"DANGER\",\"trouble_level\":\"SAFE\"}}"
									),
									@ExampleObject(
											name = "히스토리 없음",
											value = "{\"period_days\":28,\"latest\":null,\"average\":null,\"history\":[],\"baseline\":null}"
									)
							}
					)
			)
	})
	@GetMapping("/api/v1/skin-analyses/history")
	public org.springframework.http.ResponseEntity<com.sangmyungyaho.barocare.global.response.ApiResponse<SkinAnalysisDto.HistoryResponse>> getHistory(
			@org.springframework.security.core.annotation.AuthenticationPrincipal org.springframework.security.core.userdetails.UserDetails userDetails,
			@Parameter(description = "조회 기간(일)", example = "28")
			@RequestParam(name = "period", required = false, defaultValue = "28") int period
	) {
		Long userId = Long.parseLong(userDetails.getUsername());
		return org.springframework.http.ResponseEntity.ok(com.sangmyungyaho.barocare.global.response.ApiResponse.success("피부 분석 히스토리를 조회했습니다.", skinAnalysisService.getHistory(userId, period)));
	}

	@Operation(
			summary = "피부 분석 상세 조회",
			description = "특정 피부 분석 결과를 상세 조회한다. 히스토리 조회(GET .../history)와 달리 단건 조회이며, "
					+ "baseline(최초 분석) 여부와 직전 분석 대비 붉은기/트러블 변화(IMPROVED/WORSENED/UNCHANGED)를 함께 반환한다. "
					+ "새로운 분석/저장이나 OpenAI 재호출은 발생하지 않는다."
	)
	@ApiResponses({
			@ApiResponse(
					responseCode = "200", description = "조회 성공",
					content = @Content(schema = @Schema(implementation = SkinAnalysisDto.DetailResponse.class))
			),
			@ApiResponse(
					responseCode = "403", description = "다른 사용자의 피부 분석입니다.",
					content = @Content(
							schema = @Schema(implementation = ErrorResponse.class),
							examples = @ExampleObject(
									name = "FORBIDDEN",
									value = "{\"error\":{\"code\":\"FORBIDDEN\",\"message\":\"해당 리소스에 접근할 권한이 없습니다.\"}}"
							)
					)
			),
			@ApiResponse(
					responseCode = "404", description = "존재하지 않는 피부 분석입니다.",
					content = @Content(
							schema = @Schema(implementation = ErrorResponse.class),
							examples = @ExampleObject(
									name = "SKIN_ANALYSIS_NOT_FOUND",
									value = "{\"error\":{\"code\":\"SKIN_ANALYSIS_NOT_FOUND\",\"message\":\"존재하지 않는 피부 분석입니다.\"}}"
							)
					)
			)
	})
	@GetMapping("/api/v1/skin-analyses/{skinAnalysisId}")
	public org.springframework.http.ResponseEntity<com.sangmyungyaho.barocare.global.response.ApiResponse<SkinAnalysisDto.DetailResponse>> getDetail(
			@org.springframework.security.core.annotation.AuthenticationPrincipal org.springframework.security.core.userdetails.UserDetails userDetails,
			@Parameter(description = "조회할 피부 분석 ID", example = "12")
			@PathVariable Long skinAnalysisId
	) {
		Long userId = Long.parseLong(userDetails.getUsername());
		SkinAnalysisDto.DetailResponse response = skinAnalysisService.getDetail(userId, skinAnalysisId);
		return org.springframework.http.ResponseEntity.ok(com.sangmyungyaho.barocare.global.response.ApiResponse.success("피부 분석 상세 정보를 조회했습니다.", response));
	}
}
