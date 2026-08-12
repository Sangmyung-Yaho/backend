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

	public record Error(
			@Schema(description = "에러 코드", example = "CHECKIN_ALREADY_EXISTS")
			String code,

			@Schema(description = "에러 메시지", example = "해당 날짜의 체크인이 이미 존재합니다.")
			String message
	) {
	}
}
