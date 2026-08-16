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

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	public Routine(Long userId, String category, String title, String intensity, LocalDate routineDate) {
		this.userId = userId;
		this.category = category;
		this.title = title;
		this.intensity = intensity;
		this.isCompleted = false;
		this.routineDate = routineDate;
	}

	public void complete() {
		this.isCompleted = true;
	}

	public void incomplete() {
		this.isCompleted = false;
	}
}
