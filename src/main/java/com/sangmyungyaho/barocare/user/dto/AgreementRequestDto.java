package com.sangmyungyaho.barocare.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class AgreementRequestDto {
    
    @JsonProperty("marketing_agreed")
    private Boolean marketingAgreed;
    
}
