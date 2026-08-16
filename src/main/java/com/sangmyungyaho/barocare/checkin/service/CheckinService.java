package com.sangmyungyaho.barocare.checkin.service;

import com.sangmyungyaho.barocare.checkin.dto.CheckinDto;
import com.sangmyungyaho.barocare.checkin.entity.Checkin;
import com.sangmyungyaho.barocare.checkin.repository.CheckinRepository;
import com.sangmyungyaho.barocare.global.exception.ErrorCode;
import com.sangmyungyaho.barocare.global.exception.GlobalException;
import com.sangmyungyaho.barocare.routine.service.RoutineService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * 체크인 저장과 루틴 생성(원인 분석/AI 호출 가능) 사이의 결합도를 낮춘다.
 *
 * 이전에는 클래스 레벨 @Transactional로 "체크인 저장 + 루틴 생성"이 하나의 트랜잭션이었다. 루틴 생성이
 * 예외를 던지지 않는 한(RoutineService가 이미 내부적으로 흡수함) 데이터 정합성 문제는 없었지만, 그
 * 트랜잭션이 커밋될 때까지 체크인 저장에 쓰인 DB 커넥션이 루틴 생성(경우에 따라 OpenAI 동기 호출 포함)이
 * 끝날 때까지 계속 붙잡혀 있었다. 이제는 @Transactional을 제거해 체크인 저장(Spring Data JPA 리포지토리
 * 메서드 자체가 각자 짧은 트랜잭션으로 즉시 커밋)이 루틴 생성과 완전히 분리되고, 그 위에 try/catch를
 * 한 겹 더 둬서 루틴 생성 쪽에서 예상치 못한 예외가 나더라도 체크인 저장 응답 자체는 항상 성공하게 한다.
 */
@Service
@RequiredArgsConstructor
public class CheckinService {

	private static final Logger log = LoggerFactory.getLogger(CheckinService.class);

	private final CheckinRepository checkinRepository;
	private final RoutineService routineService;

	public CheckinDto.Response createCheckin(Long userId, CheckinDto.Request request) {
		if (checkinRepository.existsByUserIdAndCheckedDate(userId, request.checkedDate())) {
			throw new GlobalException(ErrorCode.CHECKIN_ALREADY_EXISTS);
		}

		// 체크인 저장은 여기서 이미 커밋된다(트랜잭션 경계가 아래 루틴 생성과 분리되어 있음).
		Checkin checkin = checkinRepository.save(request.toEntity(userId));

		// 루틴 생성(및 그 안에서 참조하는 원인 분석/AI 호출)이 실패해도 체크인 저장 자체는 이미 끝났으므로
		// 영향받지 않는다. RoutineService가 이미 AI 관련 예외를 내부에서 흡수하지만, 예상치 못한 오류까지
		// 방어하기 위해 여기서도 한 번 더 감싼다.
		try {
			routineService.generateRoutines(userId, checkin);
		} catch (RuntimeException e) {
			log.warn("루틴 생성 실패(체크인 저장에는 영향 없음): userId={}, checkinId={}", userId, checkin.getId(), e);
		}

		return CheckinDto.Response.from(checkin);
	}

	/**
	 * 오늘의 체크인 조회(프론트 화면 연동). userId는 인증된 사용자 기준으로만 조회하므로
	 * 다른 사용자의 체크인을 조회할 방법이 없다.
	 */
	@Transactional(readOnly = true)
	public CheckinDto.Response getTodayCheckin(Long userId) {
		Checkin checkin = checkinRepository.findByUserIdAndCheckedDate(userId, LocalDate.now())
				.orElseThrow(() -> new GlobalException(ErrorCode.TODAY_CHECKIN_NOT_FOUND));
		return CheckinDto.Response.from(checkin);
	}

	/**
	 * 기간별(startDate~endDate 포함) 체크인 조회(프론트 화면 연동). 날짜 오름차순으로 반환하며,
	 * 기록이 없으면 에러 없이 빈 목록을 반환한다(SkinAnalysis 히스토리 조회와 동일한 관례).
	 */
	@Transactional(readOnly = true)
	public List<CheckinDto.Response> getCheckinsByDateRange(Long userId, LocalDate startDate, LocalDate endDate) {
		return checkinRepository.findAllByUserIdAndCheckedDateBetweenOrderByCheckedDateAsc(userId, startDate, endDate)
				.stream()
				.map(CheckinDto.Response::from)
				.toList();
	}
}
