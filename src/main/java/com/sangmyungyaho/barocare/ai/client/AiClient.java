package com.sangmyungyaho.barocare.ai.client;

import com.sangmyungyaho.barocare.ai.dto.AiDto;
import com.sangmyungyaho.barocare.global.exception.ErrorCode;
import com.sangmyungyaho.barocare.global.exception.GlobalException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;

/**
 * OpenAI(Spring AI ChatClient) 호출을 전담하는 컴포넌트.
 * 도메인 로직(어떤 이미지를 분석할지, 결과를 어떻게 저장할지)은 알지 못하고,
 * "이미지 바이트를 주면 구조화된 피부 지표 분석 결과를 돌려준다"는 책임만 가진다.
 */
@Component
public class AiClient {

	private static final Logger log = LoggerFactory.getLogger(AiClient.class);

	/**
	 * GPT 관찰 스키마 버전. 이 프롬프트(관찰 스키마)를 수정하면 함께 올린다.
	 * 실제 SAFE/CAUTION/DANGER 판정 규칙 버전은 {@code SkinGradeRubric.RUBRIC_VERSION}에서 관리한다
	 * (GPT는 더 이상 등급을 직접 판정하지 않기 때문).
	 */
	public static final String OBSERVATION_SCHEMA_VERSION = "v3.1-observation";

	/**
	 * 피부 지표 "관찰값 추출" 프롬프트.
	 * 중요: GPT는 SAFE/CAUTION/DANGER 같은 최종 등급을 판정하지 않는다. 사진에서 시각적으로
	 * 관찰되는 사실(구역/강도/밀도)만 구조화해서 반환하고, 최종 등급은 Spring의 SkinGradeRubric이
	 * 고정된 규칙으로 계산한다. 등급 판정 기준을 바꾸고 싶으면 이 프롬프트가 아니라
	 * SkinGradeRubric을 수정해야 한다.
	 *
	 * v3.1: redness 관찰이 트러블의 붉은 반점만 인식하고, 뺨 등에서 옅게 퍼진 피부 바탕 붉은기(홍조성 붉은기)를
	 *       놓치는 문제를 보완 — "피부 바탕 자체가 주변보다 붉어 보이는 경우"도 관찰 대상에 명시적으로 포함.
	 */
	private static final String SKIN_OBSERVATION_SYSTEM_PROMPT = """
			너는 업로드된 얼굴 정면 사진에서, 시각적으로 관찰되는 사실만 구조화해서 보고하는 도구다.
			최종 등급(SAFE/CAUTION/DANGER 등)은 네가 정하지 않는다. 오직 관찰값만 정확하게 추출하라.

			다음 원칙을 반드시 지켜라.
			- 질환명 또는 의학적 진단을 생성하지 않는다.
			- 사진에서 시각적으로 관찰되는 외관만 보고한다.
			- 확실하지 않으면 관찰되지 않은 것으로 처리한다(해당 구역을 목록에 포함하지 않음). 관찰을 과장하지 않는다.

			얼굴은 다음 5개 구역으로만 구분한다: FOREHEAD(이마), CHEEK_LEFT(왼쪽 뺨), CHEEK_RIGHT(오른쪽 뺨), NOSE(코), CHIN(턱).

			[붉은기(redness) 관찰]
			redness는 트러블(뾰루지 등)의 붉은 반점과는 별개의 관찰 항목이다. 다음 두 가지 모두를 redness 관찰 대상으로 본다.
			- 뚜렷하게 구분되는 붉은 반점/영역
			- 트러블과 무관하게, 그 부위의 피부 바탕색 자체가 주변 피부보다 붉게 보이는 경우(홍조성 붉은기).
			  선명한 반점이 아니라 옅고 은은하게 퍼진 붉은기라도 실제로 관찰되면 포함한다.
			  특히 양쪽 뺨은 이런 옅은 홍조성 붉은기가 흔히 나타나는 부위이므로 놓치지 않도록 주의 깊게 살펴본다.

			다음은 redness에서 제외한다(아무리 붉어 보여도 포함하지 않음):
			- 그 사람의 정상적인 원래 피부톤
			- 조명, 화이트밸런스, 카메라 색보정으로 인한 색 변화
			- 입술, 콧구멍 주변, 눈가처럼 원래 붉은기가 자연스러운 부위

			- affectedRegions: 위 5개 구역 중 (트러블의 반점이 아닌) redness가 실제로 관찰되는 구역만 나열(없으면 빈 목록)
			- maxIntensity: affectedRegions 중 가장 강한 붉은기의 정도를 MILD(옅고 은은함) / MODERATE(중간) / SEVERE(뚜렷함) 중 하나로.
			  옅더라도 실제로 관찰되면 MILD로 포함하고 목록에서 제외하지 않는다.
			  affectedRegions가 비어 있으면 이 값은 의미가 없다.

			[트러블(trouble) 관찰]
			- affectedRegions: 위 5개 구역 중 트러블(뾰루지, 여드름 등)이 실제로 관찰되는 구역만 나열(없으면 빈 목록)
			- density: 관찰된 트러블의 전반적인 개수를 FEW(소수, 산발적) / MANY(다수, 밀집) 중 하나로.
			  affectedRegions가 비어 있으면 이 값은 의미가 없다.

			추가로 이미지 자체의 품질을 lighting(조명), blur(초점/흔들림), angle(정면 여부), faceRatio(얼굴이 프레임에서 차지하는 비율)
			네 가지 관점에서 각각 GOOD 또는 POOR로만 판정하라. 어느 하나라도 판단하기 어려울 정도로 나쁘면 POOR로 표시하라.
			""";

