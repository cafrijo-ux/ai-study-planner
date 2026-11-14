# 별도 Java Android 브라우저 셸

이 디렉터리는 mobile_flutter 앱과 별도인 AndroidX Browser TWA / Custom Tab 셸입니다. 내장 WebView나 JavaScript 브리지, API 키·DB 계정은 포함하지 않습니다. 승인된 HTTPS origin을 명시하지 않으면 연결 버튼이 비활성화됩니다.

JDK 17, Android SDK(API 35), Gradle Wrapper 8.11.1, Android Gradle Plugin 8.9.2가 필요합니다. 환경에 맞게 JAVA_HOME과 ANDROID_HOME을 설정하거나 Android Studio에서 SDK를 선택하세요. local.properties는 Git에 추가하지 않습니다.

빌드·테스트 명령: 이 디렉터리에서 PowerShell로 gradlew.bat --no-daemon assembleDebug testDebugUnitTest lintDebug를 실행합니다. 디버그 APK 경로는 app/build/outputs/apk/debug/app-debug.apk입니다.

HTTPS origin을 구성할 때는 -PplannerOrigin=https://example.com처럼 빌드에 전달합니다. 실제 사용할 도메인과 서버, 앱 서명, 웹의 Digital Asset Links를 별도로 준비해야 합니다. HTTP·localhost·IP 주소를 허용하도록 검증을 우회하지 않습니다. PC 웹 127.0.0.1:8083에 자동 연결되는 앱이 아닙니다.

TWA/Custom Tab은 기기 브라우저의 세션을 사용하며 PC의 로그인과 공유하지 않습니다. 앱은 쿠키를 직접 읽지 않습니다. 네트워크 실패·상태 코드·시간 초과·늦은 응답을 처리하는 소스와 JVM 테스트를 포함합니다.

이번 공개본 준비에서는 이 셸의 Android 빌드·Lint·실기기 동작·외부 HTTPS를 새로 검증하지 않았습니다. 저장소 루트의 검증 보고서를 확인하세요. 릴리스 서명키·스토어 등록·도메인 게시도 수행하지 않았습니다.
