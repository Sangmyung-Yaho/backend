package com.sangmyungyaho.barocare.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 피부 촬영 가이드 동의 저장 요청(온보딩).
 */
@Getter
@NoArgsConstructor
public class PhotoGuideAgreementRequestDto {

    @NotNull(message = "피부 촬영 가이드 동의 여부는 필수입니다.")
    @JsonProperty("photo_guide_agreed")
    private Boolean photoGuideAgreed;
}
