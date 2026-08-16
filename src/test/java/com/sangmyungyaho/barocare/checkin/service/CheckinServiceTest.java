package com.sangmyungyaho.barocare.checkin.service;

import com.sangmyungyaho.barocare.checkin.dto.CheckinDto;
import com.sangmyungyaho.barocare.checkin.entity.Checkin;
import com.sangmyungyaho.barocare.checkin.repository.CheckinRepository;
import com.sangmyungyaho.barocare.global.exception.ErrorCode;
import com.sangmyungyaho.barocare.global.exception.GlobalException;
import com.sangmyungyaho.barocare.routine.service.RoutineService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 4 보완 #2: 체크인 저장과 루틴 생성(원인 분석/AI 호출 가능) 사이의 결합도 검증.
 *
 * 핵심 요구사항: 루틴 생성(그 내부의 AI 원인 분석 호출)이 실패해도 체크인 저장 자체는 항상 성공해야 한다.
 */
@ExtendWith(MockitoExtension.class)
class CheckinServiceTest {

	private static final Long USER_ID = 1L;

	@Mock
	private CheckinRepository checkinRepository;

	@Mock
	private RoutineService routineService;

	@InjectMocks
	private CheckinService checkinService;

	@Test
	void 정상_흐름에서는_체크인을_저장하고_루틴_생성을_호출한다() {
		CheckinDto.Request request = new CheckinDto.Request(7.0, 2, 1500, LocalDate.of(2026, 8, 10));
		when(checkinRepository.existsByUserIdAndCheckedDate(USER_ID, request.checkedDate())).thenReturn(false);
		when(checkinRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

		CheckinDto.Response response = checkinService.createCheckin(USER_ID, request);

		assertThat(response.sleepHours()).isEqualTo(7.0);
		verify(checkinRepository).save(any());
		verify(routineService).generateRoutines(eq(USER_ID), any(Checkin.class));
	}

	@Test
	void 이미_체크인이_있으면_저장과_루틴_생성_모두_호출하지_않고_예외를_던진다() {
		CheckinDto.Request request = new CheckinDto.Request(7.0, 2, 1500, LocalDate.of(2026, 8, 10));
		when(checkinRepository.existsByUserIdAndCheckedDate(USER_ID, request.checkedDate())).thenReturn(true);

		assertThatThrownBy(() -> checkinService.createCheckin(USER_ID, request))
				.isInstanceOf(GlobalException.class)
				.extracting(e -> ((GlobalException) e).getErrorCode())
				.isEqualTo(ErrorCode.CHECKIN_ALREADY_EXISTS);

		verify(checkinRepository, never()).save(any());
		verify(routineService, never()).generateRoutines(anyLong(), any());
	}

	@Test
	void 루틴_생성이_AI_분석_실패로_예외를_던져도_체크인_저장은_유지된다() {
		// RoutineService는 이미 내부적으로 AI 관련 예외를 흡수하지만(GlobalException(AI_ANALYSIS_FAILED) 등),
		// 여기서는 "혹시 그 방어선이 뚫리더라도" 체크인 저장 자체는 영향받지 않는지를 검증한다.
		CheckinDto.Request request = new CheckinDto.Request(6.0, 3, 1000, LocalDate.of(2026, 8, 10));
		when(checkinRepository.existsByUserIdAndCheckedDate(USER_ID, request.checkedDate())).thenReturn(false);
		when(checkinRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
		doThrow(new GlobalException(ErrorCode.AI_ANALYSIS_FAILED))
				.when(routineService).generateRoutines(eq(USER_ID), any(Checkin.class));

		CheckinDto.Response response = checkinService.createCheckin(USER_ID, request);

		assertThat(response).isNotNull();
		assertThat(response.sleepHours()).isEqualTo(6.0);
		verify(checkinRepository).save(any());
	}

	@Test
	void 루틴_생성이_예상치_못한_런타임_예외를_던져도_체크인_저장은_유지된다() {
		CheckinDto.Request request = new CheckinDto.Request(8.0, 1, 2000, LocalDate.of(2026, 8, 10));
		when(checkinRepository.existsByUserIdAndCheckedDate(USER_ID, request.checkedDate())).thenReturn(false);
		when(checkinRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
		doThrow(new IllegalStateException("예상치 못한 오류"))
				.when(routineService).generateRoutines(eq(USER_ID), any(Checkin.class));

		CheckinDto.Response response = checkinService.createCheckin(USER_ID, request);

		assertThat(response).isNotNull();
		verify(checkinRepository).save(any());
	}
}
