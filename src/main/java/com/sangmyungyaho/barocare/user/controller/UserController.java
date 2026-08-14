package com.sangmyungyaho.barocare.user.controller;

import com.sangmyungyaho.barocare.global.response.ApiResponse;
import com.sangmyungyaho.barocare.user.dto.ProfileUpdateRequestDto;
import com.sangmyungyaho.barocare.user.dto.ProfileUpdateResponseDto;
import com.sangmyungyaho.barocare.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

@Tag(name = "User", description = "유저 및 프로필 관련 API")
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @Operation(summary = "프로필 정보 수정", description = "유저의 프로필 정보(닉네임, 키, 몸무게, 피부타입)를 수정하고 권장 목표 수분 섭취량을 반환합니다.")
    @PatchMapping("/profile")
    public ResponseEntity<ApiResponse<ProfileUpdateResponseDto>> updateProfile(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody ProfileUpdateRequestDto request) {

        Long userId = Long.parseLong(userDetails.getUsername());
        ProfileUpdateResponseDto responseDto = userService.updateProfile(userId, request);

        return ResponseEntity.ok(ApiResponse.success("프로필 정보가 수정되었습니다.", responseDto));
    }
}
