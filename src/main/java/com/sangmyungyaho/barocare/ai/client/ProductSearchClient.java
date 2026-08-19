package com.sangmyungyaho.barocare.ai.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sangmyungyaho.barocare.global.exception.ErrorCode;
import com.sangmyungyaho.barocare.global.exception.GlobalException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 실시간 웹 검색 기반 "관련 제품" 조회(ISSUE-30).
 *
 * 기존 {@link AiClient}는 Spring AI {@code ChatClient}로 구조화 출력만 다루고 web_search 도구는 쓰지
 * 않으므로, 이 컴포넌트는 별도로 OpenAI Responses API(POST /v1/responses)를 {@link RestClient}로 직접
 * 호출한다 - AiClient를 web_search 대응까지 확장하는 대신, "AI 호출을 전담"이라는 AiClient의 책임 범위를
 * 그대로 유지하기 위해 분리했다.
 *
 * 핵심 요구사항: LLM이 알고 있는 지식만으로 제품명을 지어내면 안 된다. 그래서
 * - {@code tool_choice: "required"}로 web_search 도구 실행을 강제하고,
 * - 프롬프트에도 "검색으로 확인되지 않은 제품은 제외"를 명시하고,
 * - 응답을 파싱한 뒤에도 필수 필드(특히 productUrl)가 비어 있는 항목은 다시 한번 걸러낸다(2중 방어).
 *
 * 이 클라이언트는 DB의 JSON 컬럼을 다루는 게 아니라 OpenAI 원문 응답 파싱만 담당하므로, 프로젝트의 다른
 * 서비스가 쓰는 tools.jackson(Jackson 3, DB JSON 컬럼 직렬화용)이 아니라 이 클래스 안에서 독립적으로
 * com.fasterxml.jackson(Jackson 2, HTTP 응답 파싱용)을 사용한다 - 서로 다른 관심사라 굳이 같은 빈을
 * 공유할 필요가 없다.
 */
@Component
public class ProductSearchClient {

	private static final Logger log = LoggerFactory.getLogger(ProductSearchClient.class);

	private static final String RESPONSES_API_URL = "https://api.openai.com/v1/responses";

	// web_search 도구를 지원하는 모델만 쓸 수 있다(기존 ChatClient 기본 모델인 gpt-4o-mini는 미지원).
	// 비용을 고려해 지원 모델 중 가장 저렴한 mini 등급을 사용한다.
	private static final String MODEL = "gpt-4.1-mini";

	private static final int MAX_PRODUCTS = 3;

	private static final String SYSTEM_INSTRUCTIONS = """
			너는 실제로 판매 중인 화장품을 웹 검색으로 찾아서 추천하는 도구다.

			다음 원칙을 반드시 지켜라.
			- 반드시 web_search 도구로 실제 검색을 수행한 뒤, 검색 결과에서 실제로 존재가 확인된 제품만
			  추천한다. 검색하지 않고 이미 알고 있는 지식만으로 제품명을 만들어내지 않는다.
			- 검색 결과로 실제 존재 여부나 정확한 이름을 확인할 수 없는 제품은 추천 목록에서 제외한다.
			- 의약품이 아닌 일반 화장품(스킨케어 제품)만 추천한다.
			- productUrl은 검색으로 확인된 실제 URL만 쓴다. 가능하면 브랜드 공식 홈페이지 또는 공식 제품
			  페이지를 우선하고, 확실한 URL을 찾지 못한 제품은 제외한다.
			- 의학적 치료 효과를 단정하지 않고, 피부 관리 관점에서만 이유를 설명한다.
			- 결과는 최대 3개까지만 추천한다. 추천할 만한 제품이 없으면 빈 배열을 반환해도 된다.
			- 다른 설명 없이 아래 형식의 JSON 배열만 출력한다(마크다운 코드블록도 쓰지 않는다):
			  [{"brand": "...", "name": "...", "matchedIngredient": "...", "reason": "...", "productUrl": "..."}]
			""";

	private final RestClient restClient;
	private final ObjectMapper objectMapper = new ObjectMapper();

	// Boot가 자동 구성한 RestClient.Builder를 주입받아 그 위에 baseUrl/헤더만 얹는다. 이 컴포넌트가
	// 임의로 requestFactory를 새로 설정하지 않는 이유: (1) 타임아웃 등 HTTP 전송 설정은 앱 전역에서
	// RestClientCustomizer 빈으로 관리하는 게 컴포넌트마다 흩어놓는 것보다 낫고, (2) 빌더에 이미 다른
	// requestFactory가 꽂혀 있으면(테스트에서 MockRestServiceServer.bindTo(builder)가 그렇게 한다)
	// 여기서 덮어쓰는 순간 그 설정이 무효화된다 - 주입받은 빌더를 신뢰하고 존중해야 테스트도 가능하다.
	public ProductSearchClient(
			RestClient.Builder restClientBuilder,
			@Value("${spring.ai.openai.api-key}") String apiKey
	) {
		this.restClient = restClientBuilder
				.baseUrl(RESPONSES_API_URL)
				.defaultHeader("Authorization", "Bearer " + apiKey)
				.build();
	}

