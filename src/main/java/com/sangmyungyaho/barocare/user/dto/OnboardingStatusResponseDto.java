package com.sangmyungyaho.barocare.user.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
public class OnboardingStatusResponseDto {

    private UserInfoDto user;

    @Getter
    @AllArgsConstructor
    public static class UserInfoDto {
        private Long id;
        private String nickname;
        private String provider;
        private boolean isOnboarded;
        private LocalDateTime createdAt;
    }
}
