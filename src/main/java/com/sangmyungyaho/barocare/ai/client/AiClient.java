package com.sangmyungyaho.barocare.ai.client;

import com.sangmyungyaho.barocare.ai.dto.AiDto;
import com.sangmyungyaho.barocare.global.exception.ErrorCode;
import com.sangmyungyaho.barocare.global.exception.GlobalException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.sangmyungyaho.barocare.skin.entity.SkinAnalysisLevel;
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

	/**
	 * 피부 변화 원인 분석(REP-101) 프롬프트 버전. 이 프롬프트를 수정하면 함께 올린다.
	 * v4: summary의 역할을 "리포트 전체 요약"에서 "원인 요인 설명 한 문장"으로 좁혔다. 붉은기/트러블의
	 * 변화 문구·전체 평가 문구는 이제 ReportService가 rednessStatus/troubleStatus로 결정적으로 조립한다
	 * (AI 프롬프트가 IMPROVED/WORSENED/POOR 같은 내부 enum 토큰을 그대로 노출하던 문제의 근본 수정 - 자세한
	 * 내용은 ReportService#buildSummary 참고).
	 */
	public static final String CAUSE_ANALYSIS_SCHEMA_VERSION = "v4-cause-analysis";

	/**
	 * 피부 변화 원인 후보 "설명 생성" 프롬프트.
	 * 중요(역할 분리): 어떤 요인이 원인 "후보"인지는 이미 백엔드(LifestyleFactorRubric)가 candidateFactors로
	 * 확정해서 넘긴다. GPT는 그 후보를 뒤집거나 새 후보를 추가하는 판정자가 아니라, 이미 확정된 후보에 대해
	 * 사용자에게 보여줄 자연어 설명을 쓰는 역할만 한다. redness/trouble의 점수·변화량·상태·변화방향도
	 * 전부 Java가 계산해 전달하며, GPT는 이 값을 다시 계산하지 않는다.
	 *
	 * summary는 더 이상 리포트 전체를 요약하지 않는다 - "원인 요인" 설명 한 문장만 작성하며, 붉은기/트러블
	 * 변화 문구는 ReportService가 별도로 붙인다(중복 노출 방지). ReportService가 최종 방어선으로
	 * IMPROVED/POOR 같은 영어 상태값 리터럴이 응답에 섞여 있으면 AI_ANALYSIS_FAILED로 거부하므로, 이 프롬프트를
	 * 수정할 때도 "출력에 영어 상태값을 쓰지 않는다" 원칙은 유지해야 한다.
	 */
	private static final String CAUSE_ANALYSIS_SYSTEM_PROMPT = """
			너는 사용자의 피부 변화 분석 결과와 생활습관 체크인 데이터를 보고, 이미 확정된 원인 후보를
			자연스러운 한국어 문장으로 설명하는 도구다. 어떤 요인이 원인인지 "판정"하는 것은 네 역할이 아니다 -
			그 판정은 이미 백엔드가 끝냈고, 너는 그 결과를 문장으로 풀어 설명하는 역할만 한다.

			[절대 규칙] 다음은 백엔드 내부에서만 쓰는 영어 상태값이다. 이 단어들을 대소문자 어떤 형태로도
			문장에 그대로 쓰지 마라:
			IMPROVED, WORSENED, UNCHANGED, INCREASED, DECREASED, STABLE, GOOD, MODERATE, POOR, SAFE, CAUTION, DANGER
			이 값이 뜻하는 내용(예: 수면 판정이 POOR)은 raw 수치나 자연스러운 한국어로 풀어써라
			(예: "최근 평균보다 수면 시간이 짧았어요"). "낮음", "보통", "높음" 같은 등급 라벨도 화면에 이미
			별도로 표시되는 정보이니 반복하지 마라.

			다음 원칙을 반드시 지켜라.
			- redness/trouble의 점수, 변화량, 상태, baseline 대비 정보는 참고용 컨텍스트일 뿐이다. 붉은기/트러블이
			  어떻게 변했는지에 대한 문장은 백엔드가 별도로 작성하므로, 네 출력에는 다시 등장시키지 않는다
			  (같은 정보를 두 번 말하지 않는다) - 너는 오직 "원인 요인 설명"만 작성한다.
			- 수면/스트레스/수분 섭취의 판정(sleepLevel/stressLevel/waterLevel)도 이미 계산되어 주어진 값이다.
			  너는 raw 수치(시간/지수/ml)만 보고 이 판정을 다시 매기거나 뒤집지 않는다.
			- causes(원인 후보 목록)는 candidateFactors에 주어진 요인으로만 작성한다. candidateFactors는 백엔드가
			  "원인일 가능성이 높다"고 이미 확정한 목록이다. 이 목록에 없는 요인은 causes에 절대 추가하지 마라 -
			  네가 추가해도 백엔드가 다시 걸러내 최종 응답에는 반영되지 않는다. candidateFactors가 비어 있으면
			  causes도 반드시 빈 목록으로 반환하라.
			- 피부 점수(숫자)를 새로 만들어내지 않는다. 네 응답에는 점수 필드 자체가 없다.
			- 각 원인 후보의 name은 "수면 부족"처럼 짧은 한국어 라벨, description은 해당 체크인 값(최근값/평균)을
			  근거로 1문장으로 자연스럽게 설명한다. "~영향을 준 것으로 보여요"처럼 완곡하게 표현하고
			  "때문에"처럼 단정적인 인과관계 표현은 쓰지 않는다.
			- summary는 candidateFactors를 근거로 "원인 요인" 설명만 담은 한 문장으로 작성한다.
			  예: "최근 기록에서는 스트레스와 수분 섭취가 피부 변화에 영향을 준 주요 요인으로 보여요."
			  candidateFactors가 비어 있으면 "최근 기록에서는 특별히 두드러진 위험 요인은 보이지 않아요." 같은
			  취지로 작성한다(원인을 억지로 만들어내지 않는다).
			- 질환명 또는 의학적 진단을 생성하지 않는다.
			""";

	/**
	 * 추천 성분(ISSUE-30) 프롬프트 버전. 이 프롬프트를 수정하면 함께 올린다.
	 * v2: 프롬프트가 CAUTION/DANGER/SAFE 등급을 조건문처럼 그대로 보여주며 참고하라고 지시하던 부분이,
	 * GPT가 reason에 그 영어 토큰을 그대로 베껴 쓰는 원인이었다(REP-101 원인 분석 프롬프트에서 있었던 것과
	 * 동일한 문제). 등급을 조건으로 쓰는 지시는 유지하되 "이 단어를 출력에 쓰지 마라"를 명시했고,
	 * IngredientRecommendationService에도 같은 방식의 후처리 검증을 추가했다.
	 */
	public static final String INGREDIENT_RECOMMENDATION_SCHEMA_VERSION = "v2-ingredient-recommendation";

	/**
	 * 피부 상태 기반 "화장품 성분 추천" 프롬프트.
	 * 중요: 제품명은 여기서 다루지 않는다(제품 매칭은 ProductSearchClient의 실시간 웹 검색이 담당) -
	 * 이 프롬프트는 오직 성분명과 그 이유만 반환한다.
	 */
	private static final String INGREDIENT_RECOMMENDATION_SYSTEM_PROMPT = """
			너는 사용자의 피부 분석 결과(붉은기/트러블/종합 등급)를 보고, 지금 피부 상태 관리에 도움이 될 수
			있는 화장품 성분을 추천하는 도구다.

			[절대 규칙] 등급을 나타내는 다음 영어 단어들은 백엔드 내부에서만 쓰는 상태값이다. 이 단어들을
			대소문자 어떤 형태로도 reason 문장에 그대로 쓰지 마라: SAFE, CAUTION, DANGER, GOOD, MODERATE, POOR,
			IMPROVED, WORSENED, UNCHANGED, INCREASED, DECREASED, STABLE. 등급 자체를 언급하고 싶으면
			"자극에 예민한 상태", "붉은기가 있는 상태"처럼 자연스러운 한국어로 풀어써라.

			다음 원칙을 반드시 지켜라.
			- 질환명 또는 의학적 진단·치료 효과를 단정하지 않는다("치료한다", "낫는다", "완치" 같은 표현 금지).
			- "관리에 도움을 줄 수 있다", "완화에 도움이 될 수 있다" 수준의 피부 관리(스킨케어) 관점으로만 서술한다.
			- 실제로 화장품에 흔히 쓰이는 성분명만 추천한다(존재하지 않는 성분을 지어내지 않는다).
			- 붉은기/트러블 등급이 CAUTION 또는 DANGER면 해당 부위 관리에 도움이 되는 성분을 우선하고,
			  전반적으로 SAFE면 진정·보습 등 순한 성분 위주로 추천한다(이건 너의 판단 기준일 뿐, 이 단어들을
			  reason에 그대로 옮겨 쓰라는 뜻이 아니다).
			- 성분은 2~4개만 추천한다.
			- 각 성분의 reason은 1문장으로, 왜 지금 피부 상태에 이 성분이 맞는지 설명한다.
			- name과 reason은 사용자에게 그대로 노출되는 값이므로 반드시 한국어로 작성한다. name은 영문
			  성분명이 아니라 국내 화장품 성분표에서 흔히 쓰이는 한글 성분명을 쓴다(예: "판테놀",
			  "나이아신아마이드", "센텔라아시아티카 추출물").
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

	/**
	 * 이미 계산된 피부 변화 결과와 체크인 데이터를 근거로 원인 후보 해석과 요약을 받는다.
	 * redness/trouble 점수·변화량·상태는 여기서 다시 계산하지 않고 입력값을 그대로 GPT에 전달한다.
	 *
	 * @param skinChange 이미 계산된 redness/trouble 변화 결과(점수 아님, 변화량과 상태만)
	 * @param checkin    최근 체크인 값과 그 이전 체크인들의 평균(비교 대상이 없으면 average*는 null)
	 * @return AI가 해석한 원인 후보 목록과 요약
	 * @throws GlobalException AI_ANALYSIS_FAILED - API 호출 실패 또는 응답 파싱/형식 오류 시
	 */
	public AiDto.CauseAnalysisResult analyzeSkinChangeCauses(AiDto.SkinChangeInput skinChange, AiDto.CheckinInput checkin) {
		try {
			AiDto.CauseAnalysisResult result = chatClient.prompt()
					.system(CAUSE_ANALYSIS_SYSTEM_PROMPT)
					.user(buildCauseAnalysisUserText(skinChange, checkin))
					.call()
					.entity(AiDto.CauseAnalysisResult.class);

			log.info("AI 원인 분석 결과 수신: causes={}, summary={}", result.causes(), result.summary());
			return result;
		} catch (Exception e) {
			log.warn("피부 변화 원인 분석 실패(OpenAI 호출 또는 응답 파싱 단계)", e);
			throw new GlobalException(ErrorCode.AI_ANALYSIS_FAILED);
		}
	}

	private String buildCauseAnalysisUserText(AiDto.SkinChangeInput skinChange, AiDto.CheckinInput checkin) {
		return """
				[피부 변화 결과 - 이미 계산된 값, 다시 계산하지 말 것. 첫 피부 분석이라 비교 대상이 없으면 "정보 없음"으로 표시됨]
				- redness(붉은기) 변화량: %s, 상태: %s, 변화 방향: %s
				- trouble(트러블) 변화량: %s, 상태: %s, 변화 방향: %s
				- baseline(최초 분석) 대비: %s (baseline 등급 참고용 - redness: %s, trouble: %s)

				[생활습관 요인 판정 - 이미 계산된 값, 다시 계산하지 말 것. 판정 기준: %s]
				- 수면 판정: %s (참고용 raw 수치 - 최근: %s시간, 이전 평균: %s시간)
				- 스트레스 판정: %s (참고용 raw 수치 - 최근: %s, 이전 평균: %s)
				- 수분 섭취 판정: %s (참고용 raw 수치 - 최근: %sml, 이전 평균: %sml, 목표 대비 판정이므로 절대량만으로 다시 판단하지 말 것)

				[주요 위험 요인 후보 - 백엔드가 이미 확정함, causes는 반드시 이 목록 안에서만 작성할 것]
				- candidateFactors: %s

				위 데이터를 바탕으로 candidateFactors에 대한 원인 설명과 전체 요약을 작성해줘.
				""".formatted(
				formatNullable(skinChange.rednessChange()), formatNullable(skinChange.rednessStatus()), formatNullable(skinChange.rednessDirection()),
				formatNullable(skinChange.troubleChange()), formatNullable(skinChange.troubleStatus()), formatNullable(skinChange.troubleDirection()),
				skinChange.comparedAgainstBaseline() ? "직전 분석이 곧 baseline(두 번째 분석)" : "baseline은 직전 분석보다 더 이전 시점",
				formatNullable(skinChange.baselineRednessLevel()), formatNullable(skinChange.baselineTroubleLevel()),
				checkin.personalBaselineUsed() ? "개인 기준선(최근 7일 평균)" : "고정 기준표(체크인 이력 부족)",
				checkin.sleepLevel(), checkin.latestSleepHours(), formatNullable(checkin.averageSleepHours()),
				checkin.stressLevel(), checkin.latestStressLevel(), formatNullable(checkin.averageStressLevel()),
				checkin.waterLevel(), checkin.latestWaterIntakeMl(), formatNullable(checkin.averageWaterIntakeMl()),
				checkin.candidateFactors().isEmpty() ? "없음(뚜렷한 위험 요인 없음)" : checkin.candidateFactors()
		);
	}

	private String formatNullable(Double value) {
		return value == null ? "비교할 이전 기록 없음" : value.toString();
	}

	private String formatNullable(Object value) {
		return value == null ? "정보 없음" : value.toString();
	}

	/**
	 * SkinAnalysis의 등급(붉은기/트러블/종합)만 보고 화장품 성분을 추천받는다. 제품명은 다루지 않는다
	 * (제품 매칭은 ProductSearchClient의 실시간 웹 검색이 별도로 담당한다).
	 *
	 * @throws GlobalException AI_ANALYSIS_FAILED - API 호출 실패 또는 응답 파싱/형식 오류 시
	 */
	public AiDto.IngredientRecommendationResult recommendIngredients(
			SkinAnalysisLevel rednessLevel, SkinAnalysisLevel troubleLevel, SkinAnalysisLevel skinLevel
	) {
		try {
			AiDto.IngredientRecommendationResult result = chatClient.prompt()
					.system(INGREDIENT_RECOMMENDATION_SYSTEM_PROMPT)
					.user("붉은기 등급: %s, 트러블 등급: %s, 종합 피부 등급: %s\n위 피부 분석 결과를 참고해서 지금 상태에 도움이 될 수 있는 화장품 성분을 추천해줘."
							.formatted(rednessLevel, troubleLevel, skinLevel))
					.call()
					.entity(AiDto.IngredientRecommendationResult.class);

			log.info("AI 성분 추천 결과 수신: ingredients={}", result.ingredients());
			return result;
		} catch (Exception e) {
			log.warn("성분 추천 AI 호출 실패(OpenAI 호출 또는 응답 파싱 단계)", e);
			throw new GlobalException(ErrorCode.AI_ANALYSIS_FAILED);
		}
	}
}
