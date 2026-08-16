package com.sangmyungyaho.barocare.checkin.repository;

import com.sangmyungyaho.barocare.checkin.entity.Checkin;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface CheckinRepository extends JpaRepository<Checkin, Long> {

	boolean existsByUserIdAndCheckedDate(Long userId, LocalDate checkedDate);

	// 피부 변화 원인 리포트(REP-101)에서, 분석 대상 SkinAnalysis의 analyzedAt 이전(포함) 체크인 중
	// 가장 가까운 값과 그 이전 체크인들의 평균을 계산하기 위한 조회. 첫 번째 원소가 "가장 가까운" 체크인이다.
	List<Checkin> findAllByUserIdAndCheckedDateLessThanEqualOrderByCheckedDateDesc(Long userId, LocalDate checkedDate);
}
