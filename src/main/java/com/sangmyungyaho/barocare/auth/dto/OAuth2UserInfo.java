package com.sangmyungyaho.barocare.auth.dto;

//카카오와 구글이 주는 유저 정보 JSON 구조 통일용 인터페잏스
public interface OAuth2UserInfo {
    String getProvider();
    String getProviderId();
    String getNickname();
}
