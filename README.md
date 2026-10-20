# CallScheduler 4.0 — 실제 로컬 모델 빌드 프로젝트

`blessyou4ever-debug/call_r1`의 v3.4 소스(9e882d5707951a206e85b8bae4ecb49cc13f5392)를 바탕으로 수정한 개인용 Android 앱입니다. APK 대신 소스·Gradle Wrapper·GitHub Actions를 제공합니다.

## 주요 변경

- Android SpeechRecognizer를 실제 whisper.cpp 1.7.6 JNI 엔진으로 교체. 다국어 Whisper base/small `.bin`을 사용합니다.
- 정규식 일정 추출기를 MediaPipe 0.10.27 + Gemma 3 1B `.task` 추론으로 교체합니다. MediaPipe는 유지보수 모드이지만 이 프로젝트는 기존 호환 `.task` 모델에 맞춰 버전을 고정했습니다.
- 로컬 / 혼합 / 외부 세 모드. 로컬 실패 시 외부로 자동 전송하지 않습니다. 이전 AUTO_FALLBACK 저장값은 이제 혼합(전사문만 외부 전송)입니다. 설치 후 원하는 모드를 다시 확인하세요.
- PCM을 스트리밍 디코딩하고 3분 단위로 Whisper에 넣어 전체 녹음(최대 2시간)을 처리합니다. 첫 60초만 처리하던 제한을 제거했습니다. 청크 경계에서 문장이 끊길 수 있으므로 원문을 확인하세요.
- `.task`의 실제 central-directory 위치와 CRC를 검사합니다. 공식 번들의 선행 정렬 패딩을 허용하며, Android ZipFile의 LFH signature 오류를 피합니다. 파일을 재압축하거나 패딩을 제거하지 않습니다.
- 모델 파일은 시스템 파일 선택기로 가져옵니다. 진행량, 설치 크기, 내부 저장 경로, 오류 복사를 제공합니다. 실패하면 기존 모델을 보존합니다.
- API 키를 Android Keystore AES-GCM으로 암호화하고 설정 내보내기에서는 제외합니다. 앱 백업을 끕니다.
- 새 녹음 자동 감지 + 과거 파일 수동 선택을 유지합니다. 초기 기준시각 이전의 파일과 파일명에 통화 시각이 없는 파일은 자동 분석하지 않습니다.
- 분석을 WorkManager 큐에 넣고 선택한 모드를 작업에 고정합니다. 주기 스캔으로 서비스가 멈춘 뒤에도 새 파일 검색을 보완합니다.
- 날짜/시간을 임의로 채우지 않습니다. 근거 문장을 검증하고, 시작/종료 시각을 확인·입력한 뒤 캘린더에 등록합니다.

## 휴대폰만으로 GitHub에 올리기

1. 함께 제공된 `CallScheduler-GitHub-Uploader.html`을 다운로드하고 브라우저에서 엽니다. 이 HTML은 소스와 필요한 작은 바이너리를 내장합니다. 외부 CDN은 사용하지 않습니다.
2. Owner `blessyou4ever-debug`, Repository `call_r1`, 기준 브랜치 `main`을 확인합니다.
3. 해당 저장소에만 권한을 준 GitHub 토큰을 입력합니다. Fine-grained token은 **Contents: Read and write**, **Workflows: Read and write**가 필요합니다. 토큰은 파일이나 localStorage에 저장하지 않고 현재 페이지 메모리에서만 사용합니다.
4. 파일 목록을 확인하고 업로드 버튼을 누릅니다. `local-model-v4-...` 새 브랜치가 생성되며 `main`은 그대로 둡니다. 추적 중인 `app/release-keystore.b64`는 새 브랜치에서 제거합니다.
5. 표시된 GitHub 비교 링크에서 변경을 확인한 뒤 필요하면 PR을 만들어 병합합니다. 새 브랜치로 push할 때도 Actions 빌드가 실행됩니다.
6. Actions → `Build CallScheduler local models` → 완료된 실행 → Artifacts에서 빌드 결과를 받습니다. GitHub 인증/Actions 정책에 따라 워크플로우 활성화가 필요할 수 있습니다.

