package com.sangmyungyaho.barocare.global.storage;

import org.springframework.web.multipart.MultipartFile;

import java.util.Optional;

/**
 * 이미지 파일 저장 책임을 분리한 추상화.
 * 현재는 로컬 디스크 구현체({@link LocalImageStorageService})만 존재하며,
 * 추후 S3 등 외부 스토리지로 교체 시 이 인터페이스의 새 구현체만 추가하면 된다.
 */
public interface ImageStorageService {

	/**
	 * 이미지 파일을 저장하고, 저장 결과(파일명/접근 URL)를 반환한다.
	 *
	 * @param file      저장할 이미지 파일
	 * @param directory 저장 하위 경로(예: "skin-images")
	 * @return 저장 결과
	 */
	StoredImage store(MultipartFile file, String directory);

	/**
	 * 저장된 이미지 파일의 바이트를 읽어온다.
	 * 파일이 존재하지 않으면 {@link Optional#empty()}를 반환한다(호출자가 도메인에 맞는 예외로 변환).
	 *
	 * @param directory      저장 하위 경로(예: "skin-images")
	 * @param storedFileName 저장소 내부 파일명
	 * @return 파일 바이트, 없으면 empty
	 */
	Optional<byte[]> load(String directory, String storedFileName);

	/**
	 * 저장 결과.
	 *
	 * @param storedFileName 저장소 내부에서 사용하는 실제 파일명(추후 삭제/S3 키 참조용)
	 * @param url            외부에서 접근 가능한 URL
	 */
	record StoredImage(String storedFileName, String url) {
	}
}
