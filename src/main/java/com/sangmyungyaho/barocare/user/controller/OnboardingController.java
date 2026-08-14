package com.sangmyungyaho.barocare.user.controller;

import com.sangmyungyaho.barocare.user.dto.OnboardingStatusResponseDto;
import com.sangmyungyaho.barocare.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Onboarding", description = "온보딩 상태 관련 API")
@RestController
@RequestMapping("/api/v1/onboarding")
@RequiredArgsConstructor
public class OnboardingController {

    private final UserService userService;

    @Operation(summary = "온보딩 상태 조회", description = "JWT 토큰을 기반으로 현재 유저의 정보와 온보딩 완료 여부를 조회합니다.")
    @GetMapping("/status")
    public ResponseEntity<OnboardingStatusResponseDto> getOnboardingStatus(
            @AuthenticationPrincipal UserDetails userDetails) {

        Long userId = Long.parseLong(userDetails.getUsername());
        OnboardingStatusResponseDto response = userService.getOnboardingStatus(userId);
        return ResponseEntity.ok(response);
    }
}
