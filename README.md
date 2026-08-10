# 바로케어 Backend

생활습관 데이터와 피부 상태를 기반으로 피부 변화 원인을 분석하고 맞춤형 루틴을 제공하는 **바로케어 Backend** 레포지토리입니다.

## 🛠 기술 스택

- Java 17
- Spring Boot
- Spring Data JPA
- MySQL
- Spring AI
- OpenAI API
- Gradle

## 🏃 빠른 시작

### 사전 요구사항

- Java 17
- MySQL

### 데이터베이스 생성

```sql
CREATE DATABASE barocare
CHARACTER SET utf8mb4
COLLATE utf8mb4_unicode_ci;
```

### 환경변수

```env
DB_PASSWORD=
OPENAI_API_KEY=
```

`.env` 파일은 Git에 포함하지 않습니다.

### 실행

IntelliJ의 Run Configuration에 필요한 환경변수를 등록한 후 실행합니다.

또는 터미널에서:

```bash
./gradlew bootRun
```

기본 서버 주소:

```text
http://localhost:8080
```

## 📁 디렉터리 구조

도메인 중심 패키지 구조를 사용합니다.

```text
src/main/java/com/sangmyungyaho/barocare/
├── global/                 # 공통 설정 및 전역 기능
├── auth/                   # 인증 및 인가
├── user/                   # 사용자
├── checkin/                # 데일리 체크인
├── skin/                   # 피부 촬영 및 분석
├── report/                 # 분석 리포트
├── routine/                # 맞춤형 루틴
└── ai/                     # AI 연동 및 분석
```

각 도메인은 필요에 따라 Controller, Service, Repository, Entity, DTO 등의 계층으로 구성합니다.

## 🌿 Git 협업 전략

- `main` : 배포용 브랜치
- `dev` : 개발 메인 브랜치
- `feat/[기능명]` : 기능 개발
- `fix/[수정내용]` : 버그 수정
- `chore/[작업내용]` : 설정 및 환경 구성

### 작업 순서

1. `dev`에서 작업 브랜치를 생성합니다.
2. 작업 브랜치에서 기능을 개발합니다.
3. 작업 완료 후 `dev` 대상으로 Pull Request를 생성합니다.
4. 코드 리뷰 후 `dev`에 병합합니다.
5. 배포 시 `dev` → `main` Pull Request를 생성합니다.

📋 Commit Message Convention

Gitmoji	Tag	Description
✨	feat	새로운 기능 추가
🐛	fix	버그 수정
📝	docs	문서 추가, 수정, 삭제
✅	test	테스트 코드 추가, 수정, 삭제
💄	style	코드 형식 변경
♻️	refactor	코드 리팩토링
⚡️	perf	성능 개선
💚	ci	CI 관련 설정 수정
🚀	chore	기타 변경사항
🔥️	remove	코드 및 파일 제거


### 예시

```text
feat: 체크인 저장 API 구현
fix: 리포트 조회 오류 수정
docs: README 수정
refactor: 피부 분석 로직 리팩터링
test: 체크인 서비스 테스트 추가
chore: 프로젝트 환경 설정
```