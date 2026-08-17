package com.sangmyungyaho.barocare.global.config;

import com.sangmyungyaho.barocare.global.security.handler.CustomAccessDeniedHandler;
import com.sangmyungyaho.barocare.global.security.handler.CustomAuthenticationEntryPoint;
import com.sangmyungyaho.barocare.global.security.jwt.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final CustomAuthenticationEntryPoint customAuthenticationEntryPoint;
    private final CustomAccessDeniedHandler customAccessDeniedHandler;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // REST API 방식이므로 CSRF, Form Login, Http Basic 인증 비활성화
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)

                // 로컬 API 검증용 프론트(frontend-test)에서의 크로스 오리진 호출 허용
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))

                // JWT를 사용하므로 세션을 STATELESS로 설정
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // 라우팅 (엔드포인트 인가) 설정
                .authorizeHttpRequests(auth -> auth
                        // CORS preflight(OPTIONS) 요청은 인증 없이 통과
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // 스웨거 UI 및 API 문서화 경로는 항상 열어둠
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/swagger-resources/**").permitAll()
                        // 로그인, 회원가입 관련 API 및 기본 에러 처리는 누구나 접근 가능
                        .requestMatchers("/api/v1/auth/**", "/error").permitAll()
                        // 그 외의 모든 요청은 인증 필요 (막아둠)
                        .anyRequest().authenticated()
                )

                // 예외 처리기 등록
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(customAuthenticationEntryPoint)
                        .accessDeniedHandler(customAccessDeniedHandler)
                )

                // JwtFilter를 기본 인증 필터(UsernamePasswordAuthenticationFilter) 전에 배치
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * 로컬 API 검증용 프론트(frontend-test 등)에서의 요청만 허용하는 CORS 설정.
     * localhost/127.0.0.1의 모든 포트를 허용하며, 배포 환경 오리진은 포함하지 않는다.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(List.of("http://localhost:*", "http://127.0.0.1:*"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(false);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
