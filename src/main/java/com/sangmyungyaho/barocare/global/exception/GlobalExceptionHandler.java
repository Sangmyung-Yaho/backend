package com.sangmyungyaho.barocare.global.exception;

import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/**
 * 전역 예외 처리기.
 * 도메인에서 발생한 {@link GlobalException}과 Bean Validation 실패를
 * 공통 에러 응답 형식({@link ErrorResponse})으로 변환한다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

	@ExceptionHandler(GlobalException.class)
	public ResponseEntity<ErrorResponse> handleGlobalException(GlobalException e) {
		ErrorCode errorCode = e.getErrorCode();
		return ResponseEntity.status(errorCode.getStatus())
				.body(ErrorResponse.of(errorCode));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ErrorResponse> handleValidationException(MethodArgumentNotValidException e) {
		return ResponseEntity.status(ErrorCode.BAD_REQUEST.getStatus())
				.body(ErrorResponse.of(ErrorCode.BAD_REQUEST));
	}

	// 필수 요청 파라미터가 아예 누락된 경우 (일반 @RequestParam)
	@ExceptionHandler(MissingServletRequestParameterException.class)
	public ResponseEntity<ErrorResponse> handleMissingParameterException(MissingServletRequestParameterException e) {
		return ResponseEntity.status(ErrorCode.BAD_REQUEST.getStatus())
				.body(ErrorResponse.of(ErrorCode.BAD_REQUEST));
	}

	// 요청 파라미터는 있으나 타입 변환에 실패한 경우 (예: startDate=2026-08-9 처럼 LocalDate로
	// 파싱할 수 없는 날짜 형식). 이 핸들러가 없으면 Spring 기본 에러 처리로 넘어가 원시 스택 트레이스가
	// 응답 바디에 그대로 노출된다.
	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ResponseEntity<ErrorResponse> handleMethodArgumentTypeMismatchException(MethodArgumentTypeMismatchException e) {
		return ResponseEntity.status(ErrorCode.BAD_REQUEST.getStatus())
				.body(ErrorResponse.of(ErrorCode.BAD_REQUEST));
	}

	// 필수 multipart 파트(image 파일 등)가 아예 누락된 경우
	@ExceptionHandler(MissingServletRequestPartException.class)
	public ResponseEntity<ErrorResponse> handleMissingPartException(MissingServletRequestPartException e) {
		return ResponseEntity.status(ErrorCode.INVALID_IMAGE.getStatus())
				.body(ErrorResponse.of(ErrorCode.INVALID_IMAGE));
	}

	// multipart/form-data가 아닌 요청 등 지원하지 않는 Content-Type으로 이미지 업로드를 시도한 경우
	@ExceptionHandler(HttpMediaTypeNotSupportedException.class)
	public ResponseEntity<ErrorResponse> handleUnsupportedMediaTypeException(HttpMediaTypeNotSupportedException e) {
		return ResponseEntity.status(ErrorCode.INVALID_IMAGE.getStatus())
				.body(ErrorResponse.of(ErrorCode.INVALID_IMAGE));
	}

	// 업로드 파일이 설정된 최대 크기(spring.servlet.multipart.max-file-size)를 초과한 경우
	@ExceptionHandler(MaxUploadSizeExceededException.class)
	public ResponseEntity<ErrorResponse> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException e) {
		return ResponseEntity.status(ErrorCode.FILE_TOO_LARGE.getStatus())
				.body(ErrorResponse.of(ErrorCode.FILE_TOO_LARGE));
	}
}
