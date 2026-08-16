package com.sangmyungyaho.barocare.user.service;

import com.sangmyungyaho.barocare.global.exception.ErrorCode;
import com.sangmyungyaho.barocare.global.exception.GlobalException;
import com.sangmyungyaho.barocare.global.security.repository.RefreshTokenRepository;
import com.sangmyungyaho.barocare.user.dto.AgreementRequestDto;
import com.sangmyungyaho.barocare.user.dto.ProfileUpdateRequestDto;
import com.sangmyungyaho.barocare.user.dto.WithdrawRequestDto;
import com.sangmyungyaho.barocare.user.repository.UserRepository;
import com.sangmyungyaho.barocare.user.repository.WithdrawalLogRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * fix: 기존 인증 및 사용자 데이터 처리 안정화.
 *
 * UserService의 모든 조회/수정 메서드가 사용자 미존재 시 IllegalArgumentException이 아니라
 * 프로젝트 표준 예외(GlobalException + ErrorCode.USER_NOT_FOUND)를 던지는지 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

	private static final Long USER_ID = 999L;

	@Mock
	private UserRepository userRepository;

	@Mock
	private WithdrawalLogRepository withdrawalLogRepository;

	@Mock
	private RefreshTokenRepository refreshTokenRepository;

	@InjectMocks
	private UserService userService;

	@Test
	void 온보딩_상태_조회시_사용자가_없으면_USER_NOT_FOUND를_던진다() {
		when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> userService.getOnboardingStatus(USER_ID))
				.isInstanceOf(GlobalException.class)
				.extracting(e -> ((GlobalException) e).getErrorCode())
				.isEqualTo(ErrorCode.USER_NOT_FOUND);
	}

	@Test
	void 프로필_수정시_사용자가_없으면_USER_NOT_FOUND를_던진다() {
		when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());
		ProfileUpdateRequestDto request = new ProfileUpdateRequestDto();

		assertThatThrownBy(() -> userService.updateProfile(USER_ID, request))
				.isInstanceOf(GlobalException.class)
				.extracting(e -> ((GlobalException) e).getErrorCode())
				.isEqualTo(ErrorCode.USER_NOT_FOUND);
	}

	@Test
	void 프로필_조회시_사용자가_없으면_USER_NOT_FOUND를_던진다() {
		when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> userService.getProfile(USER_ID))
				.isInstanceOf(GlobalException.class)
				.extracting(e -> ((GlobalException) e).getErrorCode())
				.isEqualTo(ErrorCode.USER_NOT_FOUND);
	}

	@Test
	void 약관_동의_조회시_사용자가_없으면_USER_NOT_FOUND를_던진다() {
		when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> userService.getAgreements(USER_ID))
				.isInstanceOf(GlobalException.class)
				.extracting(e -> ((GlobalException) e).getErrorCode())
				.isEqualTo(ErrorCode.USER_NOT_FOUND);
	}

	@Test
	void 약관_동의_수정시_사용자가_없으면_USER_NOT_FOUND를_던진다() {
		when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());
		AgreementRequestDto request = new AgreementRequestDto();

		assertThatThrownBy(() -> userService.updateAgreements(USER_ID, request))
				.isInstanceOf(GlobalException.class)
				.extracting(e -> ((GlobalException) e).getErrorCode())
				.isEqualTo(ErrorCode.USER_NOT_FOUND);
	}

	@Test
	void 회원_탈퇴시_사용자가_없으면_USER_NOT_FOUND를_던진다() {
		when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());
		WithdrawRequestDto request = new WithdrawRequestDto();

		assertThatThrownBy(() -> userService.withdraw(USER_ID, request))
				.isInstanceOf(GlobalException.class)
				.extracting(e -> ((GlobalException) e).getErrorCode())
				.isEqualTo(ErrorCode.USER_NOT_FOUND);
	}
}
