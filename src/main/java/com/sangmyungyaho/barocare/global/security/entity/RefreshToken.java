package com.sangmyungyaho.barocare.global.security.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import org.springframework.data.annotation.Id;
import org.springframework.data.redis.core.RedisHash;
import org.springframework.data.redis.core.TimeToLive;

@Getter
@AllArgsConstructor
@Builder
@RedisHash(value = "refreshToken")
public class RefreshToken {

    @Id
    private String id; // userId

    private String refreshToken;

    @TimeToLive
    private Long expiration; // 초 단위 수명
}
