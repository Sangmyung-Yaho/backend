package com.sangmyungyaho.barocare.skin.service;

import com.sangmyungyaho.barocare.global.exception.ErrorCode;
import com.sangmyungyaho.barocare.global.exception.GlobalException;
import com.sangmyungyaho.barocare.global.storage.ImageStorageService;
import com.sangmyungyaho.barocare.global.storage.ImageStorageService.StoredImage;
import com.sangmyungyaho.barocare.skin.dto.SkinImageDto;
import com.sangmyungyaho.barocare.skin.entity.SkinImage;
import com.sangmyungyaho.barocare.skin.repository.SkinImageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class SkinImageService {

	private static final String STORAGE_DIRECTORY = "skin-images";

	private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of("image/jpeg", "image/png");
	private static final List<String> ALLOWED_EXTENSIONS = List.of("jpg", "jpeg", "png");

	private final SkinImageRepository skinImageRepository;
	private final ImageStorageService imageStorageService;

	public SkinImageDto.Response uploadSkinImage(MultipartFile image) {
		validateImage(image);

		// TODO: User 연동 후에는 업로드한 사용자(userId)와 SkinImage를 연결해야 한다.
		StoredImage storedImage = imageStorageService.store(image, STORAGE_DIRECTORY);
		SkinImage skinImage = new SkinImage(storedImage.url(), storedImage.storedFileName());

		return SkinImageDto.Response.from(skinImageRepository.save(skinImage));
	}

	private void validateImage(MultipartFile image) {
		if (image == null || image.isEmpty()) {
			throw new GlobalException(ErrorCode.INVALID_IMAGE);
		}

		String contentType = image.getContentType();
		if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType.toLowerCase(Locale.ROOT))) {
			throw new GlobalException(ErrorCode.INVALID_IMAGE);
		}

		String extension = extractExtension(image.getOriginalFilename());
		if (!ALLOWED_EXTENSIONS.contains(extension.toLowerCase(Locale.ROOT))) {
			throw new GlobalException(ErrorCode.INVALID_IMAGE);
		}
	}

	private String extractExtension(String originalFileName) {
		if (originalFileName == null) {
			return "";
		}
		int dotIndex = originalFileName.lastIndexOf('.');
		return dotIndex >= 0 && dotIndex < originalFileName.length() - 1
				? originalFileName.substring(dotIndex + 1)
				: "";
	}
}
