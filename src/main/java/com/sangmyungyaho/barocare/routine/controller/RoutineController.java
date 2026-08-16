package com.sangmyungyaho.barocare.routine.controller;


import com.sangmyungyaho.barocare.routine.dto.RoutineDto;
import com.sangmyungyaho.barocare.routine.service.RoutineService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Tag(name = "Routine", description = "루틴 관리 API")
public class RoutineController {

	private final RoutineService routineService;

	@Operation(summary = "오늘의 루틴 목록 조회", description = "오늘 발급된 루틴 목록과 달성도를 조회합니다.")
	@GetMapping("/api/v1/routines/today")
	public org.springframework.http.ResponseEntity<com.sangmyungyaho.barocare.global.response.ApiResponse<RoutineDto.RoutineResponseDto>> getTodayRoutines(
			@AuthenticationPrincipal UserDetails userDetails
	) {
		Long userId = Long.parseLong(userDetails.getUsername());
		RoutineDto.RoutineResponseDto response = routineService.getTodayRoutines(userId);
		return org.springframework.http.ResponseEntity.ok(com.sangmyungyaho.barocare.global.response.ApiResponse.success("오늘의 루틴 목록을 조회했습니다.", response));
	}

	@Operation(summary = "루틴 상태 변경 (완료/미완료)", description = "특정 루틴 항목의 달성 상태를 명시적으로 변경하고, 갱신된 전체 진행률을 반환합니다.")
	@org.springframework.web.bind.annotation.PatchMapping("/api/v1/routines/{routineId}/check")
	public org.springframework.http.ResponseEntity<com.sangmyungyaho.barocare.global.response.ApiResponse<RoutineDto.CheckResponse>> checkRoutine(
			@AuthenticationPrincipal UserDetails userDetails,
			@org.springframework.web.bind.annotation.PathVariable Long routineId,
			@org.springframework.web.bind.annotation.RequestBody RoutineDto.CheckRequest request
	) {
		Long userId = Long.parseLong(userDetails.getUsername());
		RoutineDto.CheckResponse response = routineService.checkRoutine(userId, routineId, request);
		return org.springframework.http.ResponseEntity.ok(com.sangmyungyaho.barocare.global.response.ApiResponse.success("루틴 상태가 변경되었습니다.", response));
	}
}
