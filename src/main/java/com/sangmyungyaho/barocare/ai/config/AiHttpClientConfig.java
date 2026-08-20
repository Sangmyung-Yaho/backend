package com.sangmyungyaho.barocare.ai.config;

import com.openai.core.Timeout;
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * POST /skin-analyses 504 원인 분석에서 나온 두 가지 별도 HTTP 타임아웃 설정을 모아둔다. 두 빈은 서로 다른
 * HTTP 클라이언트에 적용되므로(하나로 합쳐지지 않는다) 각각 어디에 적용되는지 빈 단위로 설명을 남긴다.
 *
 * ① {@link #productSearchTimeoutCustomizer()} - ProductSearchClient(OpenAI Responses API 웹검색) 전용.
 * ② {@link #aiClientTimeoutCustomizer()} - AiClient(Vision/원인분석/성분추천, Spring AI ChatClient) 전용.
 */
@Configuration
public class AiHttpClientConfig {

	// 실제 gpt-5-nano + web_search 호출을 붙여 측정한 결과 정상 응답도 ~12.9초가 걸려 15초 여유가 빠듯했다
	// (검색 성분이 늘거나 web_search 왕복이 더 걸리면 15초를 넘겨 정상 응답도 타임아웃으로 잘릴 위험이 있음).
	// 정상 응답을 더 여유 있게 기다리도록 20초로 늘린다 - 그래도 무제한 대기보다는 훨씬 짧고, 뒤 단계
	// (제품 웹검색은 체인의 마지막 단계)가 없어 이 20초는 그대로 POST /skin-analyses 전체 지연에 더해진다.
	private static final Duration PRODUCT_SEARCH_TIMEOUT = Duration.ofSeconds(20);

	// Vision/원인분석/성분추천 각각의 목표 상한. spring.ai.openai.timeout(application.yaml)과 같은 값을
	// 쓰지만, 그 프로퍼티는 request(전체 호출) 타임아웃만 지정하고 connect는 SDK 기본값(1분)을 그대로 쓴다
	// (openai-java Timeout: read/write는 request로 폴백하지만 connect는 별도 고정 기본값). 여기서
	// connect/read/write/request를 전부 명시적으로 같은 값으로 맞춰, "connect만 여전히 1분 대기"하는
	// 상황이 남지 않게 한다.
	private static final Duration AI_CLIENT_TIMEOUT = Duration.ofSeconds(15);

	/**
	 * ProductSearchClient(OpenAI Responses API 웹검색) 전용 HTTP connect/read timeout 설정.
	 *
	 * 배경: ProductSearchClient는 Boot가 자동 구성하는 {@code RestClient.Builder} 빈을 그대로 주입받아
	 * 쓰는데, 이 빈에는 기본적으로 별도 타임아웃이 설정되어 있지 않다(사실상 무제한 대기).
	 * {@code tool_choice: "required"}로 실제 웹검색을 강제하는 호출 특성상 응답이 느려질 수 있고, 이게
	 * 그대로 POST /skin-analyses 전체 응답 시간에 더해져 앞단 프록시/ALB의 기본 idle timeout(보통 60초)을
	 * 넘기는 504의 주요 원인 중 하나였다.
	 *
	 * 이 빈은 이 프로젝트에서 (Boot가 자동 구성하는) {@code RestClient.Builder}를 실제로 주입받는 유일한
	 * 컴포넌트인 {@link com.sangmyungyaho.barocare.ai.client.ProductSearchClient}에만 실질적으로
	 * 적용된다 - AiClient(Vision/원인분석/성분추천)는 별도 빈({@link #aiClientTimeoutCustomizer()})으로
	 * 관리하고, OAuth2ClientService는 {@code RestClient.create()}로 이 빈을 거치지 않으므로 영향이 없다.
	 * 테스트({@code ProductSearchClientTest})는 Spring 컨텍스트 없이 {@code RestClient.builder()}를 직접
	 * 만들어 MockRestServiceServer를 바인딩하므로 이 커스터마이저의 영향을 받지 않는다(기존 목 기반
	 * 테스트에 영향 없음).
	 */
	@Bean
	public RestClientCustomizer productSearchTimeoutCustomizer() {
		return builder -> builder.requestFactory(
				ClientHttpRequestFactoryBuilder.detect()
						.build(HttpClientSettings.defaults().withTimeouts(PRODUCT_SEARCH_TIMEOUT, PRODUCT_SEARCH_TIMEOUT))
		);
	}

	/**
	 * AiClient(Vision/원인분석/성분추천) 전용 connect/read/write/request timeout 설정.
	 *
	 * AiClient는 Spring AI {@code ChatClient} 하나를 공유하고, 그 안의 세 메서드
	 * ({@code analyzeSkin}, {@code analyzeSkinChangeCauses}, {@code recommendIngredients})가 모두 같은
	 * OpenAI Java SDK 클라이언트({@code OpenAIClient})를 통해 호출된다 - 즉 이 빈 하나가 세 호출 모두에
	 * 동일하게 적용된다. Spring Boot의 {@code RestClientCustomizer}와는 별개 메커니즘으로, Spring AI가
	 * 제공하는 {@link OpenAiHttpClientBuilderCustomizer}가 {@code SpringAiOpenAiHttpClient.Builder}
	 * (내부적으로 OkHttp 기반)를 커스터마이즈할 수 있게 해준다. {@code spring.ai.openai.timeout}
	 * (application.yaml)만 설정하면 request/read/write는 그 값으로 맞춰지지만 connect는 SDK 기본값인
	 * 1분이 그대로 남으므로, 여기서 connect까지 명시적으로 같은 값으로 낮춘다.
	 */
	@Bean
	public OpenAiHttpClientBuilderCustomizer aiClientTimeoutCustomizer() {
		return builder -> builder.timeout(
				Timeout.builder()
						.connect(AI_CLIENT_TIMEOUT)
						.read(AI_CLIENT_TIMEOUT)
						.write(AI_CLIENT_TIMEOUT)
						.request(AI_CLIENT_TIMEOUT)
						.build()
		);
	}
}
