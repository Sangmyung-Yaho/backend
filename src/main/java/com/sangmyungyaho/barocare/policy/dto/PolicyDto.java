package com.sangmyungyaho.barocare.policy.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sangmyungyaho.barocare.policy.entity.PolicyType;
import io.swagger.v3.oas.annotations.media.Schema;

public class PolicyDto {

	@Schema(name = "PolicyResponse")
	public record Response(
			@Schema(description = "정책 종류", example = "TERMS")
			String type,

			@Schema(description = "정책 제목", example = "이용약관")
			String title,

			@Schema(description = "정책 버전", example = "1.0")
			String version,

			@Schema(description = "정책 본문", example = "제1조 (목적) ...")
			String content
	) {

		public static Response from(PolicyType policyType) {
			return new Response(policyType.name(), policyType.getTitle(), policyType.getVersion(), policyType.getContent());
		}
	}
}
