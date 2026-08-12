package com.sangmyungyaho.barocare.auth.service;

import com.sangmyungyaho.barocare.auth.dto.GoogleUserInfo;
import com.sangmyungyaho.barocare.auth.dto.KakaoUserInfo;
import com.sangmyungyaho.barocare.auth.dto.OAuth2UserInfo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Service
public class OAuth2ClientService {

    private final RestClient restClient;

    @Value("${oauth2.kakao.client-id}")
    private String kakaoClientId;

    @Value("${oauth2.kakao.client-secret}")
    private String kakaoClientSecret;

    @Value("${oauth2.kakao.redirect-uri}")
    private String kakaoRedirectUri;

    @Value("${oauth2.google.client-id}")
    private String googleClientId;

    @Value("${oauth2.google.client-secret}")
    private String googleClientSecret;

    @Value("${oauth2.google.redirect-uri}")
    private String googleRedirectUri;

    public OAuth2ClientService() {
        this.restClient = RestClient.create();
    }

    public String getKakaoAccessToken(String code) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("grant_type", "authorization_code");
        params.add("client_id", kakaoClientId);
        params.add("client_secret", kakaoClientSecret);
        params.add("redirect_uri", kakaoRedirectUri);
        params.add("code", code);

        Map<String, Object> response = restClient.post()
                .uri("https://kauth.kakao.com/oauth/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(params)
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {});

        return (String) response.get("access_token");
    }

    public OAuth2UserInfo getKakaoUserInfo(String accessToken) {
        Map<String, Object> attributes = restClient.get()
                .uri("https://kapi.kakao.com/v2/user/me")
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-type", "application/x-www-form-urlencoded;charset=utf-8")
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {});

        return new KakaoUserInfo(attributes);
    }

    public String getGoogleIdToken(String code) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("grant_type", "authorization_code");
        params.add("client_id", googleClientId);
        params.add("client_secret", googleClientSecret);
        params.add("redirect_uri", googleRedirectUri);
        params.add("code", code);

        Map<String, Object> response = restClient.post()
                .uri("https://oauth2.googleapis.com/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(params)
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {});

        return (String) response.get("id_token");
    }

    public OAuth2UserInfo getGoogleUserInfo(String idToken) {
        Map<String, Object> attributes = restClient.get()
                .uri("https://oauth2.googleapis.com/tokeninfo?id_token=" + idToken)
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {});

        return new GoogleUserInfo(attributes);
    }
}
