package com.sangmyungyaho.barocare.routine.entity;

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

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "routine")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Routine {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "routine_id")
	private Long id;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Column(name = "category", nullable = false, length = 20)
	private String category;

	@Column(name = "title", nullable = false, length = 100)
	private String title;

	@Column(name = "intensity", nullable = false, length = 20)
	private String intensity;

	@Column(name = "is_completed", nullable = false)
	private boolean isCompleted;

	@Column(name = "routine_date", nullable = false)
	private LocalDate routineDate;

	// 예상 소요시간(분). 화면(오늘 이렇게 해보세요)에서 "1분/2분" 같은 배지로 쓰인다. nullable로 두어
	// 이 컬럼이 추가되기 전에 이미 생성된 과거 루틴 행과 충돌하지 않게 한다(과거 행은 null로 남는다).
	@Column(name = "estimated_minutes")
	private Integer estimatedMinutes;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	public Routine(Long userId, String category, String title, String intensity, LocalDate routineDate, Integer estimatedMinutes) {
		this.userId = userId;
		this.category = category;
		this.title = title;
		this.intensity = intensity;
		this.isCompleted = false;
		this.routineDate = routineDate;
		this.estimatedMinutes = estimatedMinutes;
	}

	public void complete() {
		this.isCompleted = true;
	}

	public void incomplete() {
		this.isCompleted = false;
	}

	// 개인화 원인 분석 결과, 이 루틴의 카테고리에 해당하는 요인이 AI가 확인한 주요 원인 후보로도
	// 지목됐을 때 강도를 격상한다("저강도" -> "적극개입"). 그 외 필드/생성 로직은 바꾸지 않는다.
	public void escalateToActiveIntervention() {
		this.intensity = "적극개입";
	}
}
