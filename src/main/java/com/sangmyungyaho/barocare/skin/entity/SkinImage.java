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
