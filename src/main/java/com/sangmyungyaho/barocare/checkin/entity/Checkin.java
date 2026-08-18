package com.sangmyungyaho.barocare.checkin.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 생활습관 체크인 엔티티.
 *
 * TODO: auth/user 도메인이 완성되면 userId(또는 User 연관관계)를 추가하고,
 *       아래 checked_date 단일 UNIQUE 제약을 UNIQUE(user_id, checked_date)로 변경해야 한다.
 *       현재는 사용자 구분 없이 checked_date 기준으로만 중복을 검증한다.
 */
@Entity
@Table(
		name = "checkin",
		uniqueConstraints = @UniqueConstraint(name = "uk_checkin_user_id_checked_date", columnNames = {"user_id", "checked_date"})
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Checkin {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "checkin_id")
	private Long id;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Column(name = "sleep_hours", nullable = false)
	private Double sleepHours;

	@Column(name = "stress_level", nullable = false)
	private Integer stressLevel;

	@Column(name = "water_intake_ml", nullable = false)
	private Integer waterIntakeMl;

	@Column(name = "checked_date", nullable = false)
	private LocalDate checkedDate;

	public Checkin(Long userId, Double sleepHours, Integer stressLevel, Integer waterIntakeMl, LocalDate checkedDate) {
		this.userId = userId;
		this.sleepHours = sleepHours;
		this.stressLevel = stressLevel;
		this.waterIntakeMl = waterIntakeMl;
		this.checkedDate = checkedDate;
	}
}
