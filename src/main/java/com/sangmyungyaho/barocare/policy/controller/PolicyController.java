package com.sangmyungyaho.barocare.policy.controller;

import com.sangmyungyaho.barocare.global.response.ApiResponse;
import com.sangmyungyaho.barocare.policy.dto.PolicyDto;
import com.sangmyungyaho.barocare.policy.service.PolicyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@Tag(name = "Policy", description = "약관 및 정책 콘텐츠 조회 API")
public class PolicyController {

	private final PolicyService policyService;

	@Operation(
			summary = "약관 및 정책 콘텐츠 조회",
			description = "이용약관(TERMS), 개인정보처리방침(PRIVACY) 콘텐츠를 조회한다. "
					+ "type 파라미터를 생략하면 전체 목록을, 지정하면 해당 종류만 반환한다. "
					+ "존재하지 않는 type이면 에러 없이 빈 배열을 반환한다."
	)
	@ApiResponses({
			@io.swagger.v3.oas.annotations.responses.ApiResponse(
					responseCode = "200",
					description = "조회 성공",
					content = @Content(
							array = @ArraySchema(schema = @Schema(implementation = PolicyDto.Response.class)),
							examples = @ExampleObject(
									name = "전체 조회",
									value = "{\"is_success\":true,\"message\":\"정책 콘텐츠를 조회했습니다.\","
											+ "\"data\":[{\"type\":\"TERMS\",\"title\":\"이용약관\",\"version\":\"1.0\",\"content\":\"...\"},"
											+ "{\"type\":\"PRIVACY\",\"title\":\"개인정보처리방침\",\"version\":\"1.0\",\"content\":\"...\"}]}"
							)
					)
			)
	})
	@GetMapping("/api/v1/policies")
	public ResponseEntity<ApiResponse<List<PolicyDto.Response>>> getPolicies(
			@Parameter(description = "조회할 정책 종류(TERMS/PRIVACY). 생략하면 전체 조회.", example = "TERMS")
			@RequestParam(required = false) String type
	) {
		List<PolicyDto.Response> response = policyService.getPolicies(type);
		return ResponseEntity.ok(ApiResponse.success("정책 콘텐츠를 조회했습니다.", response));
	}
}