	/**
	 * 비교 프롬프트 버전. 이 프롬프트를 수정하면 함께 올린다.
	 */
	public static final String COMPARISON_SCHEMA_VERSION = "v1-comparison";

	/**
	 * 이전/현재 사진 두 장을 비교하는 "변화 방향 추출" 프롬프트.
	 * 중요: 이 호출은 SAFE/CAUTION/DANGER를 다시 판정하지 않는다. 오직 이전 대비 현재의
	 * 상대적인 변화(증가/유지/감소)만 비교한다.
	 */
	private static final String SKIN_COMPARISON_SYSTEM_PROMPT = """
			너는 같은 사람의 얼굴 사진 두 장을 비교해서, 피부 상태가 이전 사진 대비 현재 사진에서
			어떻게 달라졌는지만 보고하는 도구다. 최종 등급(SAFE/CAUTION/DANGER)은 판단하지 않는다.

			첫 번째로 첨부된 이미지는 "이전" 사진이고, 두 번째로 첨부된 이미지는 "현재" 사진이다.
			항상 이전 → 현재 방향의 변화만 보고한다.

			다음 원칙을 반드시 지켜라.
			- 질환명 또는 의학적 진단을 생성하지 않는다.
			- 조명, 촬영 각도, 사진 화질, 카메라 차이만으로 변화가 있다고 판단하지 않는다.
			  두 사진의 촬영 조건이 다를 수 있음을 감안해 보수적으로 판단하라.
			- 변화가 명확하지 않거나 확신이 서지 않으면 STABLE을 선택한다.

			[붉은기(redness) 변화 판단 기준]
			- 붉은 영역의 범위(퍼진 정도)가 이전보다 넓어졌는지 좁아졌는지
			- 붉은기의 시각적 강도(옅음/진함)가 이전보다 강해졌는지 약해졌는지
			위 두 기준을 종합해 INCREASED(증가) / STABLE(유지) / DECREASED(감소) 중 하나를 선택하라.

			[트러블(trouble) 변화 판단 기준]
			- 눈에 보이는 트러블의 수/밀도가 이전보다 늘었는지 줄었는지
			- 트러블이 나타나는 범위(부위 수)가 이전보다 넓어졌는지 좁아졌는지
			위 두 기준을 종합해 INCREASED(증가) / STABLE(유지) / DECREASED(감소) 중 하나를 선택하라.
			""";

	private final ChatClient chatClient;

