package com.sangmyungyaho.barocare.checkin.repository;

import com.sangmyungyaho.barocare.checkin.entity.Checkin;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface CheckinRepository extends JpaRepository<Checkin, Long> {

	boolean existsByUserIdAndCheckedDate(Long userId, LocalDate checkedDate);

	// 피부 변화 원인 리포트(오늘 Report 생성) - 오늘 체크인을 제외한 개인기준선 산정용 이전 체크인 조회.
	// 최신순(내림차순)이며, LifestyleFactorRubric이 앞에서부터 7건을 잘라 기준선 윈도우로 사용한다.
	List<Checkin> findAllByUserIdAndCheckedDateLessThanOrderByCheckedDateDesc(Long userId, LocalDate checkedDate);

	// 프론트 화면 연동: 오늘의 체크인 조회.
	Optional<Checkin> findByUserIdAndCheckedDate(Long userId, LocalDate checkedDate);

	// 프론트 화면 연동: 기간별 체크인 조회(startDate~endDate 포함, 날짜 오름차순 — SkinAnalysis 히스토리와 동일한 정렬 관례).
	List<Checkin> findAllByUserIdAndCheckedDateBetweenOrderByCheckedDateAsc(Long userId, LocalDate startDate, LocalDate endDate);

	void deleteAllByUserId(Long userId);
}
