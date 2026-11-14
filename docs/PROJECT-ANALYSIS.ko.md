# 프로젝트 분석과 공개본 구성

## 원본 구조

| 원본 영역 | 역할 | 공개본 처리 |
| --- | --- | --- |
| `src/main/java/ai_study_planner` | Java 17·Spring Boot 서버, JPA 엔티티/저장소, JDBC 계획·분석, Gemini/OpenAI 연동, 보안 | 주 소스 유지 |
| `src/main/resources/static` | PC 웹 HTML/CSS/JavaScript | 현재 소스 유지, 백업 파일 제외 |
| `src/test` | JUnit/MockMvc/H2/Mock/브라우저 자동화·합성 fixture | 유지, 누락 SQL fixture 복원·실행 경로 일반화 |
| `database` | 기존 MySQL 스키마와 seed | 계정·기록 혼입 방지를 위해 원본 SQL 제외, 데이터 없는 H2 스키마 별도 제공 |
| `mobile_flutter` | 현재 Android 중심 Flutter UI, API transport, 네이티브 저장 | lib·test·Android 설정·pubspec 유지 |
| `android` | Java Android WebView/Custom Tabs 계열 별도 셸 | Java/리소스/Gradle/테스트 유지 |
| `presentation-demo-*` | 발표용 Spring/H2/PC 웹/PWA 단계별 복제본·검증·산출물 | 일괄 제외 |
| `review-backups` | 작업 백업·인증·TLS·격리 검증 이력 | 일괄 제외. PWA의 필요한 소스 스냅샷만 선별·검사 후 `offline-pwa`로 복사 |
| `build`, `.gradle`, `.idea` | 빌드 결과·캐시·개발환경 | 제외 |
| `poster`, 기존 문서·시작 스크립트 | 전시물·과거 운영 절차·로컬 경로 | 제외, 공개본 문서·안전한 시작 절차 새로 작성 |

## 기능과 실행 경계

서버는 Spring Boot 4.0.6, Gradle 9.5.1, Java toolchain 17을 사용합니다. Spring WebMVC·Data JPA·Security, Jackson, MySQL Connector/J가 선언되어 있습니다. 원본에서 H2는 테스트 런타임에만 있었으므로 공개본은 H2를 실행 런타임 의존성으로 추가했습니다.

PC 웹은 서버의 정적 HTML/CSS/JS이며 별도 프런트엔드 번들러가 필요 없습니다. 상대 URL로 서버 API에 접근합니다. 인증은 인메모리 로그인 소유자와 BCrypt 해시를 사용하며 DB의 `user_id=1`에 매핑됩니다. 사용자 가입 기능으로 확장된 다중 사용자 서비스는 아닙니다.

목표·과목·공부 기록·질문·AI 응답은 JPA, 일부 시간표·계획·요청 원장은 JDBC로 처리됩니다. 초기 H2 스키마에는 일반 관리용 8개 테이블이 있습니다. 요청 중복과 비용 제어용 원장은 선택 기능이며 기본 비활성화합니다. MySQL DDL 검토본은 `docs/migrations`에 유지하되 앱이 자동 실행하지 않습니다.

Flutter 앱은 `mobile_flutter/lib/main.dart`에서 API 주소를 지정하지 않으면 Mock 화면을 사용합니다. 주소가 있으면 `PlannerApi`가 서버 연결·쿠키·CSRF·기록 저장을 처리합니다. Android의 debug 주소 정책·release cleartext 차단은 유지했습니다. Java `android/`는 이 앱과 별도의 웹 연결 셸이며 HTTPS origin을 명시해야 합니다.

전시용 PWA는 기존 브라우저 호환 소스 스냅샷의 조건부 transport와 오프라인 런타임을 보존합니다. Service Worker의 `/ipad/` scope, 버전·해시 기반 precache, Mock 스냅샷 검증과 민감 필드 거부 기능을 유지합니다. 현재 Android 앱 소스와 자동 동기화되는 구성은 아닙니다.

## 공개본에서만 변경한 부분

- `public-local` 기본 프로파일, 전용 H2 파일 DB, 데이터 없는 초기 스키마와 데모 소유자 생성
- 새 로컬 계정 입력·BCrypt 해시 생성·재실행 시 덮어쓰기 거부
- `127.0.0.1:8083` 기본 설정, 포트 점유 시 기존 프로세스 보존
- public-local에서만 로컬 HTTP용 세션 쿠키 설정. 다른 환경의 Secure 기본값 유지
- AI 생성 요청을 기본 차단하는 프로파일 한정 필터와 환경변수 기반 명시적 opt-in
- 실행 JAR에 민감정보 없는 공개 설정 포함, H2 런타임 포함
- H2와 Connector/J Mock의 테스트 classpath 분리, 테스트별 JVM 격리, 외부 설정 제거
- 누락된 두 원장 SQL fixture 복원, Gemini 정상 완료 응답의 최신 계약에 맞춘 합성 fixture·검증 갱신
- 사용자별 절대 경로를 표준 의존성·SDK 환경변수로 교체

원본 기능의 구현을 삭제하지 않았습니다. 공개 기본값으로 AI와 선택 원장을 끄며, 실제 사용 데이터와 실행 환경을 공개본에 이식하지 않습니다.
