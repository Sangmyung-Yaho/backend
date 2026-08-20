package com.sangmyungyaho.barocare.user.service;

import com.sangmyungyaho.barocare.global.exception.ErrorCode;
import com.sangmyungyaho.barocare.global.exception.GlobalException;
import com.sangmyungyaho.barocare.global.storage.ImageStorageService;
import com.sangmyungyaho.barocare.skin.entity.SkinImage;
import com.sangmyungyaho.barocare.user.dto.OnboardingAgreementRequestDto;
import com.sangmyungyaho.barocare.user.dto.OnboardingStatusResponseDto;
import com.sangmyungyaho.barocare.user.dto.PhotoGuideAgreementRequestDto;
import com.sangmyungyaho.barocare.user.dto.SkinCarePauseReasonRequestDto;
import com.sangmyungyaho.barocare.user.entity.User;
import com.sangmyungyaho.barocare.user.repository.UserRepository;
import com.sangmyungyaho.barocare.global.security.repository.RefreshTokenRepository;
import com.sangmyungyaho.barocare.user.dto.WithdrawRequestDto;
import com.sangmyungyaho.barocare.user.entity.WithdrawalLog;
import com.sangmyungyaho.barocare.checkin.repository.CheckinRepository;
import com.sangmyungyaho.barocare.routine.repository.RoutineRepository;
import com.sangmyungyaho.barocare.skin.repository.SkinAnalysisRepository;
import com.sangmyungyaho.barocare.skin.repository.SkinImageRepository;
import com.sangmyungyaho.barocare.skin.repository.SkinComparisonRepository;
import com.sangmyungyaho.barocare.report.repository.ReportRepository;
import com.sangmyungyaho.barocare.user.repository.WithdrawalLogRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class UserService {

    private static final Logger logger = LoggerFactory.getLogger(UserService.class);

    // SkinImageService/SkinAnalysisService와 동일한 저장 하위 경로.
    private static final String SKIN_IMAGE_DIRECTORY = "skin-images";

    private final UserRepository userRepository;
    private final WithdrawalLogRepository withdrawalLogRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final CheckinRepository checkinRepository;
    private final RoutineRepository routineRepository;
    private final SkinAnalysisRepository skinAnalysisRepository;
    private final SkinImageRepository skinImageRepository;
    private final SkinComparisonRepository skinComparisonRepository;
    private final ReportRepository reportRepository;
    private final ImageStorageService imageStorageService;

    @Transactional(readOnly = true)
    public OnboardingStatusResponseDto getOnboardingStatus(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new GlobalException(ErrorCode.USER_NOT_FOUND));

        return toOnboardingStatusResponse(user);
    }

    /**
     * 온보딩 필수 약관(이용약관 / 개인정보 수집·이용) 동의 저장.
     * 마케팅 정보 수신 동의(updateAgreements)와는 별개의 필수 항목이며, isOnboarded는 여기서 바꾸지 않는다.
     */
    @Transactional
    public void updateRequiredAgreements(Long userId, OnboardingAgreementRequestDto request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new GlobalException(ErrorCode.USER_NOT_FOUND));

        user.updateRequiredAgreements(request.getTermsAgreed(), request.getPrivacyAgreed());
    }

    @Transactional
    public void updatePhotoGuideAgreement(Long userId, PhotoGuideAgreementRequestDto request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new GlobalException(ErrorCode.USER_NOT_FOUND));

        user.updatePhotoGuideAgreement(request.getPhotoGuideAgreed());
    }

    @Transactional
    public void updateSkinCarePauseReason(Long userId, SkinCarePauseReasonRequestDto request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new GlobalException(ErrorCode.USER_NOT_FOUND));

        user.updateSkinCarePauseReason(request.getSkinCarePauseReason());
    }

    /**
     * 온보딩 완료(POST /api/v1/onboarding/complete). isOnboarded=true는 오직 이 메서드를 통해서만 설정된다
     * (일반 프로필 수정(updateProfile)은 더 이상 이 값을 바꾸지 않는다).
     * 필수 약관(이용약관/개인정보 수집·이용) 동의가 완료되지 않았으면 완료 처리하지 않는다.
     */
    @Transactional
    public OnboardingStatusResponseDto completeOnboarding(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new GlobalException(ErrorCode.USER_NOT_FOUND));

        if (!user.hasAgreedToRequiredTerms()) {
            throw new GlobalException(ErrorCode.ONBOARDING_AGREEMENT_REQUIRED);
        }

        user.completeOnboarding();
        return toOnboardingStatusResponse(user);
    }

    private OnboardingStatusResponseDto toOnboardingStatusResponse(User user) {
        OnboardingStatusResponseDto.UserInfoDto userInfo = new OnboardingStatusResponseDto.UserInfoDto(
                user.getId(),
                user.getNickname(),
                user.getProvider().name(),
                user.isOnboarded(),
                user.getCreatedAt()
        );
        return new OnboardingStatusResponseDto(userInfo);
    }

    @Transactional
    public com.sangmyungyaho.barocare.user.dto.ProfileUpdateResponseDto updateProfile(Long userId, com.sangmyungyaho.barocare.user.dto.ProfileUpdateRequestDto request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new GlobalException(ErrorCode.USER_NOT_FOUND));

        Double height = request.getHeight() != null ? request.getHeight() : user.getHeight();
        Double weight = request.getWeight() != null ? request.getWeight() : user.getWeight();

        Integer waterGoalMl = null;
        if (height != null && weight != null && height > 0) {
            double heightInMeters = height / 100.0;
            double bmi = weight / (heightInMeters * heightInMeters);
            int coefficient;
            if (bmi < 18.5) {
                coefficient = 35;
            } else if (bmi < 23.0) {
                coefficient = 33;
            } else {
                coefficient = 30;
            }
            waterGoalMl = (int) (weight * coefficient);
        }

        user.updateProfile(
                request.getNickname(),
                request.getHeight(),
                request.getWeight(),
                request.getSkinType(),
                waterGoalMl
        );

        return new com.sangmyungyaho.barocare.user.dto.ProfileUpdateResponseDto(waterGoalMl);
    }

    @Transactional(readOnly = true)
    public com.sangmyungyaho.barocare.user.dto.ProfileReadResponseDto getProfile(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new GlobalException(ErrorCode.USER_NOT_FOUND));

        return new com.sangmyungyaho.barocare.user.dto.ProfileReadResponseDto(
                user.getNickname(),
                user.getHeight(),
                user.getWeight(),
                user.getSkinType(),
                user.isPushMarketing(),
                user.getWaterGoalMl()
        );
    }

    @Transactional(readOnly = true)
    public com.sangmyungyaho.barocare.user.dto.AgreementResponseDto getAgreements(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new GlobalException(ErrorCode.USER_NOT_FOUND));

        return new com.sangmyungyaho.barocare.user.dto.AgreementResponseDto(user.isPushMarketing());
    }

    @Transactional
    public void updateAgreements(Long userId, com.sangmyungyaho.barocare.user.dto.AgreementRequestDto request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new GlobalException(ErrorCode.USER_NOT_FOUND));

        if (request.getMarketingAgreed() != null) {
            user.updateMarketingAgreement(request.getMarketingAgreed());
        }
    }

    @Transactional
    public void withdraw(Long userId, WithdrawRequestDto request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new GlobalException(ErrorCode.USER_NOT_FOUND));

        // 탈퇴 사유 로깅
        WithdrawalLog log = new WithdrawalLog(userId, request.getWithdrawalReason());
        withdrawalLogRepository.save(log);

        // 원본 이미지 파일 삭제. 정책상 분석 성공 시 SkinAnalysisService가 즉시 지우므로 탈퇴 시점에
        // 남아있는 건 보통 "업로드만 하고 분석하지 않은" 이미지뿐이지만, 정리 배치 주기 전에 탈퇴하는
        // 경우까지 커버하기 위해 DB row를 지우기 전에 먼저 파일을 지운다. 파일 삭제는 best-effort이며
        // 실패해도(로그만 남기고) 탈퇴 자체는 계속 진행한다.
        List<SkinImage> remainingImages = skinImageRepository.findAllByUserId(userId);
        for (SkinImage image : remainingImages) {
            try {
                imageStorageService.delete(SKIN_IMAGE_DIRECTORY, image.getStoredFileName());
            } catch (RuntimeException e) {
                logger.warn("회원 탈퇴 처리 중 원본 이미지 파일 삭제 실패(탈퇴는 계속 진행): "
                                + "userId={}, skinImageId={}, storedFileName={}",
                        userId, image.getId(), image.getStoredFileName(), e);
            }
        }

        // 하위 데이터 명시 삭제 (JPA 연관관계 없이 Long userId로 연결되어 있어 수동 처리 필수)
        reportRepository.deleteAllByCurrentSkinAnalysis_UserId(userId);
        skinComparisonRepository.deleteAllByCurrentSkinAnalysis_UserId(userId);
        skinAnalysisRepository.deleteAllByUserId(userId);
        skinImageRepository.deleteAllByUserId(userId);
        routineRepository.deleteAllByUserId(userId);
        checkinRepository.deleteAllByUserId(userId);

        // DB 유저 삭제
        userRepository.delete(user);

        // Redis Refresh Token 삭제 (로그아웃 효과)
        refreshTokenRepository.deleteById(userId.toString());
    }
}
