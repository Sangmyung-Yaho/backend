package com.sangmyungyaho.barocare.policy.controller;

import com.sangmyungyaho.barocare.global.security.jwt.JwtProvider;
import com.sangmyungyaho.barocare.policy.dto.PolicyDto;
import com.sangmyungyaho.barocare.policy.service.PolicyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * feat: 프론트 화면 연동을 위한 조회 API 구현 - 약관 및 정책 콘텐츠 조회 컨트롤러 슬라이스 테스트.
 */
@WebMvcTest(PolicyController.class)
@AutoConfigureMockMvc(addFilters = false)
class PolicyControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private PolicyService policyService;

	@MockitoBean
	private JwtProvider jwtProvider;

	@Test
	void type_없이_조회하면_전체_정책_목록을_반환한다() throws Exception {
		when(policyService.getPolicies(isNull())).thenReturn(List.of(
				new PolicyDto.Response("TERMS", "이용약관", "1.0", "..."),
				new PolicyDto.Response("PRIVACY", "개인정보처리방침", "1.0", "...")
		));

		mockMvc.perform(get("/api/v1/policies"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.is_success").value(true))
				.andExpect(jsonPath("$.data.length()").value(2));
	}

	@Test
	void type을_지정하면_해당_정책만_반환한다() throws Exception {
		when(policyService.getPolicies("TERMS")).thenReturn(List.of(
				new PolicyDto.Response("TERMS", "이용약관", "1.0", "...")
		));

		mockMvc.perform(get("/api/v1/policies").param("type", "TERMS"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[0].type").value("TERMS"))
				.andExpect(jsonPath("$.data[0].title").value("이용약관"));
	}

	@Test
	void 존재하지_않는_type이면_빈_배열을_반환한다() throws Exception {
		when(policyService.getPolicies("UNKNOWN")).thenReturn(List.of());

		mockMvc.perform(get("/api/v1/policies").param("type", "UNKNOWN"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data").isArray())
				.andExpect(jsonPath("$.data").isEmpty());
	}
}
