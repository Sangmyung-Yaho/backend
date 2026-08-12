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
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 이전/현재 SkinAnalysis에 연결된 원본 이미지 두 장을 GPT Vision에 함께 전달해 얻은
 * redness/trouble 변화 방향 결과. 최종 등급(SAFE/CAUTION/DANGER)은 다루지 않는다.
 *
 * 같은 (current, previous) 조합은 재계산하지 않고 재사용하므로 유니크 제약을 둔다.
 */
@Entity
@Table(
		name = "skin_comparison",
		uniqueConstraints = @UniqueConstraint(
				name = "uk_skin_comparison_current_previous",
				columnNames = {"current_skin_analysis_id", "previous_skin_analysis_id"}
		)
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SkinComparison {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "skin_comparison_id")
	private Long id;

	// FK 제약 없이 참조만 유지(프로젝트 컨벤션).
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "current_skin_analysis_id", nullable = false, foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
	private SkinAnalysis currentSkinAnalysis;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "previous_skin_analysis_id", nullable = false, foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
	private SkinAnalysis previousSkinAnalysis;

	@Enumerated(EnumType.STRING)
	@Column(name = "redness_change", nullable = false, length = 20)
	private ChangeDirection rednessChange;

	@Enumerated(EnumType.STRING)
	@Column(name = "trouble_change", nullable = false, length = 20)
	private ChangeDirection troubleChange;

	@CreationTimestamp
	@Column(name = "compared_at", nullable = false, updatable = false)
	private LocalDateTime comparedAt;

	public SkinComparison(
			SkinAnalysis currentSkinAnalysis, SkinAnalysis previousSkinAnalysis,
			ChangeDirection rednessChange, ChangeDirection troubleChange
	) {
		this.currentSkinAnalysis = currentSkinAnalysis;
		this.previousSkinAnalysis = previousSkinAnalysis;
		this.rednessChange = rednessChange;
		this.troubleChange = troubleChange;
	}
}