	/**
	 * 추천 성분명 목록을 근거로 실제 판매 중인 제품을 웹에서 검색해 최대 3개까지 반환한다.
	 *
	 * @throws GlobalException AI_ANALYSIS_FAILED - API 호출 실패, 응답 파싱 실패, 유효한 제품이 하나도
	 *                          없는 경우(모두 실패로 취급 - 검증되지 않은 제품을 반환하느니 빈 결과가 낫다)
	 */
	public List<ProductSuggestion> search(List<String> ingredientNames) {
		try {
			String requestJson = objectMapper.writeValueAsString(buildRequestBody(ingredientNames));

			String responseBody = restClient.post()
					.contentType(MediaType.APPLICATION_JSON)
					.body(requestJson)
					.retrieve()
					.body(String.class);

			String outputText = extractOutputText(responseBody);
			List<ProductSuggestion> parsed = objectMapper.readValue(cleanJson(outputText), new TypeReference<List<ProductSuggestion>>() {
			});

			List<ProductSuggestion> valid = parsed.stream()
					.filter(this::isValid)
					.limit(MAX_PRODUCTS)
					.toList();

			log.info("제품 웹 검색 완료: ingredientNames={}, 검색결과={}건, 유효={}건", ingredientNames, parsed.size(), valid.size());
			return valid;
		} catch (Exception e) {
			log.warn("제품 웹 검색 실패(OpenAI Responses API 호출 또는 응답 파싱 단계): ingredientNames={}", ingredientNames, e);
			throw new GlobalException(ErrorCode.AI_ANALYSIS_FAILED);
		}
	}

	private Map<String, Object> buildRequestBody(List<String> ingredientNames) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("model", MODEL);
		body.put("instructions", SYSTEM_INSTRUCTIONS);
		body.put("input", "다음 추천 성분과 관련된 실제 판매 중인 화장품을 웹에서 검색해서 추천해줘: " + String.join(", ", ingredientNames));
		body.put("tools", List.of(Map.of("type", "web_search")));
		body.put("tool_choice", "required"); // 검색 없이 지식만으로 답하는 걸 막기 위해 강제한다.
		return body;
	}

	// Responses API 응답의 output[] 배열에서 role=assistant인 message의 output_text를 꺼낸다.
	// (output[]에는 web_search_call 항목도 함께 들어있으므로 type="message"인 항목만 본다.)
	private String extractOutputText(String responseBody) throws Exception {
		JsonNode root = objectMapper.readTree(responseBody);
		JsonNode output = root.get("output");
		if (output == null || !output.isArray()) {
			throw new IllegalStateException("OpenAI 응답에 output 배열이 없습니다: " + responseBody);
		}

		for (JsonNode item : output) {
			if (!"message".equals(textOf(item.get("type")))) {
				continue;
			}
			JsonNode content = item.get("content");
			if (content == null || !content.isArray()) {
				continue;
			}
			for (JsonNode block : content) {
				if ("output_text".equals(textOf(block.get("type")))) {
					String text = textOf(block.get("text"));
					if (StringUtils.hasText(text)) {
						return text;
					}
				}
			}
		}
		throw new IllegalStateException("OpenAI 응답에서 output_text를 찾을 수 없습니다: " + responseBody);
	}

	private String textOf(JsonNode node) {
		return node == null || node.isNull() ? null : node.asText();
	}

	// 모델이 지시를 어기고 ```json ... ``` 코드블록으로 감싸 응답하는 경우를 대비한 방어적 정리.
	private String cleanJson(String text) {
		String trimmed = text.trim();
		if (trimmed.startsWith("```")) {
			trimmed = trimmed.replaceFirst("^```[a-zA-Z]*\\n?", "");
			int lastFence = trimmed.lastIndexOf("```");
			if (lastFence >= 0) {
				trimmed = trimmed.substring(0, lastFence);
			}
		}
		return trimmed.trim();
	}

	// 필수 필드가 비어 있거나 productUrl이 URL 형태가 아니면(검색으로 확인되지 않았을 가능성이 높음) 제외한다.
	private boolean isValid(ProductSuggestion product) {
		return StringUtils.hasText(product.brand())
				&& StringUtils.hasText(product.name())
				&& StringUtils.hasText(product.matchedIngredient())
				&& StringUtils.hasText(product.reason())
				&& StringUtils.hasText(product.productUrl())
				&& product.productUrl().startsWith("http");
	}

	public record ProductSuggestion(
			@JsonProperty("brand") String brand,
			@JsonProperty("name") String name,
			@JsonProperty("matchedIngredient") String matchedIngredient,
			@JsonProperty("reason") String reason,
			@JsonProperty("productUrl") String productUrl
	) {
	}
}
