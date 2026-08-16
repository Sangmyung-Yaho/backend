package com.sangmyungyaho.barocare.home.controller;

import com.sangmyungyaho.barocare.global.response.ApiResponse;
import com.sangmyungyaho.barocare.home.dto.HomeDto;
import com.sangmyungyaho.barocare.home.service.HomeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Tag(name = "Home", description = "홈 대시보드 통합 조회 API")
public class HomeController {

	private final HomeService homeService;

	@Operation(
			summary = "홈 대시보드 통합 조회",
			description = "홈 화면에 필요한 데이터(최근 7일 체크인 현황, 최신 피부 분석, 최신 원인 리포트 요약, "
					+ "오늘의 루틴 및 달성 현황)를 한 번에 조회한다. 이미 저장된 데이터만 조회하며, 새로운 피부 분석/"
					+ "원인 리포트 생성/루틴 생성이나 OpenAI 호출은 발생하지 않는다. "
					+ "신규 사용자 등 일부 데이터가 없어도 에러 없이 해당 영역만 null 또는 빈 값으로 반환한다."
	)
	@ApiResponses({
			@io.swagger.v3.oas.annotations.responses.ApiResponse(
					responseCode = "200",
					description = "조회 성공",
					content = @Content(
							schema = @Schema(implementation = HomeDto.DashboardResponse.class),
							examples = {
									@ExampleObject(
											name = "데이터가 있는 사용자",
											value = "{\"is_success\":true,\"message\":\"홈 대시보드를 조회했습니다.\",\"data\":{"
													+ "\"weekly_checkins\":{\"days\":["
													+ "{\"date\":\"2026-08-10\",\"checked\":true},"
													+ "{\"date\":\"2026-08-11\",\"checked\":false}],"
													+ "\"checked_count\":1},"
													+ "\"latest_skin_analysis\":{\"skin_analysis_id\":12,\"skin_image_id\":55,"
													+ "\"redness\":\"CAUTION\",\"trouble\":\"SAFE\",\"skin_level\":\"CAUTION\","
													+ "\"analyzed_at\":\"2026-08-12T17:30:00\",\"is_baseline\":false,"
													+ "\"previous_skin_analysis_id\":8,\"redness_change_status\":\"IMPROVED\","
													+ "\"trouble_change_status\":\"UNCHANGED\"},"
													+ "\"latest_report\":{\"report_id\":101,\"report_date\":\"2026-08-12\","
													+ "\"skin_change\":{\"redness\":{\"previous_score\":1,\"current_score\":0,"
													+ "\"change\":-1,\"status\":\"IMPROVED\"},\"trouble\":{\"previous_score\":0,"
													+ "\"current_score\":0,\"change\":0,\"status\":\"UNCHANGED\"}},"
													+ "\"primary_causes\":[],\"summary\":\"최근 수면 부족의 영향을 받았을 가능성이 있어요.\"},"
													+ "\"today_routine\":{\"is_checkin_completed\":true,\"is_generating\":false,"
													+ "\"total_count\":4,\"completed_count\":3,\"today_progress_percent\":75,\"routines\":[]}"
													+ "}}"
									),
									@ExampleObject(
											name = "데이터가 없는 신규 사용자",
											value = "{\"is_success\":true,\"message\":\"홈 대시보드를 조회했습니다.\",\"data\":{"
													+ "\"weekly_checkins\":{\"days\":["
													+ "{\"date\":\"2026-08-10\",\"checked\":false}],\"checked_count\":0},"
													+ "\"latest_skin_analysis\":null,"
													+ "\"latest_report\":null,"
													+ "\"today_routine\":{\"is_checkin_completed\":false,\"is_generating\":false,"
													+ "\"total_count\":0,\"completed_count\":0,\"today_progress_percent\":0,\"routines\":[]}"
													+ "}}"
									)
							}
					)
			)
	})
	@GetMapping("/api/v1/home")
	public ResponseEntity<ApiResponse<HomeDto.DashboardResponse>> getDashboard(
			@AuthenticationPrincipal UserDetails userDetails
	) {
		Long userId = Long.parseLong(userDetails.getUsername());
		HomeDto.DashboardResponse response = homeService.getDashboard(userId);
		return ResponseEntity.ok(ApiResponse.success("홈 대시보드를 조회했습니다.", response));
	}
}
