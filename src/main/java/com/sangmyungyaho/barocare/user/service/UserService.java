package com.sangmyungyaho.barocare.user.service;

import com.sangmyungyaho.barocare.user.dto.OnboardingStatusResponseDto;
import com.sangmyungyaho.barocare.user.entity.User;
import com.sangmyungyaho.barocare.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public OnboardingStatusResponseDto getOnboardingStatus(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("유저를 찾을 수 없습니다. id=" + userId));

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
                .orElseThrow(() -> new IllegalArgumentException("유저를 찾을 수 없습니다. id=" + userId));

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
}
