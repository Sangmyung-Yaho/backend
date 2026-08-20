package com.sangmyungyaho.barocare.skin.controller;

import com.sangmyungyaho.barocare.global.exception.ErrorResponse;
import com.sangmyungyaho.barocare.skin.dto.SkinComparisonDto;
import com.sangmyungyaho.barocare.skin.service.SkinComparisonService;
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
@Tag(name = "SkinComparison", description = "이전/현재 피부 사진 변화 비교 API")
public class SkinComparisonController {

	private final SkinComparisonService skinComparisonService;

	@Operation(
			summary = "피부 사진 변화 비교",
			description = "현재/이전 SkinAnalysis에 이미 저장된 관찰값(redness/troubleLevel 및 세부 강도/밀도)을 "
					+ "비교해 redness/trouble의 상대적 변화 방향(INCREASED/STABLE/DECREASED)을 계산한다. "
					+ "원본 이미지 보관 정책(분석 완료 후 원본 삭제)에 따라 원본 이미지나 AI(GPT Vision) 호출에는 "
					+ "의존하지 않는다. SAFE/CAUTION/DANGER 등급을 다시 판정하지 않는다. "
					+ "previous_skin_analysis_id가 없으면(최초 분석) 비교 결과 없이 즉시 반환한다. "
					+ "이미 계산된 (current, previous) 조합이면 재계산하지 않고 기존 결과를 재사용한다.\n\n"
					+ "⚠️ current_skin_analysis_id / previous_skin_analysis_id는 POST /api/v1/skin-images 응답의 "
					+ "skin_image_id가 아니라, POST /api/v1/skin-analyses 응답의 skin_analysis_id다. "
					+ "이 둘을 혼동하면 404(SKIN_ANALYSIS_NOT_FOUND)가 발생한다."
	)
	@ApiResponses({
			@ApiResponse(
					responseCode = "201", description = "새로 계산해서 저장한 비교 결과",
					content = @Content(schema = @Schema(implementation = SkinComparisonDto.Response.class))
			),
			@ApiResponse(
					responseCode = "200", description = "기존 비교 결과 재사용, 또는 이전 분석이 없어 비교를 생략함",
					content = @Content(schema = @Schema(implementation = SkinComparisonDto.Response.class))
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
					responseCode = "404",
					description = "존재하지 않는 피부 분석입니다. "
							+ "(skin_image_id를 skin_analysis_id 자리에 넣은 경우 흔히 발생 — skin-analyses 응답의 ID를 사용해야 한다)",
					content = @Content(
							schema = @Schema(implementation = ErrorResponse.class),
							examples = @ExampleObject(
									name = "SKIN_ANALYSIS_NOT_FOUND",
									value = "{\"error\":{\"code\":\"SKIN_ANALYSIS_NOT_FOUND\",\"message\":\"존재하지 않는 피부 분석입니다.\"}}"
							)
					)
			)
	})
	@PostMapping("/api/v1/skin-comparisons")
	public ResponseEntity<SkinComparisonDto.Response> compareSkin(@Valid @RequestBody SkinComparisonDto.Request request) {
		SkinComparisonService.Outcome outcome = skinComparisonService.compareSkin(request);
		HttpStatus status = outcome.created() ? HttpStatus.CREATED : HttpStatus.OK;
		return ResponseEntity.status(status).body(outcome.response());
	}
}
