package com.sangmyungyaho.barocare.checkin.repository;

import com.sangmyungyaho.barocare.checkin.entity.Checkin;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;

public interface CheckinRepository extends JpaRepository<Checkin, Long> {

	// TODO: User 연동 후 existsByUserIdAndCheckedDate(Long userId, LocalDate checkedDate)로 변경
	boolean existsByCheckedDate(LocalDate checkedDate);
}
