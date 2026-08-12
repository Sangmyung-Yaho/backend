package com.sangmyungyaho.barocare.global.exception;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 공통 에러 응답 형식.
 * { "error": { "code": ..., "message": ... } }
 */
public record ErrorResponse(
		Error error
) {

	public static ErrorResponse of(ErrorCode errorCode) {
		return new ErrorResponse(new Error(errorCode.getCode(), errorCode.getMessage()));
	}

	// code/message에 example을 고정하지 않는다 — 모든 API의 에러 응답이 이 스키마 하나를 공유하기 때문에,
	// 여기에 example을 박으면 어떤 API/상태코드든 동일한 example만 노출된다.
	// 실제 API별 example은 각 Controller의 @ApiResponse(content = @Content(examples = @ExampleObject(...)))에서 지정한다.
	public record Error(
			@Schema(description = "에러 코드")
			String code,

			@Schema(description = "에러 메시지")
			String message
	) {
	}
}
