package com.sangmyungyaho.barocare.routine.repository;

import com.sangmyungyaho.barocare.routine.entity.Routine;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface RoutineRepository extends JpaRepository<Routine, Long> {
	List<Routine> findAllByUserIdAndRoutineDate(Long userId, LocalDate routineDate);
	long countByUserIdAndRoutineDate(Long userId, LocalDate routineDate);
	long countByUserIdAndRoutineDateAndIsCompletedTrue(Long userId, LocalDate routineDate);

	// 같은 날 피부 분석이 여러 번 호출돼도 루틴이 중복 생성되지 않도록 하는 멱등성 체크용 조회.
	boolean existsByUserIdAndRoutineDate(Long userId, LocalDate routineDate);

	void deleteAllByUserId(Long userId);
}
