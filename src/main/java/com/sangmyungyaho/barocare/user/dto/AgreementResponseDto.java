package com.sangmyungyaho.barocare.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class AgreementResponseDto {
    
    @JsonProperty("marketing_agreed")
    private boolean marketingAgreed;
    
}
