package com.sangmyungyaho.barocare.user.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.stream.Stream;

@Getter
@RequiredArgsConstructor
public enum SkinType {
    DRY("건성"),
    OILY("지성"),
    COMBINATION("복합성"),
    SENSITIVE("민감성");

    private final String description;

    @JsonCreator
    public static SkinType parsing(String inputValue) {
        return Stream.of(SkinType.values())
                .filter(skinType -> skinType.getDescription().equals(inputValue) || skinType.name().equalsIgnoreCase(inputValue))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("유효하지 않은 피부 타입입니다."));
    }

    @JsonValue
    public String getDescription() {
        return description;
    }
}
