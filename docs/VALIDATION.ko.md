# 최종 검증 결과

검증일: 2026-11-14 (Asia/Seoul). 최초 공개 준비, 첫 배포 commit의 원격 CI, GitHub 새 clone 실행 검증을 구분해 기록합니다. 이번 문서·화면 문구 수정본은 아직 commit·push하지 않았습니다.

## 최초 공개 준비 검증

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

## 첫 배포 commit의 GitHub Actions

대상은 `9f95db3f6929e8cb004c0f66dbd058626119a7f7`입니다. [Offline-safe checks #1](https://github.com/cafrijo-ux/ai-study-planner/actions/runs/43566686109)이 성공으로 완료됐습니다.

- 새 Windows 러너의 JDK 17에서 Gradle `test`, `connectorTest`, `bootJar` 성공
- Node 의존성·Chromium 설치 후 웹 검사 40+44개, PWA 단위 검사 13개 통과
- Android APK·실기기·실제 MySQL·유료 AI를 실행한 CI는 아님

위 원격 CI 결과는 첫 배포 commit의 결과입니다. 현재 로컬 문구 수정본은 원격 Actions에서 아직 검증하지 않았습니다.

## GitHub 새 clone의 제3자 실행 검증

같은 첫 배포 commit을 원본 프로젝트 및 github-public-ready 밖의 새 임시 폴더에 **GitHub에서 직접 clone**했습니다. 기존 소스·JAR·DB를 복사하지 않았으며, 공백이 있는 경로와 빈 전용 Gradle 캐시를 사용했습니다.

| 검증 항목 | 결과 |
| --- | --- |
| Java·javac 17.0.20.1, Windows PowerShell 5.1 | 확인 |
| README의 `gradlew.bat --no-daemon bootJar` | 의존성 최초 다운로드부터 빌드 성공, 47초 |
| README 최초 계정 스크립트 | 새 합성 계정·BCrypt cost 12 해시 생성 성공 |
| README 실행 스크립트 | 127.0.0.1:18087에서 새 JAR·새 H2로 시작 |
| 실제 웹 로그인·빈 H2 확인 | 통과 |
| 웹 폼의 목표·필수 과목 등록 | 통과 |
| 공부 기록 저장·목록 조회 | 합성 기록 1개·40분 저장·표시 확인 |
| 시간 설정·계획 저장·월간 달력 | 저장 계획의 API 조회·화면 표시 확인 |
| API 키 없는 분석 | 정상 응답, AI ready=false·demoMode=true |
| AI 생성 기본 차단 | 일반 코칭·요약·주간 코칭·질문 생성이 503/not_called 반환 |
| 정상 종료·재시작·재로그인 | 통과 |
| 재시작 후 데이터 보존 | 목표·과목·공부 기록·월간 계획 응답 전체 및 계정 파일 해시 동일 |
| 계정·DB·빌드 결과 Git 제외 | 추적 파일 376개 유지, Git 작업 폴더 변경 없음 |

최종 검증 묶음 **11개 모두 통과**, 제품 기능 실패와 브라우저 JavaScript 오류는 0건입니다. 합성 계정·기록만 사용했으며 사용자 계정·개인 학습 기록은 가져오지 않았습니다.

안전한 검증을 위해 기존 API·Spring·DB 환경변수를 전달하지 않았습니다. 계정 입력은 검증 드라이버로 자동화하고 PowerShell 실행 정책은 검증 프로세스 범위에서만 조정했습니다. clone 밖의 Java 17 agent로 모든 서버 외부 연결을 차단했습니다. 첫 실행과 재시작 모두 AI 제공자 접속 검사·소켓 연결 시도·성공한 외부 연결은 0건이었고, JVM 초기화의 DNS 권한 검사 2건씩은 차단됐습니다. 브라우저 외부 origin 요청도 0건입니다. 실제 API를 호출한 검증은 아닙니다.

검증에서 시작한 18087 서버만 정상 종료했습니다. 기존 8083 상태, 원본 파일·DB·API 키는 유지했습니다. 당시 저장소는 Private이므로 접근 권한이 있는 인증으로 clone했으며, 익명 접근 가능성을 확인한 검증은 아닙니다.

## 이번 문서·화면 문구 수정의 회귀 검사

DB 종류를 특정하던 저장 버튼·진행 상태·오류 안내·사용자용 프롬프트 미리보기 라벨을 일반화했습니다. SQL, API 경로, 데이터 구조, 저장·계산·AI 호출 로직, 내부 모드 식별자는 변경하지 않았습니다. 수정한 제품 코드 7개와 기존 Flutter 테스트 2개를 Git 기준본과 비교해 지정한 표시 문자열 치환만 있는지 확인했습니다. README의 PC 화면 이미지도 갱신된 Mock 화면으로 교체했습니다.

| 이번 수정본의 검사 | 결과 |
| --- | --- |
| 계획 저장·학습 계획·AI Mock Java 회귀 | 169개 통과, 실패·건너뜀 0 |
| 변경 서버 소스·정적 리소스 JAR 빌드 | `bootJar` 성공 |
| PC 달력·최종 화면 Playwright 회귀 | 40+44개 통과, 외부 요청 0 |
| Flutter Android 계획·달력·화면 관련 회귀 | 66개 통과 |
| 전시 PWA Flutter 계획·달력·화면 관련 회귀 | 66개 통과 |
| Gitleaks·자체 민감정보 검사 | 발견 0건 |
| 공개 파일 목록·체크섬 | 376개 파일, 체크섬 파일 자체를 제외한 375개 해시 일치 |

실제 DB·유료 AI·실기기와 연결하지 않는 기존 Mock·위젯 테스트입니다. 전체 Java/Flutter 테스트를 다시 실행한 결과나 현재 수정본의 GitHub Actions 결과로 표현하지 않습니다. 이번 수정본은 아직 commit·push하지 않았습니다.

## 검증 환경과 재현 범위

- JDK 17.0.20.1, Gradle 9.5.1, Spring Boot 4.0.6
- Flutter 3.47.8, Dart 3.13.7
- Playwright 1.62.1, 설치된 Microsoft Edge headless
- Windows PowerShell 실행 스크립트와 PowerShell 구문 확인
- 최초 준비의 단위·회귀 검사는 별도 복사 캐시로 수행. 새 clone 검증은 빈 전용 캐시에서 의존성 다운로드부터 수행. 캐시는 공개 파일에 포함하지 않음

Android 앱과 PWA의 소스 분리는 의도적입니다. Java 서버 검사는 현재 Android용 합성 계약 fixture를 새로 생성하며 완료 메타데이터를 검사합니다. 과거 PWA 소스는 해당 스냅샷의 계약 fixture와 테스트를 유지합니다.

## 아직 검증하지 않은 항목

- OS·JDK 설치부터 시작한 완전히 새 Windows PC의 전체 절차. JDK가 설치된 Windows에서 새 clone·빈 캐시·최초 의존성 다운로드는 검증 완료
- 실제 Gemini/OpenAI 인증·유료 호출·현재 모델 제공 여부·응답 품질·청구 금액
- 실제 MySQL 인스턴스 연결·DDL 적용·기존 데이터 이식
- Flutter Android APK 빌드·설치·실기기·에뮬레이터·USB/Wi-Fi 서버 연결
- 별도 Java Android 셸의 이번 상태에 대한 Gradle 빌드·Lint·TWA 관계 검증
- iOS/Safari의 PWA 설치·캐시 저장 용량·운영체제별 오프라인 유지 정책
- 외부 HTTPS 도메인·TLS·리버스 프록시·운영 다중 사용자 서비스

첫 배포 commit은 사용자 승인 후 Private 저장소에 업로드됐습니다. 새 clone 검증과 이번 문구 정리에서는 추가 commit·push·공개 전환을 수행하지 않았습니다. 원본 API 키 변경·전송, 원본 MySQL/H2 초기화·삭제도 수행하지 않았습니다.
