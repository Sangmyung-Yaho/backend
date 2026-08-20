package com.sangmyungyaho.barocare.ai.client;

import com.sangmyungyaho.barocare.global.exception.ErrorCode;
import com.sangmyungyaho.barocare.global.exception.GlobalException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * ISSUE-30: ProductSearchClient(OpenAI Responses API web_search) 단위 테스트.
 *
 * 실제 네트워크 호출 없이 {@link MockRestServiceServer}로 HTTP 계층만 스텁한다. 핵심 검증 대상은
 * (1) output[] 안의 message.output_text를 올바르게 꺼내 JSON 배열로 파싱하는지, (2) 마크다운 코드블록으로
 * 감싸 응답해도 파싱되는지, (3) 필수 필드가 비었거나 productUrl이 URL이 아닌 항목은 걸러내는지,
 * (4) HTTP 실패/파싱 실패 시 AI_ANALYSIS_FAILED로 통일해서 던지는지다.
 */
class ProductSearchClientTest {

	private MockRestServiceServer mockServer;
	private ProductSearchClient client;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder();
		mockServer = MockRestServiceServer.bindTo(builder).build();
		client = new ProductSearchClient(builder, "test-api-key");
	}

	@Test
	void 웹검색_결과를_파싱해서_유효한_제품만_반환한다() {
		String outputText = "[{\"brand\":\"라로슈포제\",\"name\":\"시카플라스트 밤 B5+\",\"matchedIngredient\":\"판테놀\","
				+ "\"reason\":\"진정 관리에 도움을 줄 수 있습니다.\",\"productUrl\":\"https://www.laroche-posay.co.kr/product/a\"}]";
		mockServer.expect(requestTo("https://api.openai.com/v1/responses"))
				.andExpect(method(org.springframework.http.HttpMethod.POST))
				.andRespond(withSuccess(responsesApiEnvelope(outputText), MediaType.APPLICATION_JSON));

		List<ProductSearchClient.ProductSuggestion> result = client.search(List.of("판테놀"));

		assertThat(result).hasSize(1);
		assertThat(result.get(0).brand()).isEqualTo("라로슈포제");
		assertThat(result.get(0).matchedIngredient()).isEqualTo("판테놀");
	}

	@Test
	void 마크다운_코드블록으로_감싸_응답해도_파싱한다() {
		String fenced = "```json\n[{\"brand\":\"A\",\"name\":\"B\",\"matchedIngredient\":\"판테놀\","
				+ "\"reason\":\"이유\",\"productUrl\":\"https://example.com/p\"}]\n```";
		mockServer.expect(requestTo("https://api.openai.com/v1/responses"))
				.andRespond(withSuccess(responsesApiEnvelope(fenced), MediaType.APPLICATION_JSON));

		List<ProductSearchClient.ProductSuggestion> result = client.search(List.of("판테놀"));

		assertThat(result).hasSize(1);
	}

	@Test
	void productUrl이_없는_항목은_결과에서_제외한다() {
		String outputText = "[{\"brand\":\"A\",\"name\":\"B\",\"matchedIngredient\":\"판테놀\",\"reason\":\"이유\",\"productUrl\":\"\"}]";
		mockServer.expect(requestTo("https://api.openai.com/v1/responses"))
				.andRespond(withSuccess(responsesApiEnvelope(outputText), MediaType.APPLICATION_JSON));

		List<ProductSearchClient.ProductSuggestion> result = client.search(List.of("판테놀"));

		assertThat(result).isEmpty();
	}

	@Test
	void 검색결과가_4개를_넘으면_3개까지만_반환한다() {
		String outputText = "[" + String.join(",", List.of(
				productJson("A"), productJson("B"), productJson("C"), productJson("D")
		)) + "]";
		mockServer.expect(requestTo("https://api.openai.com/v1/responses"))
				.andRespond(withSuccess(responsesApiEnvelope(outputText), MediaType.APPLICATION_JSON));

		List<ProductSearchClient.ProductSuggestion> result = client.search(List.of("판테놀"));

		assertThat(result).hasSize(3);
	}

	@Test
	void HTTP_호출이_실패하면_AI_ANALYSIS_FAILED_예외를_던진다() {
		mockServer.expect(requestTo("https://api.openai.com/v1/responses"))
				.andRespond(withServerError());

		assertThatThrownBy(() -> client.search(List.of("판테놀")))
				.isInstanceOf(GlobalException.class)
				.extracting("errorCode")
				.isEqualTo(ErrorCode.AI_ANALYSIS_FAILED);
	}

	// ---------- 429 Too Many Requests 재시도(운영 로그에서 실측된 실패 원인 - OpenAI TPM 한도 일시 초과) ----------

	@Test
	void 첫_시도가_429면_짧은_대기_후_재시도해서_성공한다() {
		String outputText = "[{\"brand\":\"라로슈포제\",\"name\":\"시카플라스트 밤 B5+\",\"matchedIngredient\":\"판테놀\","
				+ "\"reason\":\"진정 관리에 도움을 줄 수 있습니다.\",\"productUrl\":\"https://www.laroche-posay.co.kr/product/a\"}]";
		mockServer.expect(requestTo("https://api.openai.com/v1/responses"))
				.andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
						.body(rateLimitErrorBody())
						.contentType(MediaType.APPLICATION_JSON));
		mockServer.expect(requestTo("https://api.openai.com/v1/responses"))
				.andRespond(withSuccess(responsesApiEnvelope(outputText), MediaType.APPLICATION_JSON));

		List<ProductSearchClient.ProductSuggestion> result = client.search(List.of("판테놀"));

		assertThat(result).hasSize(1);
		assertThat(result.get(0).brand()).isEqualTo("라로슈포제");
		mockServer.verify(); // 정확히 2번(429 + 성공) 호출됐는지까지 확인.
	}

	@Test
	void 재시도_한도까지_계속_429면_AI_ANALYSIS_FAILED를_던진다() {
		mockServer.expect(requestTo("https://api.openai.com/v1/responses"))
				.andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).body(rateLimitErrorBody()).contentType(MediaType.APPLICATION_JSON));
		mockServer.expect(requestTo("https://api.openai.com/v1/responses"))
				.andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).body(rateLimitErrorBody()).contentType(MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> client.search(List.of("판테놀")))
				.isInstanceOf(GlobalException.class)
				.extracting("errorCode")
				.isEqualTo(ErrorCode.AI_ANALYSIS_FAILED);
		mockServer.verify(); // 재시도 한도(2회)만큼만 호출되고 무한 재시도하지 않는지 확인.
	}

	@Test
	void HTTP_상태코드가_429가_아니면_재시도하지_않고_즉시_실패한다() {
		// withServerError()(500)는 딱 1번만 stub돼 있다 - 코드가 실수로 재시도를 시도하면
		// MockRestServiceServer가 "더 이상 예상된 요청이 없다"며 즉시 실패하므로, 이 테스트가 예외 없이
		// GlobalException만 받고 끝난다는 것 자체가 "재시도하지 않았다"는 증거다.
		mockServer.expect(requestTo("https://api.openai.com/v1/responses"))
				.andRespond(withServerError());

		assertThatThrownBy(() -> client.search(List.of("판테놀")))
				.isInstanceOf(GlobalException.class)
				.extracting("errorCode")
				.isEqualTo(ErrorCode.AI_ANALYSIS_FAILED);
		mockServer.verify();
	}

	private String rateLimitErrorBody() {
		return """
				{"error": {"message": "Rate limit reached for gpt-5-nano on tokens per min (TPM). Please try again in 441ms.",
				"type": "tokens", "param": null, "code": "rate_limit_exceeded"}}
				""";
	}

	@Test
	void output_text를_찾을_수_없으면_AI_ANALYSIS_FAILED_예외를_던진다() {
		mockServer.expect(requestTo("https://api.openai.com/v1/responses"))
				.andRespond(withSuccess("{\"output\": [{\"type\": \"web_search_call\"}]}", MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> client.search(List.of("판테놀")))
				.isInstanceOf(GlobalException.class)
				.extracting("errorCode")
				.isEqualTo(ErrorCode.AI_ANALYSIS_FAILED);
	}

	private String productJson(String name) {
		return "{\"brand\":\"" + name + "브랜드\",\"name\":\"" + name + "\",\"matchedIngredient\":\"판테놀\","
				+ "\"reason\":\"이유\",\"productUrl\":\"https://example.com/" + name + "\"}";
	}

	// OpenAI Responses API 응답 envelope. output[]에 web_search_call과 message(role=assistant)가
	// 함께 들어있는 실제 응답 구조를 최소한으로 흉내낸다.
	private String responsesApiEnvelope(String outputText) {
		String escaped = outputText.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
		return """
				{
				  "output": [
				    {"type": "web_search_call", "id": "ws_1", "status": "completed"},
				    {"type": "message", "role": "assistant", "content": [
				      {"type": "output_text", "text": "%s"}
				    ]}
				  ]
				}
				""".formatted(escaped);
	}
}
