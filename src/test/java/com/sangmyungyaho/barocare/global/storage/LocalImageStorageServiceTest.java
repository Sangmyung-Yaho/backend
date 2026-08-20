package com.sangmyungyaho.barocare.global.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 원본 이미지 보관 정책(분석 완료 후 원본 삭제) 도입에 따른 LocalImageStorageService.delete() 단위 테스트.
 * store()는 ServletUriComponentsBuilder.fromCurrentContextPath()가 실제 HTTP 요청 컨텍스트를
 * 필요로 해 순수 단위 테스트 범위에서는 제외한다(기존에도 별도 테스트가 없었다).
 */
class LocalImageStorageServiceTest {

	@Test
	void 존재하는_파일을_삭제하면_true를_반환하고_실제로_파일이_사라진다(@TempDir Path tempDir) throws IOException {
		LocalImageStorageService storage = new LocalImageStorageService(tempDir.toString(), "/uploads");
		Path dir = tempDir.resolve("skin-images");
		Files.createDirectories(dir);
		Path file = dir.resolve("a.jpg");
		Files.write(file, new byte[]{1, 2, 3});

		boolean deleted = storage.delete("skin-images", "a.jpg");

		assertThat(deleted).isTrue();
		assertThat(Files.exists(file)).isFalse();
	}

	@Test
	void 존재하지_않는_파일을_삭제하면_예외없이_false를_반환한다(@TempDir Path tempDir) {
		LocalImageStorageService storage = new LocalImageStorageService(tempDir.toString(), "/uploads");

		boolean deleted = storage.delete("skin-images", "not-exists.jpg");

		assertThat(deleted).isFalse();
	}
}
