package com.sangmyungyaho.barocare.skin.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sangmyungyaho.barocare.skin.entity.SkinImage;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

public class SkinImageDto {

	@Schema(name = "SkinImageResponse")
	public record Response(
			@Schema(description = "피부 이미지 ID", example = "55")
			@JsonProperty("skin_image_id")
			Long skinImageId,

			@Schema(description = "업로드된 이미지 접근 URL", example = "https://example.com/skin-images/55.jpg")
			@JsonProperty("image_url")
			String imageUrl,

			@Schema(description = "업로드 일시", example = "2026-08-10T09:41:00Z")
			@JsonProperty("created_at")
			Instant createdAt
	) {

		public static Response from(SkinImage skinImage) {
			return new Response(
					skinImage.getId(),
					skinImage.getImageUrl(),
					skinImage.getCreatedAt()
			);
		}
	}
}
