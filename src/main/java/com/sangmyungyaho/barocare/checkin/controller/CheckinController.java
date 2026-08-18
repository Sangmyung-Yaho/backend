package com.sangmyungyaho.barocare.checkin.controller;

import com.sangmyungyaho.barocare.checkin.dto.CheckinDto;
import com.sangmyungyaho.barocare.checkin.service.CheckinService;
import com.sangmyungyaho.barocare.global.exception.ErrorResponse;
import com.sangmyungyaho.barocare.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequiredArgsConstructor
@Tag(name = "Checkin", description = "생활습관 체크인 API")
public class CheckinController {

	private final CheckinService checkinService;

	@Operation(summary = "체크인 저장", description = "수면 시간, 스트레스 지수, 수분 섭취량 등 하루의 생활습관 체크인을 저장한다.")
	@ApiResponses({
			@io.swagger.v3.oas.annotations.responses.ApiResponse(
					responseCode = "201", description = "체크인 저장 성공",
					content = @Content(schema = @Schema(implementation = CheckinDto.Response.class))
			),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(
					responseCode = "400", description = "입력값이 올바르지 않습니다.",
					content = @Content(
							schema = @Schema(implementation = ErrorResponse.class),
							examples = @ExampleObject(
									name = "BAD_REQUEST",
									value = "{\"error\":{\"code\":\"BAD_REQUEST\",\"message\":\"입력값이 올바르지 않습니다.\"}}"
							)
					)
			),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(
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
	public ResponseEntity<ApiResponse<CheckinDto.Response>> createCheckin(
			@AuthenticationPrincipal UserDetails userDetails,
			@Valid @RequestBody CheckinDto.Request request
	) {
		Long userId = Long.parseLong(userDetails.getUsername());
		CheckinDto.Response response = checkinService.createCheckin(userId, request);
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("오늘의 체크인이 완료되었습니다.", response));
	}

	@Operation(summary = "오늘의 체크인 조회", description = "현재 로그인한 사용자의 오늘 체크인 데이터를 조회한다.")
	@ApiResponses({
			@io.swagger.v3.oas.annotations.responses.ApiResponse(
					responseCode = "200", description = "조회 성공",
					content = @Content(schema = @Schema(implementation = CheckinDto.Response.class))
			),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(
					responseCode = "404", description = "오늘의 체크인 기록이 없습니다.",
					content = @Content(
							schema = @Schema(implementation = ErrorResponse.class),
							examples = @ExampleObject(
									name = "TODAY_CHECKIN_NOT_FOUND",
									value = "{\"error\":{\"code\":\"TODAY_CHECKIN_NOT_FOUND\",\"message\":\"오늘의 체크인 기록이 없습니다.\"}}"
							)
					)
			)
	})
	@GetMapping("/api/v1/checkins/today")
	public ResponseEntity<ApiResponse<CheckinDto.Response>> getTodayCheckin(
			@AuthenticationPrincipal UserDetails userDetails
	) {
		Long userId = Long.parseLong(userDetails.getUsername());
		CheckinDto.Response response = checkinService.getTodayCheckin(userId);
		return ResponseEntity.ok(ApiResponse.success("오늘의 체크인을 조회했습니다.", response));
	}

	@Operation(
			summary = "기간별 체크인 조회",
			description = "startDate~endDate(포함) 범위의 체크인 기록을 날짜 오름차순으로 조회한다. "
					+ "해당 기간에 기록이 없으면 에러 없이 빈 배열을 반환한다."
	)
	@ApiResponses({
			@io.swagger.v3.oas.annotations.responses.ApiResponse(
					responseCode = "200", description = "조회 성공",
					content = @Content(array = @io.swagger.v3.oas.annotations.media.ArraySchema(schema = @Schema(implementation = CheckinDto.Response.class)))
			),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(
					responseCode = "400", description = "startDate/endDate가 누락되었거나 형식이 올바르지 않습니다.",
					content = @Content(
							schema = @Schema(implementation = ErrorResponse.class),
							examples = @ExampleObject(
									name = "BAD_REQUEST",
									value = "{\"error\":{\"code\":\"BAD_REQUEST\",\"message\":\"입력값이 올바르지 않습니다.\"}}"
							)
					)
			)
	})
	@GetMapping("/api/v1/checkins")
	public ResponseEntity<ApiResponse<List<CheckinDto.Response>>> getCheckinsByDateRange(
			@AuthenticationPrincipal UserDetails userDetails,
			@Parameter(description = "조회 시작일(포함)", example = "2026-08-10")
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
			@Parameter(description = "조회 종료일(포함)", example = "2026-08-16")
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate
	) {
		Long userId = Long.parseLong(userDetails.getUsername());
		List<CheckinDto.Response> response = checkinService.getCheckinsByDateRange(userId, startDate, endDate);
		return ResponseEntity.ok(ApiResponse.success("체크인 기록을 조회했습니다.", response));
	}
}
