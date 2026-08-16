package com.sangmyungyaho.barocare.policy.service;

import com.sangmyungyaho.barocare.policy.dto.PolicyDto;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PolicyServiceTest {

	private final PolicyService policyService = new PolicyService();

	@Test
	void type이_없으면_전체_정책을_반환한다() {
		List<PolicyDto.Response> result = policyService.getPolicies(null);

		assertThat(result).extracting(PolicyDto.Response::type).containsExactlyInAnyOrder("TERMS", "PRIVACY");
	}

	@Test
	void type을_지정하면_대소문자_구분_없이_해당_정책만_반환한다() {
		List<PolicyDto.Response> result = policyService.getPolicies("terms");

		assertThat(result).hasSize(1);
		assertThat(result.get(0).type()).isEqualTo("TERMS");
	}

	@Test
	void 존재하지_않는_type이면_에러_없이_빈_목록을_반환한다() {
		List<PolicyDto.Response> result = policyService.getPolicies("UNKNOWN");

		assertThat(result).isEmpty();
	}
}
