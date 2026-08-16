package com.sangmyungyaho.barocare.policy.service;

import com.sangmyungyaho.barocare.policy.dto.PolicyDto;
import com.sangmyungyaho.barocare.policy.entity.PolicyType;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

@Service
public class PolicyService {

	/**
	 * 약관/정책 콘텐츠를 조회한다. type이 없으면 전체 목록(이용약관 + 개인정보처리방침)을 반환하고,
	 * type이 있으면 해당 종류만 필터링한다. 존재하지 않는 type이면 에러 없이 빈 목록을 반환한다
	 * (다른 조회 API들의 "결과 없음 -> 빈 배열" 관례와 동일).
	 */
	public List<PolicyDto.Response> getPolicies(String type) {
		return Arrays.stream(PolicyType.values())
				.filter(policyType -> type == null || type.isBlank() || policyType.name().equalsIgnoreCase(type))
				.map(PolicyDto.Response::from)
				.toList();
	}
}
