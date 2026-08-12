package com.sangmyungyaho.barocare.checkin.controller;

import com.sangmyungyaho.barocare.checkin.dto.CheckinDto;
import com.sangmyungyaho.barocare.checkin.service.CheckinService;
import com.sangmyungyaho.barocare.global.exception.ErrorResponse;
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
@Tag(name = "Checkin", description = "생활습관 체크인 API")
public class CheckinController {

	private final CheckinService checkinService;

	@Operation(summary = "체크인 저장", description = "수면 시간, 스트레스 지수, 수분 섭취량 등 하루의 생활습관 체크인을 저장한다.")
	@ApiResponses({
			@ApiResponse(
					responseCode = "201", description = "체크인 저장 성공",
					content = @Content(schema = @Schema(implementation = CheckinDto.Response.class))
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
					responseCode = "409", description = "해당 날짜의 체크인이 이미 존재합니다.",
					content = @Content(
							schema = @Schema(implementation = ErrorResponse.class),
							examples = @ExampleObject(
									name = "CHECKIN_ALREADY_EXISTS",
									value = "{\"error\":{\"code\":\"CHECKIN_ALREADY_EXISTS\",\"message\":\"해당 날짜의 체크인이 이미 존재합니다.\"}}"
							)
					)
			)
	})
	@PostMapping("/api/v1/checkins")
	public ResponseEntity<CheckinDto.Response> createCheckin(@Valid @RequestBody CheckinDto.Request request) {
		CheckinDto.Response response = checkinService.createCheckin(request);
		return ResponseEntity.status(HttpStatus.CREATED).body(response);
	}
}
