package com.sangmyungyaho.barocare.global.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

	BAD_REQUEST(HttpStatus.BAD_REQUEST, "입력값이 올바르지 않습니다."),
	CHECKIN_ALREADY_EXISTS(HttpStatus.CONFLICT, "해당 날짜의 체크인이 이미 존재합니다."),
	INVALID_IMAGE(HttpStatus.BAD_REQUEST, "유효한 이미지 파일을 업로드해주세요.");

	private final HttpStatus status;
	private final String message;

	ErrorCode(HttpStatus status, String message) {
		this.status = status;
		this.message = message;
	}

	public HttpStatus getStatus() {
		return status;
	}

	public String getMessage() {
		return message;
	}

	public String getCode() {
		return name();
	}
}