토큰에 Workflow 권한을 부여할 수 없다면 ZIP을 PC에서 풀어 Git으로 커밋·푸시하거나 GitHub 웹에서 파일을 올리세요. ZIP 파일 한 개만 저장소에 올리면 프로젝트가 빌드되지 않습니다.

## Android Studio / 명령행 빌드

- JDK 17, Android SDK 36, NDK 28.2.13676358, CMake 3.22.1, Gradle 8.13, AGP 8.11.1.
- ZIP을 풀어 `CallScheduler` 폴더를 Android Studio에서 열고 SDK를 설치합니다.
- `bash ./gradlew testDebugUnitTest assembleDebug assembleRelease` (Windows: `gradlew.bat ...`).
- arm64-v8a 전용, minSdk 31, targetSdk 36입니다. Fold7/S26+용입니다.
- 최초 빌드 시 Maven/Google/Gradle 및 whisper.cpp 소스 다운로드가 필요합니다. CMake는 Whisper 소스의 SHA-256을 검증합니다. 모델 가중치는 소스·APK에 들어 있지 않습니다.

## 서명과 설치

기본 debug 빌드는 기존 앱과 구분되는 `com.example.callscheduler.localtest`이며 병행 설치할 수 있습니다. debug 서명키는 CI 실행 환경에 따라 달라질 수 있습니다. 지속적으로 업데이트하려면 고정된 개인 릴리스 키를 Secrets에 보관하세요.

릴리스 applicationId는 기존 `com.example.callscheduler`입니다. GitHub 저장소 Settings → Secrets and variables → Actions에 다음 네 값을 넣으면 Actions가 서명합니다:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

키가 없으면 release는 **unsigned** 결과입니다. unsigned APK는 바로 설치할 수 없습니다. 기존 설치 앱을 덮어쓰려면 동일한 applicationId와 서명키가 필요합니다. 새 키로 전환하면 기존 설치 앱 삭제가 필요할 수 있으니 데이터부터 보존하세요.

기존 v3.4에는 키스토어와 기본 비밀번호가 소스에 포함되어 있었습니다. 이 패키지는 이를 포함하지 않습니다. 이미 공개한 키는 비공개가 된 것으로 볼 수 없으며, 현재 파일을 지워도 Git 이력에는 남습니다. 개인 키를 새로 만드는 경우 업데이트 호환성 영향을 고려하세요. 서명이나 앱 아이콘 변경만으로 Play Protect 경고가 없어지는 것은 아닙니다.

## 모델 설치 (내부 저장소를 직접 열 필요 없음)

앱 → 3. AI 모드 → **로컬 모델 관리**.

1. Whisper base 또는 small 다운로드 페이지를 엽니다. `ggml-base.bin`(약 142 MiB) 또는 `ggml-small.bin`(약 466 MiB)의 **다운로드 버튼**을 눌러 휴대폰 Downloads에 저장합니다. `.en` 전용 모델은 한국어용이 아닙니다.
2. 앱의 **Whisper .bin 가져오기**로 다운로드 파일을 선택합니다.
3. Gemma 페이지에서 로그인/이용 조건을 직접 확인하고 `Gemma3-1B-IT_multi-prefill-seq_q4_block128_ekv4096.task`를 받습니다. 정확한 크기는 689,308,662 bytes(약 657 MiB)입니다.
4. **Gemma .task 가져오기**로 선택하고 검사 완료를 기다립니다. `.litertlm`을 이름만 바꾼 파일은 사용할 수 없습니다.
5. 설치 파일은 앱 내부 `files/models/whisper.bin`, `files/models/gemma.task`에 복사됩니다. 원본 Downloads 파일은 유지되며, 앱 삭제 시 내부 모델도 삭제됩니다.

가져오기 중 화면을 유지하세요. 회전·프로세스 종료 시 진행은 중단될 수 있습니다. 재시도하면 미완료 임시 파일을 덮어쓰며 기존 설치 모델은 유지합니다.

