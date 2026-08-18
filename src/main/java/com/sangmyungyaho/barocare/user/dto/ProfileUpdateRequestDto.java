package com.sangmyungyaho.barocare.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sangmyungyaho.barocare.user.entity.SkinType;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class ProfileUpdateRequestDto {
    private String nickname;
    private Double height;
    private Double weight;
    
    @JsonProperty("skin_type")
    private SkinType skinType;
}
