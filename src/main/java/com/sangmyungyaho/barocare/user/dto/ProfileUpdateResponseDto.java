package com.sangmyungyaho.barocare.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ProfileUpdateResponseDto {
    @JsonProperty("water_goal_ml")
    private Integer waterGoalMl;
}
