package com.sangmyungyaho.barocare.global.response;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * test: ApiResponse 직렬화 결과에 is_success만 존재하고 success는 존재하지 않는지 검증한다.
 *
 * 배경: 필드명이 isSuccess였을 때, 필드 자체의 암묵적 프로퍼티명("isSuccess")과 Lombok이 생성한
 * 게터 isSuccess()의 암묵적 프로퍼티명("success")이 서로 달라 Jackson이 이 둘을 별개의 프로퍼티로
 * 인식했다. 그 결과 @JsonProperty("is_success")로 지정한 필드 쪽과는 별개로, 게터에서 자동 유도된
 * "success"가 함께 직렬화되어 is_success/success가 중복 노출됐다. 필드명을 success로 바꿔 게터
 * (isSuccess())와 필드의 암묵적 이름을 "success"로 일치시켜 Jackson이 하나의 프로퍼티로 병합하도록
 * 수정했고, 이 테스트는 실제 직렬화 결과로 그 수정을 검증한다.
 */
class ApiResponseTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void 직렬화_결과에는_is_success만_존재하고_success는_존재하지_않는다() {
		ApiResponse<String> response = ApiResponse.success("메시지", "데이터");

		String json = objectMapper.writeValueAsString(response);

		assertThat(json).contains("\"is_success\":true");
		assertThat(json).doesNotContain("\"success\"");
	}

	@Test
	void data가_null이어도_is_success만_존재하고_success는_존재하지_않는다() {
		ApiResponse<Void> response = ApiResponse.success("완료", null);

		String json = objectMapper.writeValueAsString(response);

		assertThat(json).contains("\"is_success\":true");
		assertThat(json).contains("\"message\":\"완료\"");
		assertThat(json).doesNotContain("\"success\"");
	}

	@Test
	void isSuccess_게터는_기존과_동일하게_동작한다() {
		ApiResponse<String> response = ApiResponse.success("메시지", "데이터");

		assertThat(response.isSuccess()).isTrue();
	}
}
