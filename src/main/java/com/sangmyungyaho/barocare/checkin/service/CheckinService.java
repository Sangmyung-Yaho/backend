package com.sangmyungyaho.barocare.checkin.service;

import com.sangmyungyaho.barocare.checkin.dto.CheckinDto;
import com.sangmyungyaho.barocare.checkin.entity.Checkin;
import com.sangmyungyaho.barocare.checkin.repository.CheckinRepository;
import com.sangmyungyaho.barocare.global.exception.ErrorCode;
import com.sangmyungyaho.barocare.global.exception.GlobalException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * 체크인 저장(오늘 전용).
 *
 * 루틴/원인 리포트 생성은 더 이상 체크인 저장 시점에 일어나지 않는다 - 실제 사용자 플로우(Figma 기준)는
 * "체크인 → 피부 사진 촬영/업로드 → 피부 분석 → 오늘 원인 리포트 생성 → 오늘의 루틴 생성" 순서이므로,
 * 체크인 저장 시점에는 아직 오늘 피부 분석이 없어 직전(과거) 데이터로 루틴을 만드는 문제가 있었다.
 * 그 생성 트리거는 이제 {@link com.sangmyungyaho.barocare.skin.service.SkinAnalysisService#analyzeSkin}
 * (피부 분석 완료 시점)으로 옮겨졌다. 체크인 서비스는 저장만 담당한다.
 */
@Service
@RequiredArgsConstructor
public class CheckinService {

	private final CheckinRepository checkinRepository;

	public CheckinDto.Response createCheckin(Long userId, CheckinDto.Request request) {
		LocalDate today = LocalDate.now();
		if (checkinRepository.existsByUserIdAndCheckedDate(userId, today)) {
			throw new GlobalException(ErrorCode.CHECKIN_ALREADY_EXISTS);
		}

		Checkin checkin = checkinRepository.save(request.toEntity(userId));
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
