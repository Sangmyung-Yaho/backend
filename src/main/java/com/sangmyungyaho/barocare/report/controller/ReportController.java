package com.sangmyungyaho.barocare.report.controller;

import com.sangmyungyaho.barocare.global.exception.ErrorResponse;
import com.sangmyungyaho.barocare.global.response.ApiResponse;
import com.sangmyungyaho.barocare.report.dto.ReportDto;
import com.sangmyungyaho.barocare.report.service.ReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequiredArgsConstructor
@Tag(name = "Report", description = "피부 변화 원인 분석 리포트 API")
public class ReportController {

	private final ReportService reportService;

	@Operation(
			summary = "리포트 보관함 목록 조회(ISSUE-29)",
			description = "이미 생성되어 있는 Report만 조회한다(새로운 분석/저장 없음, OpenAI 재호출 없음). "
					+ "date 쿼리 파라미터가 있으면 해당 날짜의 리포트만, 없으면 전체 리포트를 최신순으로 반환한다. "
					+ "조회 결과가 없으면 에러 없이 빈 배열을 반환한다."
	)
	@ApiResponses({
			@io.swagger.v3.oas.annotations.responses.ApiResponse(
					responseCode = "200",
					description = "조회 성공. 응답 본문은 ApiResponse로 래핑되며, reports는 data 필드 안에 담긴다"
							+ "(결과가 없으면 data.reports가 빈 배열).",
					content = @Content(
							schema = @Schema(implementation = ReportDto.ListResponse.class),
							examples = {
									@ExampleObject(
											name = "목록 조회",
											value = "{\"is_success\":true,\"message\":\"리포트 목록을 조회했습니다.\","
													+ "\"data\":{\"reports\":[{\"report_id\":101,\"report_date\":\"2026-08-10\","
													+ "\"skin_level\":\"CAUTION\",\"summary\":\"최근 트러블 증가는 수면 부족과 "
													+ "높은 스트레스의 영향을 받았을 가능성이 있어요.\"}]}}"
									),
									@ExampleObject(
											name = "결과 없음",
											value = "{\"is_success\":true,\"message\":\"리포트 목록을 조회했습니다.\","
													+ "\"data\":{\"reports\":[]}}"
									)
							}
					)
			)
	})
	@GetMapping("/api/v1/reports")
	public ResponseEntity<ApiResponse<ReportDto.ListResponse>> getReports(
			@org.springframework.security.core.annotation.AuthenticationPrincipal org.springframework.security.core.userdetails.UserDetails userDetails,
			@Parameter(description = "조회할 리포트 기준일(YYYY-MM-DD). 생략하면 전체 리포트를 최신순으로 조회한다.", example = "2026-08-07")
			@RequestParam(required = false) LocalDate date
	) {
		Long userId = Long.parseLong(userDetails.getUsername());
		ReportDto.ListResponse response = reportService.getReports(userId, date);
		return ResponseEntity.ok(ApiResponse.success("리포트 목록을 조회했습니다.", response));
	}

	@Operation(
			summary = "리포트 상세 조회(ISSUE-29)",
			description = "reportId로 특정 리포트를 상세 조회한다. GET /api/v1/reports/skin/latest와 동일한 "
					+ "ReportDto.Response를 그대로 재사용하며, 새로운 분석/저장이나 OpenAI 재호출은 발생하지 않는다."
	)
	@ApiResponses({
			@io.swagger.v3.oas.annotations.responses.ApiResponse(
					responseCode = "200", description = "조회 성공",
					content = @Content(schema = @Schema(implementation = ReportDto.Response.class))
			),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(
					responseCode = "404", description = "존재하지 않는 리포트입니다.",
					content = @Content(
							schema = @Schema(implementation = ErrorResponse.class),
							examples = @ExampleObject(
									name = "REPORT_NOT_FOUND",
									value = "{\"error\":{\"code\":\"REPORT_NOT_FOUND\",\"message\":\"존재하지 않는 리포트입니다.\"}}"
							)
					)
			)
	})
	@GetMapping("/api/v1/reports/{reportId}")
	public ResponseEntity<ReportDto.Response> getReport(
			@Parameter(description = "조회할 리포트 ID", example = "101")
			@PathVariable Long reportId
	) {
		return ResponseEntity.ok(reportService.getReport(reportId));
	}

