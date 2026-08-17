package com.sangmyungyaho.barocare.report.entity;

import com.sangmyungyaho.barocare.skin.entity.SkinAnalysis;
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

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 피부 변화 원인 분석 리포트 엔티티(REP-101, GET /api/v1/reports/skin/latest).
 *
 * redness/trouble 점수는 실제 측정값이 아니라 SkinAnalysisLevel(SAFE=0/CAUTION=1/DANGER=2)을
 * 서수화한 값이다. status는 이 점수의 변화량(change)만으로 결정한다(AI의 이미지 비교 판단에 기대지 않음) -
 * score와 status가 서로 다른 기준이 되어 어긋나지 않도록 하기 위함이다. AI는 이 점수/상태를 계산하지 않고
 * 원인 후보 해석과 자연어 설명 생성에만 사용된다.
 *
 * primaryCauses는 별도 Entity로 분리하지 않고, API 응답과 동일한 구조(ReportDto.PrimaryCause 목록)를
 * JSON 문자열로 직렬화해 저장한다(SkinAnalysis가 관찰 구역 목록을 구분자 문자열로 저장하는 것과 같은 취지).
 *
 * 같은 currentSkinAnalysis를 기준으로 한 리포트는 재계산하지 않고 재사용하므로 유니크 제약을 둔다.
 *
 * previousSkinAnalysis/*PreviousScore/*Status는 nullable이다 - 사용자의 첫 피부 분석은 비교할 이전
 * 분석이 없어도 리포트가 생성되어야 하므로(원인 판정은 체크인 기반이라 피부 분석 이력과 무관하게 가능),
 * "비교 대상 없음"을 표현할 수 있어야 한다. previousSkinAnalysis가 null이면 두 점수/상태도 모두 null이다.
 */
@Entity
@Table(
		name = "report",
		uniqueConstraints = @UniqueConstraint(name = "uk_report_current_skin_analysis", columnNames = "current_skin_analysis_id")
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Report {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "report_id")
	private Long id;

	// FK 제약 없이 참조만 유지(프로젝트 컨벤션).
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "current_skin_analysis_id", nullable = false, foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
	private SkinAnalysis currentSkinAnalysis;

	// 첫 피부 분석(비교 대상 없음)이면 null이다.
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "previous_skin_analysis_id", foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
	private SkinAnalysis previousSkinAnalysis;

	@Column(name = "report_date", nullable = false)
	private LocalDate reportDate;

	@Column(name = "redness_previous_score")
	private Integer rednessPreviousScore;

	@Column(name = "redness_current_score", nullable = false)
	private Integer rednessCurrentScore;

	@Enumerated(EnumType.STRING)
	@Column(name = "redness_status", length = 20)
	private ReportChangeStatus rednessStatus;

	@Column(name = "trouble_previous_score")
	private Integer troublePreviousScore;

	@Column(name = "trouble_current_score", nullable = false)
	private Integer troubleCurrentScore;

	@Enumerated(EnumType.STRING)
	@Column(name = "trouble_status", length = 20)
	private ReportChangeStatus troubleStatus;

	// ReportDto.PrimaryCause 목록을 그대로 직렬화한 JSON 문자열. 파싱은 ReportService가 담당한다.
	@Column(name = "primary_causes", nullable = false, columnDefinition = "TEXT")
	private String primaryCausesJson;

	@Column(name = "summary", nullable = false, length = 1000)
	private String summary;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	public Report(
			SkinAnalysis currentSkinAnalysis, SkinAnalysis previousSkinAnalysis, LocalDate reportDate,
			Integer rednessPreviousScore, Integer rednessCurrentScore, ReportChangeStatus rednessStatus,
			Integer troublePreviousScore, Integer troubleCurrentScore, ReportChangeStatus troubleStatus,
			String primaryCausesJson, String summary
	) {
		this.currentSkinAnalysis = currentSkinAnalysis;
		this.previousSkinAnalysis = previousSkinAnalysis;
		this.reportDate = reportDate;
		this.rednessPreviousScore = rednessPreviousScore;
		this.rednessCurrentScore = rednessCurrentScore;
		this.rednessStatus = rednessStatus;
		this.troublePreviousScore = troublePreviousScore;
		this.troubleCurrentScore = troubleCurrentScore;
		this.troubleStatus = troubleStatus;
		this.primaryCausesJson = primaryCausesJson;
		this.summary = summary;
	}
}
