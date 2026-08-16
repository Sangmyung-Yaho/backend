package com.sangmyungyaho.barocare.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 온보딩 필수 약관(이용약관 / 개인정보 수집·이용) 동의 저장 요청.
 * 마케팅 정보 수신 동의(AgreementRequestDto, 선택)와는 별개의 필수 동의 항목이다.
 */
@Getter
@NoArgsConstructor
public class OnboardingAgreementRequestDto {

    @NotNull(message = "이용약관 동의 여부는 필수입니다.")
    @JsonProperty("terms_agreed")
    private Boolean termsAgreed;

    @NotNull(message = "개인정보 수집·이용 동의 여부는 필수입니다.")
    @JsonProperty("privacy_agreed")
    private Boolean privacyAgreed;
}
