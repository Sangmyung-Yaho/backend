package com.sangmyungyaho.barocare.skin.controller;

import com.sangmyungyaho.barocare.global.exception.ErrorResponse;
import com.sangmyungyaho.barocare.skin.dto.SkinAnalysisDto;
import com.sangmyungyaho.barocare.skin.service.SkinAnalysisService;
import io.swagger.v3.oas.annotations.Operation;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
					responseCode = "400", description = "입력값이 올바르지 않습니다.",
					content = @Content(
							schema = @Schema(implementation = ErrorResponse.class),
							examples = @ExampleObject(
									name = "BAD_REQUEST",
									value = "{\"error\":{\"code\":\"BAD_REQUEST\",\"message\":\"입력값이 올바르지 않습니다.\"}}"
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
	@PostMapping("/api/v1/skin-analyses")
	public ResponseEntity<SkinAnalysisDto.Response> analyzeSkin(@Valid @RequestBody SkinAnalysisDto.Request request) {
		SkinAnalysisDto.Response response = skinAnalysisService.analyzeSkin(request);
		return ResponseEntity.status(HttpStatus.CREATED).body(response);
	}
}
