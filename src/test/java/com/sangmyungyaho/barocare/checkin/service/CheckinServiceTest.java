package com.sangmyungyaho.barocare.checkin.service;

import com.sangmyungyaho.barocare.checkin.dto.CheckinDto;
import com.sangmyungyaho.barocare.checkin.entity.Checkin;
import com.sangmyungyaho.barocare.checkin.repository.CheckinRepository;
import com.sangmyungyaho.barocare.global.exception.ErrorCode;
import com.sangmyungyaho.barocare.global.exception.GlobalException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 체크인 저장(오늘 전용) 단위 테스트.
 *
 * 루틴/원인 리포트 생성은 더 이상 체크인 저장 시점에 일어나지 않는다(SkinAnalysisService.analyzeSkin()으로
 * 트리거가 옮겨졌다 - RoutineServiceTest/SkinAnalysisServiceTest에서 별도 검증). checked_date도 더 이상
 * 클라이언트가 지정할 수 없고 항상 서버 기준 오늘 날짜로 저장된다.
 */
@ExtendWith(MockitoExtension.class)
class CheckinServiceTest {

	private static final Long USER_ID = 1L;

	@Mock
	private CheckinRepository checkinRepository;

	@InjectMocks
	private CheckinService checkinService;

	@Test
	void 정상_흐름에서는_오늘_날짜로_체크인을_저장한다() {
		CheckinDto.Request request = new CheckinDto.Request(7.0, 2, 1500);
		when(checkinRepository.existsByUserIdAndCheckedDate(USER_ID, LocalDate.now())).thenReturn(false);
		when(checkinRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

		CheckinDto.Response response = checkinService.createCheckin(USER_ID, request);

		assertThat(response.sleepHours()).isEqualTo(7.0);
		assertThat(response.checkedDate()).isEqualTo(LocalDate.now());
		verify(checkinRepository).save(any());
	}

	@Test
	void 오늘_이미_체크인이_있으면_저장하지_않고_예외를_던진다() {
		CheckinDto.Request request = new CheckinDto.Request(7.0, 2, 1500);
		when(checkinRepository.existsByUserIdAndCheckedDate(USER_ID, LocalDate.now())).thenReturn(true);

		assertThatThrownBy(() -> checkinService.createCheckin(USER_ID, request))
				.isInstanceOf(GlobalException.class)
				.extracting(e -> ((GlobalException) e).getErrorCode())
				.isEqualTo(ErrorCode.CHECKIN_ALREADY_EXISTS);

		verify(checkinRepository, never()).save(any());
	}

	@Test
	void 오늘의_체크인이_있으면_조회된다() {
		Checkin todayCheckin = new Checkin(USER_ID, 7.5, 2, 1800, LocalDate.now());
		when(checkinRepository.findByUserIdAndCheckedDate(USER_ID, LocalDate.now())).thenReturn(Optional.of(todayCheckin));

		CheckinDto.Response response = checkinService.getTodayCheckin(USER_ID);

		assertThat(response.sleepHours()).isEqualTo(7.5);
	}

	@Test
	void 오늘의_체크인이_없으면_TODAY_CHECKIN_NOT_FOUND를_던진다() {
		when(checkinRepository.findByUserIdAndCheckedDate(USER_ID, LocalDate.now())).thenReturn(Optional.empty());

		assertThatThrownBy(() -> checkinService.getTodayCheckin(USER_ID))
				.isInstanceOf(GlobalException.class)
				.extracting(e -> ((GlobalException) e).getErrorCode())
				.isEqualTo(ErrorCode.TODAY_CHECKIN_NOT_FOUND);
	}

	@Test
	void 기간별_체크인은_날짜_오름차순으로_반환된다() {
		LocalDate start = LocalDate.of(2026, 8, 10);
		LocalDate end = LocalDate.of(2026, 8, 16);
		Checkin earlier = new Checkin(USER_ID, 7.0, 2, 1500, LocalDate.of(2026, 8, 10));
		Checkin later = new Checkin(USER_ID, 6.5, 3, 1400, LocalDate.of(2026, 8, 15));
		when(checkinRepository.findAllByUserIdAndCheckedDateBetweenOrderByCheckedDateAsc(USER_ID, start, end))
				.thenReturn(List.of(earlier, later));

		List<CheckinDto.Response> response = checkinService.getCheckinsByDateRange(USER_ID, start, end);

		assertThat(response).hasSize(2);
		assertThat(response.get(0).checkedDate()).isEqualTo(LocalDate.of(2026, 8, 10));
		assertThat(response.get(1).checkedDate()).isEqualTo(LocalDate.of(2026, 8, 15));
	}

	@Test
	void 기간별_체크인_기록이_없으면_빈_배열을_반환한다() {
		LocalDate start = LocalDate.of(2026, 8, 10);
		LocalDate end = LocalDate.of(2026, 8, 16);
		when(checkinRepository.findAllByUserIdAndCheckedDateBetweenOrderByCheckedDateAsc(USER_ID, start, end))
				.thenReturn(List.of());

		List<CheckinDto.Response> response = checkinService.getCheckinsByDateRange(USER_ID, start, end);

		assertThat(response).isEmpty();
	}
}
