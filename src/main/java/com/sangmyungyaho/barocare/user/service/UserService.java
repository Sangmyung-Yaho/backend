package com.sangmyungyaho.barocare.user.service;

import com.sangmyungyaho.barocare.global.exception.ErrorCode;
import com.sangmyungyaho.barocare.global.exception.GlobalException;
import com.sangmyungyaho.barocare.user.dto.OnboardingStatusResponseDto;
import com.sangmyungyaho.barocare.user.entity.User;
import com.sangmyungyaho.barocare.user.repository.UserRepository;
import com.sangmyungyaho.barocare.global.security.repository.RefreshTokenRepository;
import com.sangmyungyaho.barocare.user.dto.WithdrawRequestDto;
import com.sangmyungyaho.barocare.user.entity.WithdrawalLog;
import com.sangmyungyaho.barocare.user.repository.WithdrawalLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final WithdrawalLogRepository withdrawalLogRepository;
    private final RefreshTokenRepository refreshTokenRepository;

    @Transactional(readOnly = true)
    public OnboardingStatusResponseDto getOnboardingStatus(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new GlobalException(ErrorCode.USER_NOT_FOUND));

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

        // TODO: S3 이미지 즉시 삭제 처리 로직 추가 필요
        // SkinImage/SkinAnalysis 모두 userId를 보유하므로 조회 자체는 가능하다.
        // 여기서 ImageStorageService.delete(...) 호출을 통해 해당 유저의 이미지를 동기 삭제해야 한다.

        // DB 유저 삭제 (CASCADE에 의해 하위 데이터 자동 삭제)
        userRepository.delete(user);

        // Redis Refresh Token 삭제 (로그아웃 효과)
        refreshTokenRepository.deleteById(userId.toString());
    }
}
