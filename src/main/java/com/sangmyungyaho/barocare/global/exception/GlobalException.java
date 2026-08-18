package com.sangmyungyaho.barocare.global.exception;

import lombok.Getter;

/**
 * 도메인에서 공통으로 사용하는 예외.
 * {@link ErrorCode}를 담아 던지면 {@link GlobalExceptionHandler}가
 * 공통 에러 응답 형식으로 변환한다.
 */
@Getter
public class GlobalException extends RuntimeException {

	private final ErrorCode errorCode;

	public GlobalException(ErrorCode errorCode) {
		super(errorCode.getMessage());
		this.errorCode = errorCode;
	}
}
