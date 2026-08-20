package com.sangmyungyaho.barocare.skin.service;

import com.sangmyungyaho.barocare.global.storage.ImageStorageService;
import com.sangmyungyaho.barocare.skin.entity.SkinImage;
import com.sangmyungyaho.barocare.skin.repository.SkinImageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 분석 없이 방치된 이미지 정리 배치(SkinImageCleanupService) 단위 테스트.
 * 요구사항: 분석 실패/미시도로 쌓이는 임시 이미지가 계속 남지 않도록 파일+row를 함께 정리하되,
 * 파일 삭제가 실패해도 row 정리는 계속 진행돼야 한다.
 */
@ExtendWith(MockitoExtension.class)
class SkinImageCleanupServiceTest {

	@Mock
	private SkinImageRepository skinImageRepository;

	@Mock
	private ImageStorageService imageStorageService;

	@InjectMocks
	private SkinImageCleanupService skinImageCleanupService;

	@Test
	void 정리_대상_이미지가_없으면_아무것도_지우지_않는다() {
		when(skinImageRepository.findAllCreatedBeforeWithoutAnalysis(any())).thenReturn(List.of());

		skinImageCleanupService.cleanupOrphanedImages();

		verify(imageStorageService, never()).delete(any(), any());
		verify(skinImageRepository, never()).deleteAll(any());
	}

	@Test
	void 미분석_이미지의_파일과_row를_모두_지운다() {
		SkinImage orphan1 = new SkinImage(1L, "http://example.com/a.jpg", "a.jpg");
		SkinImage orphan2 = new SkinImage(2L, "http://example.com/b.jpg", "b.jpg");
		List<SkinImage> orphans = List.of(orphan1, orphan2);
		when(skinImageRepository.findAllCreatedBeforeWithoutAnalysis(any())).thenReturn(orphans);

		skinImageCleanupService.cleanupOrphanedImages();

		verify(imageStorageService).delete("skin-images", "a.jpg");
		verify(imageStorageService).delete("skin-images", "b.jpg");
		verify(skinImageRepository).deleteAll(orphans);
	}

	@Test
	void 파일_삭제가_실패해도_row_정리는_계속_진행된다() {
		SkinImage orphan = new SkinImage(1L, "http://example.com/a.jpg", "a.jpg");
		List<SkinImage> orphans = List.of(orphan);
		when(skinImageRepository.findAllCreatedBeforeWithoutAnalysis(any())).thenReturn(orphans);
		doThrow(new RuntimeException("디스크 IO 오류"))
				.when(imageStorageService).delete("skin-images", "a.jpg");

		skinImageCleanupService.cleanupOrphanedImages();

		verify(skinImageRepository).deleteAll(orphans);
	}
}