	@Operation(
			summary = "최신 피부 변화 원인 리포트 조회",
			description = "가장 최근 SkinAnalysis와 그 직전 SkinAnalysis를 비교하고, 체크인 데이터를 근거로 "
					+ "피부 변화의 주요 원인 후보를 분석해 반환한다. "
					+ "같은 최신 SkinAnalysis를 기준으로 이미 생성된 리포트가 있으면 재사용하고(OpenAI 재호출 없음), "
					+ "없으면 새로 분석해서 저장한 뒤 반환한다."
	)
	@ApiResponses({
			@io.swagger.v3.oas.annotations.responses.ApiResponse(
					responseCode = "200", description = "조회 성공(기존 리포트 재사용 또는 신규 생성 후 반환)",
					content = @Content(schema = @Schema(implementation = ReportDto.Response.class))
			),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(
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
			@io.swagger.v3.oas.annotations.responses.ApiResponse(
					responseCode = "422", description = "피부 변화를 비교하기 위한 기록이 충분하지 않습니다(SkinAnalysis 2건 미만).",
					content = @Content(
							schema = @Schema(implementation = ErrorResponse.class),
							examples = @ExampleObject(
									name = "INSUFFICIENT_ANALYSIS_DATA",
									value = "{\"error\":{\"code\":\"INSUFFICIENT_ANALYSIS_DATA\",\"message\":\"피부 변화를 비교하기 위한 기록이 충분하지 않습니다.\"}}"
							)
					)
			),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(
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
	public ResponseEntity<ReportDto.Response> getLatestSkinReport(
			@org.springframework.security.core.annotation.AuthenticationPrincipal org.springframework.security.core.userdetails.UserDetails userDetails
	) {
		Long userId = Long.parseLong(userDetails.getUsername());
		return ResponseEntity.ok(reportService.getLatestSkinReport(userId));
	}

	@Operation(
			summary = "기본 원인 리포트 조회(ISSUE-28)",
			description = "GET /api/v1/reports/skin/latest와 완전히 동일한 데이터를 반환하는 별칭(alias) 엔드포인트다. "
					+ "새로운 분석을 수행하거나 별도의 Report를 생성하지 않고, 기존 ReportService.getLatestSkinReport()를 "
					+ "그대로 호출한다(OpenAI 재호출 없음). 성공/실패 응답 형태는 GET /api/v1/reports/skin/latest와 동일하다."
	)
	@ApiResponses({
			@io.swagger.v3.oas.annotations.responses.ApiResponse(
					responseCode = "200", description = "조회 성공(기존 리포트 재사용 또는 신규 생성 후 반환)",
					content = @Content(schema = @Schema(implementation = ReportDto.Response.class))
			),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(
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
			@io.swagger.v3.oas.annotations.responses.ApiResponse(
					responseCode = "422", description = "피부 변화를 비교하기 위한 기록이 충분하지 않습니다(SkinAnalysis 2건 미만).",
					content = @Content(
							schema = @Schema(implementation = ErrorResponse.class),
							examples = @ExampleObject(
									name = "INSUFFICIENT_ANALYSIS_DATA",
									value = "{\"error\":{\"code\":\"INSUFFICIENT_ANALYSIS_DATA\",\"message\":\"피부 변화를 비교하기 위한 기록이 충분하지 않습니다.\"}}"
							)
					)
			),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(
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
	@GetMapping("/api/v1/reports/causes/latest")
	public ResponseEntity<ReportDto.Response> getLatestCauseReport(
			@org.springframework.security.core.annotation.AuthenticationPrincipal org.springframework.security.core.userdetails.UserDetails userDetails
	) {
		Long userId = Long.parseLong(userDetails.getUsername());
		return ResponseEntity.ok(reportService.getLatestSkinReport(userId));
	}

	@Operation(
			summary = "원인 요인 상호작용 설명 조회(ISSUE-28)",
			description = "최신 피부 변화 원인 리포트(GET /api/v1/reports/causes/latest)의 primaryCauses를 기준으로, "
					+ "2개 이상의 생활습관 요인이 함께 관찰된 경우 이를 설명하는 카드 목록을 반환한다. "
					+ "고위험 조합 경고(GET /api/v1/reports/causes/latest/warnings, #27)와 동일한 조합 매칭 규칙을 재사용하지만, "
					+ "\"~ 때문에 발생했다\"는 식의 의료적 인과관계 표현은 사용하지 않고 "
					+ "\"함께 관찰되었다\"/\"영향을 주었을 가능성이 있다\" 수준으로만 서술한다. "
					+ "체크인 원본 데이터를 다시 조회하지 않고 이미 계산된 원인 리포트를 그대로 재사용한다. "
					+ "함께 관찰된 조합이 없으면 에러 없이 빈 배열을 반환한다."
	)
	@ApiResponses({
			@io.swagger.v3.oas.annotations.responses.ApiResponse(
					responseCode = "200",
					description = "조회 성공. 응답 본문은 ApiResponse로 래핑되며, interactions는 data 필드 안에 담긴다"
							+ "(함께 관찰된 조합이 없으면 data.interactions가 빈 배열).",
					content = @Content(
							schema = @Schema(implementation = ReportDto.InteractionsResponse.class),
							examples = {
									@ExampleObject(
											name = "함께 관찰된 조합 있음",
											value = "{\"is_success\":true,\"message\":\"원인 요인 상호작용 설명을 조회했습니다.\","
													+ "\"data\":{\"interactions\":[{\"factors\":[\"SLEEP\",\"STRESS\"],"
													+ "\"message\":\"수면 부족과 높은 스트레스가 함께 관찰되었어요. "
													+ "두 요인이 피부 컨디션 변화와 함께 나타났을 가능성이 있어요.\"}]}}"
									),
									@ExampleObject(
											name = "함께 관찰된 조합 없음",
											value = "{\"is_success\":true,\"message\":\"원인 요인 상호작용 설명을 조회했습니다.\","
													+ "\"data\":{\"interactions\":[]}}"
									)
							}
					)
			),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(
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
			@io.swagger.v3.oas.annotations.responses.ApiResponse(
					responseCode = "422", description = "피부 변화를 비교하기 위한 기록이 충분하지 않습니다(SkinAnalysis 2건 미만).",
					content = @Content(
							schema = @Schema(implementation = ErrorResponse.class),
							examples = @ExampleObject(
									name = "INSUFFICIENT_ANALYSIS_DATA",
									value = "{\"error\":{\"code\":\"INSUFFICIENT_ANALYSIS_DATA\",\"message\":\"피부 변화를 비교하기 위한 기록이 충분하지 않습니다.\"}}"
							)
					)
			),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(
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
	@GetMapping("/api/v1/reports/causes/latest/interactions")
	public ResponseEntity<ApiResponse<ReportDto.InteractionsResponse>> getLatestCauseInteractions(
			@org.springframework.security.core.annotation.AuthenticationPrincipal org.springframework.security.core.userdetails.UserDetails userDetails
	) {
		Long userId = Long.parseLong(userDetails.getUsername());
		ReportDto.InteractionsResponse response = reportService.getLatestCauseInteractions(userId);
		return ResponseEntity.ok(ApiResponse.success("원인 요인 상호작용 설명을 조회했습니다.", response));
	}

	@Operation(
			summary = "피부 컨디션 신호 카드 조회(ISSUE-28)",
			description = "최신 피부 변화 원인 리포트가 참조하는 현재/이전 SkinAnalysis를 기준으로, 이전 대비 현재 붉은기/트러블의 "
					+ "변화 신호(증가/유지/감소)를 반환한다. 같은 (current, previous) SkinAnalysis 쌍의 SkinComparison이 이미 "
					+ "존재하면 그 값(AI 이미지 비교 판단)을 사용하고, 존재하지 않으면 이미 계산되어 있는 리포트의 등급 변화(status)를 "
					+ "대체 신호로 사용한다. 이 API를 위해 Vision/OpenAI 분석을 새로 실행하거나 SkinComparison을 새로 생성하지 않는다."
	)
	@ApiResponses({
			@io.swagger.v3.oas.annotations.responses.ApiResponse(
					responseCode = "200",
					description = "조회 성공. 응답 본문은 ApiResponse로 래핑되며, redness/trouble 신호는 data 필드 안에 담긴다.",
					content = @Content(
							schema = @Schema(implementation = ReportDto.SkinSignalResponse.class),
							examples = @ExampleObject(
									name = "신호 카드 예시",
									value = "{\"is_success\":true,\"message\":\"피부 컨디션 신호 카드를 조회했습니다.\","
											+ "\"data\":{\"redness\":{\"direction\":\"DECREASED\",\"message\":\"이전보다 붉은기가 감소했어요.\"},"
											+ "\"trouble\":{\"direction\":\"STABLE\",\"message\":\"트러블이 이전과 비슷한 상태예요.\"}}}"
							)
					)
			),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(
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
			@io.swagger.v3.oas.annotations.responses.ApiResponse(
					responseCode = "422", description = "피부 변화를 비교하기 위한 기록이 충분하지 않습니다(SkinAnalysis 2건 미만).",
					content = @Content(
							schema = @Schema(implementation = ErrorResponse.class),
							examples = @ExampleObject(
									name = "INSUFFICIENT_ANALYSIS_DATA",
									value = "{\"error\":{\"code\":\"INSUFFICIENT_ANALYSIS_DATA\",\"message\":\"피부 변화를 비교하기 위한 기록이 충분하지 않습니다.\"}}"
							)
					)
			),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(
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
	@GetMapping("/api/v1/reports/causes/latest/skin-signal")
	public ResponseEntity<ApiResponse<ReportDto.SkinSignalResponse>> getLatestSkinSignal(
			@org.springframework.security.core.annotation.AuthenticationPrincipal org.springframework.security.core.userdetails.UserDetails userDetails
	) {
		Long userId = Long.parseLong(userDetails.getUsername());
		ReportDto.SkinSignalResponse response = reportService.getLatestSkinSignal(userId);
		return ResponseEntity.ok(ApiResponse.success("피부 컨디션 신호 카드를 조회했습니다.", response));
	}

	@Operation(
			summary = "고위험 조합 경고 카드 조회",
			description = "최신 피부 변화 원인 리포트(GET /api/v1/reports/skin/latest)의 primaryCauses를 기준으로, "
					+ "미리 정의된 고위험 요인 조합(예: 수면 부족 + 높은 스트레스)이 동시에 확인되는지 판정해 경고 카드 목록을 반환한다. "
					+ "체크인 원본 데이터를 다시 조회하지 않고 이미 계산된 원인 리포트를 그대로 재사용한다. "
					+ "고위험 조합이 없으면 에러 없이 빈 배열을 반환한다."
	)
	@ApiResponses({
			@io.swagger.v3.oas.annotations.responses.ApiResponse(
					responseCode = "200",
					description = "조회 성공. 응답 본문은 ApiResponse로 래핑되며, warnings는 data 필드 안에 담긴다"
						+ "(고위험 조합이 없으면 data.warnings가 빈 배열).",
					content = @Content(
						schema = @Schema(implementation = ReportDto.WarningsResponse.class),
						examples = {
								@ExampleObject(
										name = "고위험 조합 있음",
										value = "{\"is_success\":true,\"message\":\"고위험 조합 경고를 조회했습니다.\","
												+ "\"data\":{\"warnings\":[{\"level\":\"HIGH\",\"factors\":[\"SLEEP\",\"STRESS\"],"
												+ "\"title\":\"고위험 조합 감지\",\"message\":\"수면 부족과 높은 스트레스가 함께 확인됐어요.\"}]}}"
								),
								@ExampleObject(
										name = "고위험 조합 없음",
										value = "{\"is_success\":true,\"message\":\"고위험 조합 경고를 조회했습니다.\","
												+ "\"data\":{\"warnings\":[]}}"
								)
						}
					)
			),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(
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
			@io.swagger.v3.oas.annotations.responses.ApiResponse(
					responseCode = "422", description = "피부 변화를 비교하기 위한 기록이 충분하지 않습니다(SkinAnalysis 2건 미만).",
					content = @Content(
							schema = @Schema(implementation = ErrorResponse.class),
							examples = @ExampleObject(
									name = "INSUFFICIENT_ANALYSIS_DATA",
									value = "{\"error\":{\"code\":\"INSUFFICIENT_ANALYSIS_DATA\",\"message\":\"피부 변화를 비교하기 위한 기록이 충분하지 않습니다.\"}}"
							)
					)
			),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(
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
	@GetMapping("/api/v1/reports/causes/latest/warnings")
	public ResponseEntity<ApiResponse<ReportDto.WarningsResponse>> getLatestCauseWarnings(
			@org.springframework.security.core.annotation.AuthenticationPrincipal org.springframework.security.core.userdetails.UserDetails userDetails
	) {
		Long userId = Long.parseLong(userDetails.getUsername());
		ReportDto.WarningsResponse response = reportService.getLatestCauseWarnings(userId);
		return ResponseEntity.ok(ApiResponse.success("고위험 조합 경고를 조회했습니다.", response));
	}
}
