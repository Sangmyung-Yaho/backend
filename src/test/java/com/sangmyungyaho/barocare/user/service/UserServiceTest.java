package com.sangmyungyaho.barocare.user.service;

import com.sangmyungyaho.barocare.checkin.repository.CheckinRepository;
import com.sangmyungyaho.barocare.global.exception.ErrorCode;
import com.sangmyungyaho.barocare.global.exception.GlobalException;
import com.sangmyungyaho.barocare.global.security.repository.RefreshTokenRepository;
import com.sangmyungyaho.barocare.global.storage.ImageStorageService;
import com.sangmyungyaho.barocare.report.repository.ReportRepository;
import com.sangmyungyaho.barocare.routine.repository.RoutineRepository;
import com.sangmyungyaho.barocare.skin.entity.SkinImage;
import com.sangmyungyaho.barocare.skin.repository.SkinAnalysisRepository;
import com.sangmyungyaho.barocare.skin.repository.SkinComparisonRepository;
import com.sangmyungyaho.barocare.skin.repository.SkinImageRepository;
import com.sangmyungyaho.barocare.user.dto.AgreementRequestDto;
import com.sangmyungyaho.barocare.user.dto.OnboardingAgreementRequestDto;
import com.sangmyungyaho.barocare.user.dto.OnboardingStatusResponseDto;
import com.sangmyungyaho.barocare.user.dto.PhotoGuideAgreementRequestDto;
import com.sangmyungyaho.barocare.user.dto.ProfileUpdateRequestDto;
import com.sangmyungyaho.barocare.user.dto.SkinCarePauseReasonRequestDto;
import com.sangmyungyaho.barocare.user.dto.WithdrawRequestDto;
import com.sangmyungyaho.barocare.user.entity.Provider;
import com.sangmyungyaho.barocare.user.entity.User;
import com.sangmyungyaho.barocare.user.repository.UserRepository;
import com.sangmyungyaho.barocare.user.repository.WithdrawalLogRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * fix: 기존 인증 및 사용자 데이터 처리 안정화.
 *
 * UserService의 모든 조회/수정 메서드가 사용자 미존재 시 IllegalArgumentException이 아니라
 * 프로젝트 표준 예외(GlobalException + ErrorCode.USER_NOT_FOUND)를 던지는지 검증한다.
 *
 * feat: 온보딩 데이터 저장 및 완료 처리.
 * 필수 약관/촬영 가이드 동의/관리 중단 이유 저장, 온보딩 완료(및 필수 약관 검증),
 * 일반 프로필 수정이 더 이상 isOnboarded를 바꾸지 않는지 검증한다.
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

	@Mock
	private CheckinRepository checkinRepository;

	@Mock
	private RoutineRepository routineRepository;

	@Mock
	private SkinAnalysisRepository skinAnalysisRepository;

	@Mock
	private SkinImageRepository skinImageRepository;

	@Mock
	private SkinComparisonRepository skinComparisonRepository;

	@Mock
	private ReportRepository reportRepository;

	@Mock
	private ImageStorageService imageStorageService;

	@InjectMocks
	private UserService userService;

	private User newUser() {
		User user = User.builder().provider(Provider.KAKAO).socialId("kakao-1").nickname("닉네임").build();
		ReflectionTestUtils.setField(user, "id", USER_ID);
		return user;
	}

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

	@Test
	void 회원_탈퇴시_남아있는_이미지_파일을_모두_삭제한_뒤_DB_데이터를_정리한다() {
		User user = newUser();
		when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

		SkinImage image1 = new SkinImage(USER_ID, "http://example.com/a.jpg", "a.jpg");
		SkinImage image2 = new SkinImage(USER_ID, "http://example.com/b.jpg", "b.jpg");
		when(skinImageRepository.findAllByUserId(USER_ID)).thenReturn(List.of(image1, image2));

		userService.withdraw(USER_ID, new WithdrawRequestDto());

		verify(imageStorageService).delete("skin-images", "a.jpg");
		verify(imageStorageService).delete("skin-images", "b.jpg");
		verify(skinImageRepository).deleteAllByUserId(USER_ID);
		verify(userRepository).delete(user);
	}

	@Test
	void 회원_탈퇴시_이미지_파일_삭제가_실패해도_탈퇴_처리는_계속_진행된다() {
		User user = newUser();
		when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

		SkinImage image = new SkinImage(USER_ID, "http://example.com/a.jpg", "a.jpg");
		when(skinImageRepository.findAllByUserId(USER_ID)).thenReturn(List.of(image));
		doThrow(new RuntimeException("디스크 IO 오류"))
				.when(imageStorageService).delete("skin-images", "a.jpg");

		userService.withdraw(USER_ID, new WithdrawRequestDto());

		verify(skinImageRepository).deleteAllByUserId(USER_ID);
		verify(userRepository).delete(user);
	}

	@Test
	void 필수_약관_동의_저장시_사용자가_없으면_USER_NOT_FOUND를_던진다() {
		when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());
		OnboardingAgreementRequestDto request = new OnboardingAgreementRequestDto();
		ReflectionTestUtils.setField(request, "termsAgreed", true);
		ReflectionTestUtils.setField(request, "privacyAgreed", true);

		assertThatThrownBy(() -> userService.updateRequiredAgreements(USER_ID, request))
				.isInstanceOf(GlobalException.class)
				.extracting(e -> ((GlobalException) e).getErrorCode())
				.isEqualTo(ErrorCode.USER_NOT_FOUND);
	}

	@Test
	void 필수_약관_동의를_저장하면_User에_반영된다() {
		User user = newUser();
		when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
		OnboardingAgreementRequestDto request = new OnboardingAgreementRequestDto();
		ReflectionTestUtils.setField(request, "termsAgreed", true);
		ReflectionTestUtils.setField(request, "privacyAgreed", true);

		userService.updateRequiredAgreements(USER_ID, request);

		assertThat(user.hasAgreedToRequiredTerms()).isTrue();
	}

	@Test
	void 피부_촬영_가이드_동의를_저장하면_User에_반영된다() {
		User user = newUser();
		when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
		PhotoGuideAgreementRequestDto request = new PhotoGuideAgreementRequestDto();
		ReflectionTestUtils.setField(request, "photoGuideAgreed", true);

		userService.updatePhotoGuideAgreement(USER_ID, request);

		assertThat(user.isPhotoGuideAgreed()).isTrue();
	}

	@Test
	void 피부_관리_중단_이유를_저장하면_User에_반영된다() {
		User user = newUser();
		when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
		SkinCarePauseReasonRequestDto request = new SkinCarePauseReasonRequestDto();
		ReflectionTestUtils.setField(request, "skinCarePauseReason", "귀찮아서 중단했어요");

		userService.updateSkinCarePauseReason(USER_ID, request);

		assertThat(user.getSkinCarePauseReason()).isEqualTo("귀찮아서 중단했어요");
	}

	@Test
	void 필수_약관에_동의하지_않았으면_온보딩_완료_요청시_ONBOARDING_AGREEMENT_REQUIRED를_던지고_isOnboarded는_그대로다() {
		User user = newUser(); // termsAgreed/privacyAgreed 모두 기본값 false
		when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

		assertThatThrownBy(() -> userService.completeOnboarding(USER_ID))
				.isInstanceOf(GlobalException.class)
				.extracting(e -> ((GlobalException) e).getErrorCode())
				.isEqualTo(ErrorCode.ONBOARDING_AGREEMENT_REQUIRED);

		assertThat(user.isOnboarded()).isFalse();
	}

	@Test
	void 필수_약관에_모두_동의했으면_온보딩_완료_요청시_isOnboarded가_true가_된다() {
		User user = newUser();
		user.updateRequiredAgreements(true, true);
		when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

		OnboardingStatusResponseDto response = userService.completeOnboarding(USER_ID);

		assertThat(user.isOnboarded()).isTrue();
		assertThat(response.getUser().isOnboarded()).isTrue();
	}

	@Test
	void 온보딩_완료시_사용자가_없으면_USER_NOT_FOUND를_던진다() {
		when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> userService.completeOnboarding(USER_ID))
				.isInstanceOf(GlobalException.class)
				.extracting(e -> ((GlobalException) e).getErrorCode())
				.isEqualTo(ErrorCode.USER_NOT_FOUND);
	}

	@Test
	void 일반_프로필_수정만으로는_isOnboarded가_바뀌지_않는다() {
		User user = newUser();
		when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
		ProfileUpdateRequestDto request = new ProfileUpdateRequestDto();
		ReflectionTestUtils.setField(request, "nickname", "새닉네임");
		ReflectionTestUtils.setField(request, "height", 170.0);
		ReflectionTestUtils.setField(request, "weight", 60.0);

		userService.updateProfile(USER_ID, request);

		assertThat(user.isOnboarded()).isFalse();
	}
}
