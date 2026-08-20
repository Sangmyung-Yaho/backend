package com.sangmyungyaho.barocare.skin.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * 피부 분석용 얼굴 이미지 엔티티.
 *
 * 원본 이미지 보관 정책: 이 엔티티가 가리키는 원본 파일은 피부 분석(SkinAnalysis) 용도로만
 * 임시 저장된다. 분석이 성공적으로 끝나면 SkinAnalysisService가 원본 파일을 즉시 삭제하고,
 * 분석 없이 방치된 이미지는 SkinImageCleanupService가 주기적으로 정리한다. 즉 row 자체는
 * (분석 기록의 참조 대상으로) 계속 남지만, imageUrl/storedFileName이 가리키는 실제 파일은
 * 분석 이후 더 이상 존재하지 않을 수 있다 - 두 값을 다시 읽어 파일에 접근하려는 코드는 이
 * 사실을 감안해야 한다(ImageStorageService.load()는 파일이 없으면 Optional.empty()를 반환한다).
 */
@Entity
@Table(name = "skin_image")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SkinImage {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "skin_image_id")
	private Long id;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Column(name = "image_url", nullable = false, length = 1000)
	private String imageUrl;

	// 저장소(현재는 로컬 디스크) 내부 파일명. 추후 삭제/재발급 또는 S3 키 참조용.
	@Column(name = "stored_file_name", nullable = false)
	private String storedFileName;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	public SkinImage(Long userId, String imageUrl, String storedFileName) {
		this.userId = userId;
		this.imageUrl = imageUrl;
		this.storedFileName = storedFileName;
	}
}
