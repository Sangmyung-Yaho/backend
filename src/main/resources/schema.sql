-- 사용자의 첫 피부 분석(비교할 이전 분석이 없음)에서는 previousSkinAnalysis와 그 파생값(이전 점수)이
-- 전부 null이어야 정상이다 - Report 엔티티는 이미 그렇게 매핑돼 있다(previousSkinAnalysis,
-- rednessPreviousScore, troublePreviousScore 모두 nullable, redness/troubleStatus도 마찬가지).
--
-- 이 프로젝트는 별도 마이그레이션 도구 없이 spring.jpa.hibernate.ddl-auto=update로만 스키마를
-- 관리하는데, update 모드는 새로 생성되는 컬럼은 엔티티 매핑대로(nullable) 만들어주지만 이미
-- NOT NULL로 만들어져 있던 기존 컬럼의 제약은 스스로 완화하지 못한다. 그래서 이 컬럼들이 매핑 변경
-- 이전에 먼저 생성된 환경(로컬/공유 개발 DB 등)은 계속 NOT NULL로 남아, 첫 피부 분석 시 리포트 저장이
-- DB 제약 위반으로 실패한다.
--
-- application.yaml의 spring.jpa.defer-datasource-initialization=true 설정으로 이 스크립트는
-- Hibernate가 테이블을 만든/갱신한 "다음"에 매 기동마다 실행된다 - 완전히 새 환경(테이블이 이제 막
-- 생성됨)이든, 이미 존재하던 환경(컬럼이 NOT NULL로 남아있음)이든 동일한 결과로 수렴시키기 위함이다.
-- 이미 nullable인 컬럼에 다시 실행해도 안전하다(멱등 - 오류나 데이터 손실 없음).
--
-- MySQL 전용 문법(MODIFY COLUMN)이라 report/repository/ReportRepositoryTest(@DataJpaTest, H2 사용)는
-- application.properties의 spring.sql.init.mode=never로 이 스크립트 실행 자체를 꺼둔다.
ALTER TABLE report MODIFY COLUMN previous_skin_analysis_id BIGINT NULL;
ALTER TABLE report MODIFY COLUMN redness_previous_score INT NULL;
ALTER TABLE report MODIFY COLUMN trouble_previous_score INT NULL;
