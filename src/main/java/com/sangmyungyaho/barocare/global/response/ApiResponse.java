package com.sangmyungyaho.barocare.global.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@JsonPropertyOrder({"is_success", "message", "data"})
public class ApiResponse<T> {

    // 필드명을 isSuccess가 아니라 success로 둔다: Lombok이 boolean 필드 success에 대해
    // isSuccess() 게터를 생성하므로 공개 API(isSuccess())는 그대로 유지되면서도,
    // 필드의 암묵적 프로퍼티명과 게터의 암묵적 프로퍼티명이 둘 다 "success"로 일치해 Jackson이
    // 하나의 프로퍼티로 인식한다. (필드명이 isSuccess였을 때는 필드의 암묵적 이름은 "isSuccess",
    // 게터(isSuccess())의 암묵적 이름은 "success"로 서로 달라 Jackson이 별개 프로퍼티로 보고
    // is_success와 success를 중복 직렬화하는 문제가 있었다.)
    @JsonProperty("is_success")
    private boolean success;

    private String message;

    private T data;

    public static <T> ApiResponse<T> success(String message, T data) {
        return new ApiResponse<>(true, message, data);
    }
}
