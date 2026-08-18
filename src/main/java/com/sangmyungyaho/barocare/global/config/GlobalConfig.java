package com.sangmyungyaho.barocare.global.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 전역 설정.
 * 로컬 디스크에 저장된 업로드 파일을 정적 리소스로 서빙하기 위한 핸들러를 등록한다.
 * TODO: S3 등 외부 스토리지로 교체되면 이 리소스 핸들러는 제거한다.
 */
@Configuration
public class GlobalConfig implements WebMvcConfigurer {

	@Value("${app.storage.local.base-dir:uploads}")
	private String baseDir;

	@Value("${app.storage.local.url-prefix:/uploads}")
	private String urlPrefix;

	@Override
	public void addResourceHandlers(ResourceHandlerRegistry registry) {
		registry.addResourceHandler(urlPrefix + "/**")
				.addResourceLocations("file:" + baseDir + "/");
	}
}
