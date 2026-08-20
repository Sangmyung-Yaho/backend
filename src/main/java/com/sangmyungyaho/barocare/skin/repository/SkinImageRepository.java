package com.sangmyungyaho.barocare.skin.repository;

import com.sangmyungyaho.barocare.skin.entity.SkinImage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface SkinImageRepository extends JpaRepository<SkinImage, Long> {
	void deleteAllByUserId(Long userId);

	// 회원 탈퇴 시 잔여 원본 이미지 파일을 지우기 위해, DB row를 지우기 전에 먼저 목록을 조회한다.
	List<SkinImage> findAllByUserId(Long userId);

	// 미분석 이미지 정리 배치(SkinImageCleanupService)용: cutoff 이전에 업로드됐는데
	// 아직 어떤 SkinAnalysis에도 연결되지 않은(=분석을 시도조차 안 했거나 실패한) 이미지를 찾는다.
	// FK 제약이 없는 프로젝트 컨벤션이라 NOT EXISTS 서브쿼리로 판단한다.
	@Query("SELECT si FROM SkinImage si WHERE si.createdAt < :cutoff "
			+ "AND NOT EXISTS (SELECT 1 FROM SkinAnalysis sa WHERE sa.skinImage = si)")
	List<SkinImage> findAllCreatedBeforeWithoutAnalysis(@Param("cutoff") Instant cutoff);
}
