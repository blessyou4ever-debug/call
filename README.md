# CallScheduler (통화 녹음 자동 일정·할 일 관리 안드로이드 앱)

`/storage/emulated/0/Recordings/Call/` 경로에 생성되는 통화 녹음 파일을 자동 감지하여 텍스트로 변환(STT)하고, 약속(`APPOINTMENT`)·할 일(`TODO`)·다시 연락(`CALLBACK`) 일정을 추출해 푸시 알림 확인 후 **구글 캘린더**에 등록하는 Kotlin 네이티브 프로젝트입니다.

## APK 파일 생성 방법 (2가지 중 선택)

### 방법 1. PC에 프로그램 설치 없이 GitHub Actions로 클라우드에서 APK 자동 빌드하기 (추천)
1. 본 압축 파일(`CallScheduler_Android_Project.zip`)의 압축을 풀고 본인의 GitHub 새 저장소(Repository)에 업로드합니다.
2. 프로젝트 내부에 포함된 `.github/workflows/build-apk.yml` 워크플로우가 자동으로 실행되어 클라우드 서버에서 Android SDK와 Gradle을 구성하고 APK를 빌드합니다.
3. GitHub 저장소의 **Actions** 탭 → 완료된 워크플로우 클릭 → 하단 **Artifacts**에서 **`CallScheduler-debug-apk`**(`app-debug.apk`)를 다운로드하여 스마트폰에 바로 설치합니다.

### 방법 2. Android Studio에서 직접 APK 빌드하기
1. Android Studio에서 압축을 푼 `CallSchedulerApp` 폴더를 **Open** 합니다.
2. Gradle Sync가 완료되면 상단 메뉴에서 **Build > Build Bundle(s) / APK(s) > Build APK(s)**를 클릭하거나 터미널에서 `./gradlew assembleDebug`를 실행합니다.
3. `app/build/outputs/apk/debug/app-debug.apk` 경로에 생성된 APK 파일을 스마트폰으로 옮겨 설치합니다.

## 앱 내 주요 기능 및 테스트 방법
- **권한 허용 및 자동 감시 활성화**: 앱 첫 화면의 `[권한 허용 및 자동 감시 활성화]` 버튼을 누르면 `/storage/emulated/0/Recordings/Call/` 폴더 감시 포그라운드 서비스가 시작됩니다.
- **테스트 통화 녹음 분석 실행**: 실제 통화를 하지 않아도 앱 내 `[테스트 통화 녹음 분석 실행 (푸시 알림 체험)]` 버튼을 누르면 샘플 통화(`통화 녹음_김대리_010-1234-5678_261017_174500.m4a`)가 즉시 분석되어 상단 푸시 알림(`[구글 캘린더 등록]`, `[내용 수정]`, `[무시]`)을 바로 체험할 수 있습니다.
- **온디바이스 ↔ 클라우드 전환**: 앱 화면에서 `온디바이스 우선`, `하이브리드(자동 폴백)`, `클라우드 API 고정` 모드를 실시간으로 변경할 수 있습니다.