Whisper small이 base보다 항상 정확하다고 보장하지 않습니다. 본인의 한국어 통화 표본으로 정확도·속도·발열을 비교하세요. Gemma 1B는 추출을 틀릴 수 있으므로 승인 화면의 근거와 시간을 확인하세요.

## 처리 모드

| 선택 | 음성 인식 | 일정 분석 | 외부 전송 |
|---|---|---|---|
| 로컬 | Whisper | Gemma | 없음 |
| 혼합 | Whisper | Gemini API | 전사문만 |
| 외부 | Gemini API | Gemini API | 녹음과 전사문 |

API 모드에는 본인의 Gemini API 키가 필요하며 요금/할당량은 계정에 따릅니다. 별도 서버는 필요 없습니다. 모델 다운로드·브라우저 이동은 사용자가 누를 때만 일어납니다.

## 새 녹음 / 과거 녹음

- 기본 폴더: `/storage/emulated/0/Recordings/Call/`. 권한을 허용하거나 앱에서 이 폴더를 선택합니다.
- 첫 감시 시 기존 파일을 기준선으로 제외합니다. 이후 생긴 통화 시각이 명확한 녹음만 자동 대상입니다. 앱을 열기 전에 끝난 과거 통화는 수동으로 선택하세요.
- 과거 통화는 메인 화면의 파일 선택 기능으로 가져옵니다. 자동 감지와 별개로 사용자가 명시적으로 고른 파일만 처리합니다.
- Android 16의 절전/강제 종료/백그라운드 제한 때문에 통화 종료 즉시 실행을 보장하지 않습니다. dataSync 포그라운드 서비스 시간 제한에 도달하면 서비스를 종료하며, WorkManager 주기 검색(최소 15분, OS에 따라 지연)이 보완합니다. 앱 강제 종료 후에는 다시 앱을 열어야 합니다.

## 알려진 한계 / 확인 필요

- **실제 Fold7/S26+에서 모델 추론을 검증한 빌드는 아닙니다.** 빌드·단위 테스트 통과와 실기기 품질 검증은 다릅니다.
- Whisper는 전체 파일을 전사하지만 로컬 Gemma는 4,096 토큰 문맥에 맞춰 전사문 2,800자까지 허용합니다. 더 길면 분석 실패 이유와 전사문을 통화 카드에 표시합니다. 외부 전송은 하지 않으며, 사용자가 원하면 혼합 모드로 재시도할 수 있습니다. 짧은 한글 문자열도 토큰화에 따라 한도를 넘으면 명시적으로 실패할 수 있습니다.
- WorkManager/OS 작업 제한 및 메모리 부족으로 긴 분석이 중단될 수 있습니다. 진행 중 항목이 남으면 재시도하세요. 로컬 모델에는 수 분 이상 걸릴 수 있습니다.
- 날짜/시간의 의미적 정확도를 자동 검증하지는 못합니다. 원문 근거가 맞아도 오해할 수 있습니다. 등록 전에 수정 화면을 확인하세요.
- Gemini 인라인 음성 요청은 기존 구현과 같이 18 MiB 이하로 제한합니다. 모델 이용 가능 여부는 계정별로 다릅니다.
- 이전 버전이 공유 폴더에 만든 `.callscheduler_settings_backup.json`에 API 키가 남아 있을 수 있습니다. 더 이상 API 키를 백업하지 않지만 기존 백업/공개된 키는 별도로 확인하고 필요한 경우 폐기하세요.

## 검증

`app/src/test`에 공식 형식의 선행 패딩을 재현한 더미 ZIP 번들, CRC 손상/오형식 거부, 일정 JSON 근거/시간 검증, 파일명 시각 테스트가 있습니다. 테스트 fixture는 실제 모델이 아닙니다. 테스트를 통과해도 실제 모델의 호환성과 한국어 인식 품질은 기기에서 확인해야 합니다.

Whisper: https://github.com/ggml-org/whisper.cpp/tree/v1.7.6 (MIT, WHISPER-LICENSE.txt)
MediaPipe Android: https://developers.google.com/edge/mediapipe/solutions/genai/llm_inference/android
Gemma model: https://huggingface.co/litert-community/Gemma3-1B-IT
