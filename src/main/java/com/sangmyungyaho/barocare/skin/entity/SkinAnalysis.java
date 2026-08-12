package com.sangmyungyaho.barocare.skin.entity;

import jakarta.persistence.Column;
import jakarta.persistence.ConstraintMode;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.List;

/**
 * AI 피부 지표 분석 결과 엔티티.
 * CHK-331 범위: 이미지 1장에 대한 단일 스냅샷 분석만 다룬다.
 *
 * GPT는 SAFE/CAUTION/DANGER를 직접 판정하지 않고, 관찰값(구역/강도/밀도)만 추출한다.
 * 최종 등급은 SkinGradeRubric이 계산하며, 그 근거가 된 관찰값을 그대로 저장해둔다.
 * (추후 rubric이 바뀌어도 OpenAI를 다시 호출하지 않고 저장된 관찰값으로 재계산할 수 있다.)
 *
 * TODO: CHK-332(주간 비교)에서는 이 엔티티 두 건(지난주/이번주 대표 분석)을 비교하는 방식으로 확장될 예정.
 * TODO: auth/user 도메인이 완성되면 분석을 요청한 사용자와의 연결이 필요할 수 있다
 *       (SkinImage 쪽에 userId가 붙으면 그 경로로 사용자를 추적 가능).
 */
@Entity
@Table(name = "skin_analysis")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SkinAnalysis {

	private static final String REGION_DELIMITER = ",";

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "skin_analysis_id")
	private Long id;

	// FK 제약 없이 참조만 유지(프로젝트 컨벤션).
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "skin_image_id", nullable = false, foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
	private SkinImage skinImage;

	@Enumerated(EnumType.STRING)
	@Column(name = "redness_level", nullable = false, length = 20)
	private SkinAnalysisLevel rednessLevel;

	// GPT 관찰값(재계산용 원본 데이터). 쉼표로 구분된 FaceRegion 이름 목록, 없으면 빈 문자열.
	@Column(name = "redness_affected_regions", nullable = false, length = 200)
	private String rednessAffectedRegions;

	@Enumerated(EnumType.STRING)
	@Column(name = "redness_max_intensity", length = 20)
	private RednessIntensity rednessMaxIntensity;

	@Enumerated(EnumType.STRING)
	@Column(name = "trouble_level", nullable = false, length = 20)
	private SkinAnalysisLevel troubleLevel;

	@Column(name = "trouble_affected_regions", nullable = false, length = 200)
	private String troubleAffectedRegions;

	@Enumerated(EnumType.STRING)
	@Column(name = "trouble_density", length = 20)
	private TroubleDensity troubleDensity;

	@Enumerated(EnumType.STRING)
	@Column(name = "skin_level", nullable = false, length = 20)
	private SkinAnalysisLevel skinLevel;

	// 분석 결과 신뢰성 판단용 내부 데이터. API 외부 응답에는 노출하지 않는다.
	@Enumerated(EnumType.STRING)
	@Column(name = "image_quality_lighting", nullable = false, length = 10)
	private ImageQualityRating imageQualityLighting;

	@Enumerated(EnumType.STRING)
	@Column(name = "image_quality_blur", nullable = false, length = 10)
	private ImageQualityRating imageQualityBlur;

	@Enumerated(EnumType.STRING)
	@Column(name = "image_quality_angle", nullable = false, length = 10)
	private ImageQualityRating imageQualityAngle;

	@Enumerated(EnumType.STRING)
	@Column(name = "image_quality_face_ratio", nullable = false, length = 10)
	private ImageQualityRating imageQualityFaceRatio;

	// 등급 계산 rubric 버전(SkinGradeRubric.RUBRIC_VERSION). rubric이 바뀌어도 과거 결과 해석 기준을 알 수 있게 남긴다.
	@Column(name = "rubric_version", nullable = false, length = 20)
	private String rubricVersion;

	@CreationTimestamp
	@Column(name = "analyzed_at", nullable = false, updatable = false)
	private LocalDateTime analyzedAt;

	public SkinAnalysis(
			SkinImage skinImage,
			SkinAnalysisLevel rednessLevel, List<FaceRegion> rednessAffectedRegions, RednessIntensity rednessMaxIntensity,
			SkinAnalysisLevel troubleLevel, List<FaceRegion> troubleAffectedRegions, TroubleDensity troubleDensity,
			SkinAnalysisLevel skinLevel,
			ImageQualityRating imageQualityLighting, ImageQualityRating imageQualityBlur,
			ImageQualityRating imageQualityAngle, ImageQualityRating imageQualityFaceRatio,
			String rubricVersion
	) {
		this.skinImage = skinImage;
		this.rednessLevel = rednessLevel;
		this.rednessAffectedRegions = joinRegions(rednessAffectedRegions);
		this.rednessMaxIntensity = rednessMaxIntensity;
		this.troubleLevel = troubleLevel;
		this.troubleAffectedRegions = joinRegions(troubleAffectedRegions);
		this.troubleDensity = troubleDensity;
		this.skinLevel = skinLevel;
		this.imageQualityLighting = imageQualityLighting;
		this.imageQualityBlur = imageQualityBlur;
		this.imageQualityAngle = imageQualityAngle;
		this.imageQualityFaceRatio = imageQualityFaceRatio;
		this.rubricVersion = rubricVersion;
	}

	private static String joinRegions(List<FaceRegion> regions) {
		if (regions == null || regions.isEmpty()) {
			return "";
		}
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < regions.size(); i++) {
			if (i > 0) {
				sb.append(REGION_DELIMITER);
			}
			sb.append(regions.get(i).name());
		}
		return sb.toString();
	}
}
