package com.sangmyungyaho.barocare.checkin.service;

import com.sangmyungyaho.barocare.checkin.dto.CheckinDto;
import com.sangmyungyaho.barocare.checkin.entity.Checkin;
import com.sangmyungyaho.barocare.checkin.repository.CheckinRepository;
import com.sangmyungyaho.barocare.global.exception.ErrorCode;
import com.sangmyungyaho.barocare.global.exception.GlobalException;
import com.sangmyungyaho.barocare.routine.service.RoutineService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class CheckinService {

	private final CheckinRepository checkinRepository;
	private final RoutineService routineService;

	public CheckinDto.Response createCheckin(Long userId, CheckinDto.Request request) {
		if (checkinRepository.existsByUserIdAndCheckedDate(userId, request.checkedDate())) {
			throw new GlobalException(ErrorCode.CHECKIN_ALREADY_EXISTS);
		}

		Checkin checkin = checkinRepository.save(request.toEntity(userId));
		routineService.generateRoutines(userId, checkin);
		return CheckinDto.Response.from(checkin);
	}
}
