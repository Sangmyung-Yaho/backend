package com.sangmyungyaho.barocare.routine.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sangmyungyaho.barocare.ai.client.ProductSearchClient;
import com.sangmyungyaho.barocare.ai.dto.AiDto;
import com.sangmyungyaho.barocare.routine.entity.RecommendationStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

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

	/**
	 * GET /api/v1/skin-analyses/{skinAnalysisId}/ingredients 응답. 이 GET 요청 자체는 새로운 AI 호출을
	 * 시작하지 않는다 - 이미 저장된 상태/데이터를 그대로 반환할 뿐이다. ingredients는 status가 COMPLETED일
	 * 때만 채워지고, 그 외(PENDING/PROCESSING/FAILED)에는 빈 배열이다(프론트가 status만으로 로딩/실패
	 * UI를 그릴 수 있게 하기 위함 - null이 아니라 빈 배열로 둬서 클라이언트가 매번 null 체크를 하지
	 * 않아도 된다).
	 */
	@Schema(name = "IngredientRecommendationStatusResponse")
	public record IngredientStatusResponse(
			@Schema(description = "추천 성분 생성 상태", example = "COMPLETED")
			RecommendationStatus status,

			@Schema(description = "추천 성분 목록. status가 COMPLETED가 아니면 빈 배열.")
			List<IngredientItem> ingredients
	) {

		public static IngredientStatusResponse of(RecommendationStatus status, List<IngredientItem> ingredients) {
			return new IngredientStatusResponse(status, ingredients == null ? List.of() : ingredients);
		}
	}

	/**
	 * GET /api/v1/skin-analyses/{skinAnalysisId}/products 응답. IngredientStatusResponse와 동일한 규칙 -
	 * 이 GET 요청 자체는 새로운 웹검색을 시작하지 않으며, products는 status가 COMPLETED일 때만 채워진다.
	 */
	@Schema(name = "ProductRecommendationStatusResponse")
	public record ProductStatusResponse(
			@Schema(description = "추천 제품 생성 상태", example = "COMPLETED")
			RecommendationStatus status,

			@Schema(description = "추천 제품 목록. status가 COMPLETED가 아니면 빈 배열.")
			List<ProductItem> products
	) {

		public static ProductStatusResponse of(RecommendationStatus status, List<ProductItem> products) {
			return new ProductStatusResponse(status, products == null ? List.of() : products);
		}
	}
}
