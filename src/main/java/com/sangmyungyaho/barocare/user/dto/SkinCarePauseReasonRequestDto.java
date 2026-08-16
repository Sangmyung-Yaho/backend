package com.sangmyungyaho.barocare.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 피부 관리 중단 이유 저장 요청(온보딩). 필수 항목이 아니므로 null(작성 안 함)도 허용한다.
 */
@Getter
@NoArgsConstructor
public class SkinCarePauseReasonRequestDto {

    @Size(max = 500, message = "피부 관리 중단 이유는 500자를 넘을 수 없습니다.")
    @JsonProperty("skin_care_pause_reason")
    private String skinCarePauseReason;
}
