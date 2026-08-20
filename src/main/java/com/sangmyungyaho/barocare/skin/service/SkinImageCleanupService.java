package com.sangmyungyaho.barocare.skin.service;

import com.sangmyungyaho.barocare.global.storage.ImageStorageService;
import com.sangmyungyaho.barocare.skin.entity.SkinImage;
import com.sangmyungyaho.barocare.skin.repository.SkinImageRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 분석 없이 방치된("포기된") 업로드 이미지를 주기적으로 정리한다.
 *
 * SkinAnalysisService는 분석에 "성공"한 이미지만 삭제하므로(분석 실패 시 재촬영 없이 같은
 * SkinImage로 재시도할 수 있어야 해서 즉시 삭제하지 않는다), 아래 경우들은 원본 파일이 계속
 * 디스크에 남는다:
 *  - 업로드만 하고 분석(POST /skin-analyses)을 아예 시도하지 않은 경우
 *  - 이미지 품질 부족(SKIN_IMAGE_QUALITY_INSUFFICIENT)이나 AI 분석 실패(AI_ANALYSIS_FAILED)로
 *    분석이 끝까지 성공하지 못한 경우
 *
 * 재촬영/재시도 흐름을 방해하지 않도록 충분한 유예 기간(ORPHAN_RETENTION_HOURS)이 지난 뒤에만
 * 정리 대상으로 본다.
 */
@Service
@RequiredArgsConstructor
public class SkinImageCleanupService {

	private static final Logger log = LoggerFactory.getLogger(SkinImageCleanupService.class);

	private static final String STORAGE_DIRECTORY = "skin-images";

	// 업로드 후 이 시간이 지나도록 분석이 연결되지 않으면 "포기된 업로드"로 간주한다.
	private static final long ORPHAN_RETENTION_HOURS = 24;

	private final SkinImageRepository skinImageRepository;
	private final ImageStorageService imageStorageService;

	@Scheduled(cron = "${app.storage.skin-image-cleanup.cron:0 0 4 * * *}")
	@Transactional
	public void cleanupOrphanedImages() {
		Instant cutoff = Instant.now().minus(ORPHAN_RETENTION_HOURS, ChronoUnit.HOURS);
		List<SkinImage> orphans = skinImageRepository.findAllCreatedBeforeWithoutAnalysis(cutoff);

		if (orphans.isEmpty()) {
			log.info("정리할 미분석 이미지 없음: cutoff={}", cutoff);
			return;
		}

		int deletedFileCount = 0;
		for (SkinImage orphan : orphans) {
			try {
				if (imageStorageService.delete(STORAGE_DIRECTORY, orphan.getStoredFileName())) {
					deletedFileCount++;
				}
			} catch (RuntimeException e) {
				// 파일 삭제가 실패해도 이 이미지의 row는 아래에서 함께 지운다(재시도 대상 없음).
				// 파일이 남더라도 소유 정보(user_id)까지 함께 사라지지는 않으므로 로그로 추적 가능하게 남긴다.
				log.warn("미분석 이미지 파일 삭제 실패(row는 정리 계속 진행): skinImageId={}, storedFileName={}",
						orphan.getId(), orphan.getStoredFileName(), e);
			}
		}

		skinImageRepository.deleteAll(orphans);
		log.info("미분석 이미지 정리 완료: 대상={}, 파일삭제={}, cutoff={}", orphans.size(), deletedFileCount, cutoff);
	}
}
