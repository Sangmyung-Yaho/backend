package com.sangmyungyaho.barocare.global.storage;

import com.sangmyungyaho.barocare.global.exception.ErrorCode;
import com.sangmyungyaho.barocare.global.exception.GlobalException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

/**
 * 로컬 디스크에 이미지를 저장하는 MVP 구현체.
 * TODO: 추후 S3 등 외부 스토리지로 교체 시 {@link ImageStorageService}의 새 구현체로 대체한다.
 */
@Service
public class LocalImageStorageService implements ImageStorageService {

	private final Path baseDir;
	private final String urlPrefix;

	public LocalImageStorageService(
			@Value("${app.storage.local.base-dir:uploads}") String baseDir,
			@Value("${app.storage.local.url-prefix:/uploads}") String urlPrefix
	) {
		this.baseDir = Path.of(baseDir).toAbsolutePath().normalize();
		this.urlPrefix = urlPrefix;
	}

	@Override
	public StoredImage store(MultipartFile file, String directory) {
		try {
			Path targetDir = baseDir.resolve(directory);
			Files.createDirectories(targetDir);

			String extension = extractExtension(file.getOriginalFilename());
			String storedFileName = UUID.randomUUID() + extension;

			Path targetPath = targetDir.resolve(storedFileName);
			file.transferTo(targetPath);

			String url = ServletUriComponentsBuilder.fromCurrentContextPath()
					.path(urlPrefix + "/" + directory + "/" + storedFileName)
					.toUriString();

			return new StoredImage(storedFileName, url);
		} catch (IOException e) {
			throw new GlobalException(ErrorCode.INVALID_IMAGE);
		}
	}

	@Override
	public Optional<byte[]> load(String directory, String storedFileName) {
		Path targetPath = baseDir.resolve(directory).resolve(storedFileName);
		if (!Files.isRegularFile(targetPath)) {
			return Optional.empty();
		}
		try {
			return Optional.of(Files.readAllBytes(targetPath));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	@Override
	public boolean delete(String directory, String storedFileName) {
		Path targetPath = baseDir.resolve(directory).resolve(storedFileName);
		try {
			return Files.deleteIfExists(targetPath);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private String extractExtension(String originalFileName) {
		if (originalFileName == null) {
			return "";
		}
		int dotIndex = originalFileName.lastIndexOf('.');
		return dotIndex >= 0 ? originalFileName.substring(dotIndex) : "";
	}
}
