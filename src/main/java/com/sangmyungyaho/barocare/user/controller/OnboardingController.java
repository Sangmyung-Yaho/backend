package com.sangmyungyaho.barocare.user.controller;

import com.sangmyungyaho.barocare.global.exception.ErrorResponse;
import com.sangmyungyaho.barocare.global.response.ApiResponse;
import com.sangmyungyaho.barocare.user.dto.OnboardingAgreementRequestDto;
import com.sangmyungyaho.barocare.user.dto.OnboardingStatusResponseDto;
import com.sangmyungyaho.barocare.user.dto.PhotoGuideAgreementRequestDto;
import com.sangmyungyaho.barocare.user.dto.SkinCarePauseReasonRequestDto;
import com.sangmyungyaho.barocare.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Onboarding", description = "온보딩 상태 및 온보딩 데이터 저장 관련 API")
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

    @Operation(
            summary = "온보딩 필수 약관 동의 저장",
            description = "이용약관 및 개인정보 수집·이용 동의 여부를 저장합니다. "
                    + "마케팅 정보 수신 동의(PATCH /api/v1/users/me/agreements)와는 별개의 필수 항목이며, "
                    + "이 API 호출만으로는 온보딩이 완료(isOnboarded=true)되지 않습니다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "저장 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력값이 올바르지 않습니다.", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패 (UNAUTHORIZED)", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "사용자 없음 (USER_NOT_FOUND)", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/agreements")
    public ResponseEntity<ApiResponse<Void>> saveRequiredAgreements(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody OnboardingAgreementRequestDto request) {

        Long userId = Long.parseLong(userDetails.getUsername());
        userService.updateRequiredAgreements(userId, request);
        return ResponseEntity.ok(ApiResponse.success("필수 약관 동의 정보가 저장되었습니다.", null));
    }

    @Operation(
            summary = "피부 촬영 가이드 동의 저장",
            description = "피부 분석용 얼굴 사진 촬영 가이드 확인 및 동의 여부를 저장합니다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "저장 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력값이 올바르지 않습니다.", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패 (UNAUTHORIZED)", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "사용자 없음 (USER_NOT_FOUND)", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/photo-guide-agreement")
    public ResponseEntity<ApiResponse<Void>> savePhotoGuideAgreement(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody PhotoGuideAgreementRequestDto request) {

        Long userId = Long.parseLong(userDetails.getUsername());
        userService.updatePhotoGuideAgreement(userId, request);
        return ResponseEntity.ok(ApiResponse.success("피부 촬영 가이드 동의 정보가 저장되었습니다.", null));
    }

    @Operation(
            summary = "피부 관리 중단 이유 저장",
            description = "피부 관리를 중단했던 이유(선택 입력)를 저장합니다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "저장 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력값이 올바르지 않습니다.", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패 (UNAUTHORIZED)", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "사용자 없음 (USER_NOT_FOUND)", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/pause-reason")
    public ResponseEntity<ApiResponse<Void>> saveSkinCarePauseReason(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody SkinCarePauseReasonRequestDto request) {

        Long userId = Long.parseLong(userDetails.getUsername());
        userService.updateSkinCarePauseReason(userId, request);
        return ResponseEntity.ok(ApiResponse.success("피부 관리 중단 이유가 저장되었습니다.", null));
    }

    @Operation(
            summary = "온보딩 완료",
            description = "온보딩을 완료 처리(isOnboarded=true)합니다. 일반 프로필 수정(PATCH /api/v1/users/profile)만으로는 "
                    + "온보딩이 완료되지 않으며, 이 API를 명시적으로 호출했을 때만 완료됩니다. "
                    + "필수 약관(이용약관/개인정보 수집·이용)에 동의하지 않은 상태면 완료 처리하지 않습니다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "온보딩 완료 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "필수 약관 동의가 완료되지 않았습니다.",
                    content = @Content(
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(
                                    name = "ONBOARDING_AGREEMENT_REQUIRED",
                                    value = "{\"error\":{\"code\":\"ONBOARDING_AGREEMENT_REQUIRED\",\"message\":\"온보딩을 완료하려면 이용약관 및 개인정보 수집·이용에 동의해야 합니다.\"}}"
                            )
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패 (UNAUTHORIZED)", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "사용자 없음 (USER_NOT_FOUND)", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/complete")
    public ResponseEntity<OnboardingStatusResponseDto> completeOnboarding(
            @AuthenticationPrincipal UserDetails userDetails) {

        Long userId = Long.parseLong(userDetails.getUsername());
        OnboardingStatusResponseDto response = userService.completeOnboarding(userId);
        return ResponseEntity.ok(response);
    }
}
