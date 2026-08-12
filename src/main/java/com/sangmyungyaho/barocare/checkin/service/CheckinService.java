package com.sangmyungyaho.barocare.checkin.service;

import com.sangmyungyaho.barocare.checkin.dto.CheckinDto;
import com.sangmyungyaho.barocare.checkin.entity.Checkin;
import com.sangmyungyaho.barocare.checkin.repository.CheckinRepository;
import com.sangmyungyaho.barocare.global.exception.ErrorCode;
import com.sangmyungyaho.barocare.global.exception.GlobalException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CheckinService {

	private final CheckinRepository checkinRepository;

	public CheckinDto.Response createCheckin(CheckinDto.Request request) {
		// TODO: User 연동 후에는 (userId, checkedDate) 기준으로 중복 검증해야 한다.
		if (checkinRepository.existsByCheckedDate(request.checkedDate())) {
			throw new GlobalException(ErrorCode.CHECKIN_ALREADY_EXISTS);
		}

		Checkin checkin = checkinRepository.save(request.toEntity());
		return CheckinDto.Response.from(checkin);
	}
}
