package com.sangmyungyaho.barocare.skin.service;

import com.sangmyungyaho.barocare.global.storage.ImageStorageService;
import com.sangmyungyaho.barocare.skin.dto.SkinImageDto;
import com.sangmyungyaho.barocare.skin.entity.SkinImage;
import com.sangmyungyaho.barocare.skin.repository.SkinImageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * fix: 기존 인증 및 사용자 데이터 처리 안정화.
 *
 * 업로드된 SkinImage에 업로드한 사용자(userId)가 정상적으로 연결되는지 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class SkinImageServiceTest {

	private static final Long USER_ID = 1L;

	@Mock
	private SkinImageRepository skinImageRepository;

	@Mock
	private ImageStorageService imageStorageService;

	@InjectMocks
	private SkinImageService skinImageService;

	@Test
	void 업로드하면_SkinImage에_요청한_사용자의_userId가_저장된다() {
		MockMultipartFile file = new MockMultipartFile("image", "face.jpg", "image/jpeg", new byte[]{1, 2, 3});
		when(imageStorageService.store(eq(file), eq("skin-images")))
				.thenReturn(new ImageStorageService.StoredImage("stored-face.jpg", "http://example.com/stored-face.jpg"));
		when(skinImageRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

		SkinImageDto.Response response = skinImageService.uploadSkinImage(USER_ID, file);

		ArgumentCaptor<SkinImage> captor = ArgumentCaptor.forClass(SkinImage.class);
		verify(skinImageRepository).save(captor.capture());
		assertThat(captor.getValue().getUserId()).isEqualTo(USER_ID);
		assertThat(response.imageUrl()).isEqualTo("http://example.com/stored-face.jpg");
	}
}
