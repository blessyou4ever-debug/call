# 빌드 검증 기록

- 환경: Linux / JDK 17 / SDK 36 / NDK 28.2.13676358 / Gradle 8.13.
- `testDebugUnitTest assembleDebug assembleRelease`: 성공.
- JUnit: 10 tests, 0 failures, 0 errors.
- libcallwhisper.so / libllm_inference_engine_jni.so: ELF LOAD 정렬 0x4000 (16KB).
- Debug APK: zipalign -c -P 16 4 검사 성공.
- 업로더 JavaScript 문법 검사, 모의 API 성공/403 처리, 토큰 초기화, main 직접 변경 없음, 내장 ZIP 동일성 검사 성공. 실제 GitHub 업로드는 실행하지 않음.

실제 Fold7/S26+의 추론, 한국어 인식 정확도, 전체 녹음 자동 감지, 캘린더 동기화는 실기기에서 추가 확인해야 합니다. 실제 Gemma 가중치로 로딩 시험을 한 것은 아닙니다. APK는 이 소스 패키지에 포함하지 않았습니다.
