package com.sangmyungyaho.barocare.skin.service;

import com.sangmyungyaho.barocare.ai.client.AiClient;
import com.sangmyungyaho.barocare.ai.dto.AiDto;
import com.sangmyungyaho.barocare.global.exception.ErrorCode;
import com.sangmyungyaho.barocare.global.exception.GlobalException;
import com.sangmyungyaho.barocare.global.storage.ImageStorageService;
import com.sangmyungyaho.barocare.skin.dto.SkinComparisonDto;
import com.sangmyungyaho.barocare.skin.entity.SkinAnalysis;
import com.sangmyungyaho.barocare.skin.entity.SkinComparison;
import com.sangmyungyaho.barocare.skin.repository.SkinAnalysisRepository;
import com.sangmyungyaho.barocare.skin.repository.SkinComparisonRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 이전/현재 피부 사진 "변화" 비교(CHK-332). 단일 이미지 분석({@link SkinAnalysisService})과
 * {@link SkinGradeRubric}은 이 기능과 무관하며 변경하지 않는다.
 */
@Service
@RequiredArgsConstructor
public class SkinComparisonService {

	private static final Logger log = LoggerFactory.getLogger(SkinComparisonService.class);

	// SkinImageService/SkinAnalysisService와 동일한 저장 하위 경로.
	private static final String STORAGE_DIRECTORY = "skin-images";

	private static final Map<String, MimeType> EXTENSION_MIME_TYPES = Map.of(
			"jpg", MimeTypeUtils.IMAGE_JPEG,
			"jpeg", MimeTypeUtils.IMAGE_JPEG,
			"png", MimeTypeUtils.IMAGE_PNG
	);

	private final SkinAnalysisRepository skinAnalysisRepository;
	private final SkinComparisonRepository skinComparisonRepository;
	private final ImageStorageService imageStorageService;
	private final AiClient aiClient;

	/**
	 * @return 비교 결과와, 이번 호출로 새로 계산/저장했는지 여부(컨트롤러가 201/200 결정에 사용)
	 */
	public Outcome compareSkin(SkinComparisonDto.Request request) {
		SkinAnalysis current = skinAnalysisRepository.findById(request.currentSkinAnalysisId())
				.orElseThrow(() -> new GlobalException(ErrorCode.SKIN_ANALYSIS_NOT_FOUND));

		if (request.previousSkinAnalysisId() == null) {
			log.info("이전 분석 없음(최초 분석): currentSkinAnalysisId={} - 비교를 생략한다.", current.getId());
			return new Outcome(SkinComparisonDto.Response.withoutPrevious(current.getId()), false);
		}

		SkinAnalysis previous = skinAnalysisRepository.findById(request.previousSkinAnalysisId())
				.orElseThrow(() -> new GlobalException(ErrorCode.SKIN_ANALYSIS_NOT_FOUND));

		Optional<SkinComparison> existing = skinComparisonRepository
				.findByCurrentSkinAnalysis_IdAndPreviousSkinAnalysis_Id(current.getId(), previous.getId());
		if (existing.isPresent()) {
			log.info("기존 비교 결과 재사용: skinComparisonId={}, currentSkinAnalysisId={}, previousSkinAnalysisId={}",
					existing.get().getId(), current.getId(), previous.getId());
			return new Outcome(SkinComparisonDto.Response.from(existing.get()), false);
		}

		byte[] previousBytes = loadImageBytes(previous);
		byte[] currentBytes = loadImageBytes(current);
		MimeType previousMimeType = resolveMimeType(previous.getSkinImage().getStoredFileName());
		MimeType currentMimeType = resolveMimeType(current.getSkinImage().getStoredFileName());

		log.info("OpenAI 비교 요청 시작: currentSkinAnalysisId={}, previousSkinAnalysisId={}", current.getId(), previous.getId());
		AiDto.SkinComparisonResult result = aiClient.compareSkin(
				previousBytes, previousMimeType, currentBytes, currentMimeType
		);
		validateResult(result);

		log.info("비교 결과 계산 완료: currentSkinAnalysisId={}, previousSkinAnalysisId={}, redness={}, trouble={}",
				current.getId(), previous.getId(), result.redness(), result.trouble());

		SkinComparison saved = skinComparisonRepository.save(
				new SkinComparison(current, previous, result.redness(), result.trouble())
		);
		return new Outcome(SkinComparisonDto.Response.from(saved), true);
	}

	private void validateResult(AiDto.SkinComparisonResult result) {
		if (result == null || result.redness() == null || result.trouble() == null) {
			log.warn("AI 비교 결과 검증 실패: redness/trouble 누락 - {}", result);
			throw new GlobalException(ErrorCode.AI_ANALYSIS_FAILED);
		}
	}

	private byte[] loadImageBytes(SkinAnalysis skinAnalysis) {
		return imageStorageService.load(STORAGE_DIRECTORY, skinAnalysis.getSkinImage().getStoredFileName())
				.orElseThrow(() -> new GlobalException(ErrorCode.SKIN_IMAGE_FILE_NOT_FOUND));
	}

	private MimeType resolveMimeType(String storedFileName) {
		String extension = extractExtension(storedFileName).toLowerCase(Locale.ROOT);
		MimeType mimeType = EXTENSION_MIME_TYPES.get(extension);
		if (mimeType == null) {
			throw new GlobalException(ErrorCode.SKIN_IMAGE_FILE_NOT_FOUND);
		}
		return mimeType;
	}

	private String extractExtension(String fileName) {
		int dotIndex = fileName.lastIndexOf('.');
		return dotIndex >= 0 && dotIndex < fileName.length() - 1 ? fileName.substring(dotIndex + 1) : "";
	}

	public record Outcome(SkinComparisonDto.Response response, boolean created) {
	}
}