	public AiClient(ChatClient.Builder chatClientBuilder) {
		this.chatClient = chatClientBuilder.build();
	}

	/**
	 * 얼굴 이미지를 OpenAI에 전달해 구조화된 "관찰값"을 받는다.
	 * 반환값에는 SAFE/CAUTION/DANGER 같은 최종 등급이 없다 — 등급 계산은 SkinGradeRubric의 몫이다.
	 *
	 * @param imageBytes 분석할 이미지 바이트
	 * @param mimeType   이미지 MIME 타입(image/jpeg, image/png)
	 * @return AI가 반환한 구조화된 관찰값
	 * @throws GlobalException AI_ANALYSIS_FAILED - API 호출 실패 또는 응답 파싱/형식 오류 시
	 */
	public AiDto.SkinAnalysisResult analyzeSkin(byte[] imageBytes, MimeType mimeType) {
		try {
			Media media = Media.builder()
					.mimeType(mimeType)
					.data(new ByteArrayResource(imageBytes))
					.build();

			AiDto.SkinAnalysisResult result = chatClient.prompt()
					.system(SKIN_OBSERVATION_SYSTEM_PROMPT)
					.user(u -> u.text("첨부된 얼굴 이미지에서 관찰값을 추출해줘.").media(media))
					.call()
					.entity(AiDto.SkinAnalysisResult.class);

			// 진단용 로그: OpenAI 호출/파싱은 성공했음을 표시하고 GPT가 추출한 관찰값을 그대로 남긴다.
			log.info("AI 관찰값 수신: redness={}, trouble={}, imageQuality={}",
					result.redness(), result.trouble(), result.imageQuality());
			return result;
		} catch (Exception e) {
			// OpenAI 원문 오류 메시지는 서버 로그에만 남기고, 클라이언트에는 노출하지 않는다.
			log.warn("피부 이미지 AI 관찰값 추출 실패(OpenAI 호출 또는 응답 파싱 단계)", e);
			throw new GlobalException(ErrorCode.AI_ANALYSIS_FAILED);
		}
	}

	/**
	 * 이전/현재 얼굴 이미지 두 장을 함께 OpenAI에 전달해 redness/trouble의 상대적 변화 방향을 받는다.
	 * SAFE/CAUTION/DANGER는 다시 판정하지 않는다.
	 *
	 * @param previousImageBytes 이전 사진 바이트
	 * @param previousMimeType   이전 사진 MIME 타입
	 * @param currentImageBytes  현재 사진 바이트
	 * @param currentMimeType    현재 사진 MIME 타입
	 * @return 구조화된 변화 비교 결과
	 * @throws GlobalException AI_ANALYSIS_FAILED - API 호출 실패 또는 응답 파싱/형식 오류 시
	 */
	public AiDto.SkinComparisonResult compareSkin(
			byte[] previousImageBytes, MimeType previousMimeType,
			byte[] currentImageBytes, MimeType currentMimeType
	) {
		try {
			Media previousMedia = Media.builder()
					.mimeType(previousMimeType)
					.data(new ByteArrayResource(previousImageBytes))
					.build();
			Media currentMedia = Media.builder()
					.mimeType(currentMimeType)
					.data(new ByteArrayResource(currentImageBytes))
					.build();

			AiDto.SkinComparisonResult result = chatClient.prompt()
					.system(SKIN_COMPARISON_SYSTEM_PROMPT)
					.user(u -> u.text("첫 번째 이미지는 이전 사진, 두 번째 이미지는 현재 사진이다. 두 사진을 비교해줘.")
							.media(previousMedia, currentMedia))
					.call()
					.entity(AiDto.SkinComparisonResult.class);

			log.info("AI 비교 결과 수신: redness={}, trouble={}", result.redness(), result.trouble());
			return result;
		} catch (Exception e) {
			log.warn("피부 사진 비교 실패(OpenAI 호출 또는 응답 파싱 단계)", e);
			throw new GlobalException(ErrorCode.AI_ANALYSIS_FAILED);
		}
	}
}
