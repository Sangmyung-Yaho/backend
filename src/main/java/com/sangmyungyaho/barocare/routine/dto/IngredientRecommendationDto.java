package com.sangmyungyaho.barocare.routine.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sangmyungyaho.barocare.ai.client.ProductSearchClient;
import com.sangmyungyaho.barocare.ai.dto.AiDto;

public class IngredientRecommendationDto {

	/**
	 * 추천 성분 카드. AiDto.IngredientSuggestion을 그대로 옮겨 담을 뿐, 필드를 추가하지 않는다
	 * (AiDto는 AI 원시 응답 매핑용, 이 record는 API 응답/DB 저장용 - Report의 AiDto.Cause / ReportDto.PrimaryCause
	 * 관계와 같은 이유로 계층을 분리했다).
	 */
	public record IngredientItem(
			String name,
			String reason
	) {

		public static IngredientItem from(AiDto.IngredientSuggestion suggestion) {
			return new IngredientItem(suggestion.name(), suggestion.reason());
		}
	}

	/**
	 * 관련 제품 카드. 필드명은 이 기능의 요구사항에 명시된 형태(brand/name/matchedIngredient/reason/productUrl)를
	 * 그대로 따른다 - 프로젝트의 다른 응답 필드는 snake_case를 쓰지만, 이 카드 내부 필드만은 요구된 예시 형태를
	 * 우선했다(바깥쪽 recommended_products 키 자체는 기존 컨벤션대로 snake_case).
	 */
	public record ProductItem(
			String brand,
			String name,
			@JsonProperty("matchedIngredient") String matchedIngredient,
			String reason,
			@JsonProperty("productUrl") String productUrl
	) {

		public static ProductItem from(ProductSearchClient.ProductSuggestion suggestion) {
			return new ProductItem(
					suggestion.brand(), suggestion.name(), suggestion.matchedIngredient(),
					suggestion.reason(), suggestion.productUrl()
			);
		}
	}
}
