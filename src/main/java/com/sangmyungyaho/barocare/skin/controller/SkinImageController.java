package com.sangmyungyaho.barocare.skin.controller;

import com.sangmyungyaho.barocare.global.exception.ErrorResponse;
import com.sangmyungyaho.barocare.skin.dto.SkinImageDto;
import com.sangmyungyaho.barocare.skin.service.SkinImageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequiredArgsConstructor
@Tag(name = "SkinImage", description = "피부 분석용 얼굴 이미지 업로드 API")
public class SkinImageController {

	private final SkinImageService skinImageService;

	@Operation(
			summary = "피부 이미지 업로드",
			description = "피부 분석을 위한 얼굴 정면 이미지를 업로드한다. jpg/jpeg/png 형식만 허용한다."
	)
	@ApiResponses({
			@ApiResponse(
					responseCode = "201", description = "업로드 성공",
					content = @Content(schema = @Schema(implementation = SkinImageDto.Response.class))
			),
			@ApiResponse(
					responseCode = "400", description = "유효한 이미지 파일을 업로드해주세요.",
					content = @Content(
							schema = @Schema(implementation = ErrorResponse.class),
							examples = @ExampleObject(
									name = "INVALID_IMAGE",
									value = "{\"error\":{\"code\":\"INVALID_IMAGE\",\"message\":\"유효한 이미지 파일을 업로드해주세요.\"}}"
							)
					)
			)
	})
	@PostMapping(value = "/api/v1/skin-images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public ResponseEntity<SkinImageDto.Response> uploadSkinImage(
			@Parameter(description = "피부 분석을 위해 촬영한 얼굴 이미지 파일(jpg, jpeg, png)", required = true)
			@RequestParam("image") MultipartFile image
	) {
		SkinImageDto.Response response = skinImageService.uploadSkinImage(image);
		return ResponseEntity.status(HttpStatus.CREATED).body(response);
	}
}
