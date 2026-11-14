# 최종 검증 결과

검증일: 2026-11-14 (Asia/Seoul). 이 보고서는 별도 공개 준비 복사본을 대상으로 합니다.

| 검사 | 결과 |
| --- | --- |
| Java / Spring / H2 / Mock / 보안·계획·기록 회귀 | `test` 708개 중 707 통과, 1 건너뜀, 실패 0 |
| Connector/J 내부 오프라인 Mock | `connectorTest` 3 통과, 실패 0 |
| 실행 JAR 패키징 | `bootJar` 성공, H2·공개 안전 설정 포함 |
| Flutter Android 소스 자동화 | 327 통과 |
| 전시 PWA Flutter 소스 자동화 | 352 통과 |
| PC 웹 달력 Playwright | 40 통과, 외부 페이지 요청 0 |
| PC 웹 최종 Playwright | 44 통과, 외부 페이지 요청 0 |
| PWA 캐시·Service Worker Node 단위 검사 | 13 통과 |
| PWA 웹 빌드·precache | 성공, 40개 정적 파일, `/ipad/` scope |
| PWA 실제 브라우저 오프라인 새로고침 | 통과, shellReady=true, 외부 요청 0 |
| 최초 계정 PowerShell 스크립트 | 무작위 임시 비밀번호로 BCrypt 해시 생성 통과 |
| Windows PowerShell 시작 스크립트 | 별도 18084 포트·새 공개본 H2·AI 차단 확인 |
| 공개본 자체 민감정보 패턴 검사 | 발견 0 |
| 원본 보존 | 복사 시 기록한 원본 326개 파일 해시 비교, 변경 0 |

Java의 건너뛴 1개는 `question.browser.enabled=true`로 별도 opt-in해야 하는 실제 HTTP 브라우저용 검사입니다. 별도 Playwright Mock 검사와 아래 실행 JAR HTTP 검증은 수행했습니다.

## 실행 JAR 검증

개인 설정과 API 환경변수를 전달하지 않은 새 검증 폴더에서 JAR을 시작했습니다. 원본 8083 환경을 사용하지 않고 127.0.0.1:18083과 새 H2 파일 DB를 사용했습니다.

1. 실행 JAR 시작·로그인 페이지 응답
2. CSRF 발급과 실제 폼 로그인·세션 인증
3. 새 H2에 개인 목표가 없음을 확인
4. 합성 목표·과목 등록과 조회
5. 합성 공부 기록 저장
6. API 키 없는 분석 조회
7. 일반 코칭·기록 요약·주간 코칭·질문 생성 경로의 기본 차단
8. 검증 서버 종료·재시작 후 목표·공부 기록 유지

모두 통과했습니다. 원본 DB 연결 0, 실제 AI 호출 0입니다. 검증 중 시작한 프로세스만 종료했으며 기존 실행 환경을 중단하지 않았습니다. 공개본 기본 설정은 `127.0.0.1:8083`을 유지합니다.

## 검증 환경과 재현 범위

- JDK 17.0.20, Gradle 9.5.1, Spring Boot 4.0.6
- Flutter 3.47.8, Dart 3.13.7
- Playwright 1.62.1, 설치된 Microsoft Edge headless
- Windows PowerShell 실행 스크립트와 PowerShell 구문 확인
- 의존성은 별도 복사 캐시를 사용해 오프라인 빌드·테스트. 공개 패키지에는 캐시를 포함하지 않음

Android 앱과 PWA의 소스 분리는 의도적입니다. Java 서버 검사는 현재 Android용 합성 계약 fixture를 새로 생성하며 완료 메타데이터를 검사합니다. 과거 PWA 소스는 해당 스냅샷의 계약 fixture와 테스트를 유지합니다.

## 아직 검증하지 않은 항목

- 완전히 새 Windows PC에서 도구 설치와 의존성 최초 다운로드부터 진행한 검증
- 실제 Gemini/OpenAI 인증·유료 호출·현재 모델 제공 여부·응답 품질·청구 금액
- 실제 MySQL 인스턴스 연결·DDL 적용·기존 데이터 이식
- Flutter Android APK 빌드·설치·실기기·에뮬레이터·USB/Wi-Fi 서버 연결
- 별도 Java Android 셸의 이번 상태에 대한 Gradle 빌드·Lint·TWA 관계 검증
- iOS/Safari의 PWA 설치·캐시 저장 용량·운영체제별 오프라인 유지 정책
- 외부 HTTPS 도메인·TLS·리버스 프록시·운영 다중 사용자 서비스
- GitHub Actions 원격 실행과 실제 clone(업로드 전이므로 수행 불가)

GitHub 저장소 생성·commit·push·공개 전환은 수행하지 않았습니다. 원본 API 키 변경·전송, 원본 MySQL/H2 초기화·삭제도 수행하지 않았습니다.
