package com.sangmyungyaho.barocare.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sangmyungyaho.barocare.user.entity.SkinType;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ProfileReadResponseDto {

    private String nickname;

    private Double height;

    private Double weight;

    @JsonProperty("skin_type")
    private SkinType skinType;

    @JsonProperty("marketing_agreed")
    private boolean marketingAgreed;

    @JsonProperty("water_goal_ml")
    private Integer waterGoalMl;
}
