package com.sangmyungyaho.barocare.global.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

	BAD_REQUEST(HttpStatus.BAD_REQUEST, "입력값이 올바르지 않습니다."),
	USER_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 사용자입니다."),
	CHECKIN_ALREADY_EXISTS(HttpStatus.CONFLICT, "해당 날짜의 체크인이 이미 존재합니다."),
	INVALID_IMAGE(HttpStatus.BAD_REQUEST, "유효한 이미지 파일을 업로드해주세요."),
	SKIN_IMAGE_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 피부 이미지입니다."),
	SKIN_IMAGE_FILE_NOT_FOUND(HttpStatus.INTERNAL_SERVER_ERROR, "이미지 파일을 찾을 수 없습니다."),
	SKIN_ANALYSIS_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 피부 분석입니다."),
	AI_ANALYSIS_FAILED(HttpStatus.BAD_GATEWAY, "AI 분석에 실패했습니다. 잠시 후 다시 시도해주세요."),
	CHECKIN_NOT_FOUND(HttpStatus.NOT_FOUND, "원인 분석에 필요한 체크인 기록이 없습니다."),
	INSUFFICIENT_ANALYSIS_DATA(HttpStatus.UNPROCESSABLE_CONTENT, "피부 변화를 비교하기 위한 기록이 충분하지 않습니다."),
	REPORT_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 리포트입니다."),
	ROUTINE_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 루틴입니다."),
	FORBIDDEN(HttpStatus.FORBIDDEN, "해당 리소스에 접근할 권한이 없습니다."),
	UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다. (토큰 만료/유효하지 않음)"),
	OAUTH_PROVIDER_NOT_SUPPORTED(HttpStatus.BAD_REQUEST, "지원하지 않는 소셜 플랫폼입니다."),
	OAUTH_API_FAILED(HttpStatus.BAD_GATEWAY, "소셜 로그인 API 호출에 실패했습니다."),
	ONBOARDING_AGREEMENT_REQUIRED(HttpStatus.BAD_REQUEST, "온보딩을 완료하려면 이용약관 및 개인정보 수집·이용에 동의해야 합니다.");

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
