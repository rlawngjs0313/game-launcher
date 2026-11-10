# 컴파일 및 배포 검증 기록

2026-11-10, macOS / Temurin JDK 21.0.6.

* 초기 샌드박스 실행은 Gradle 로컬 소켓 제한으로 실패했습니다.
* 이후 허용된 실행 환경에서 기존 소스의 실제 Java 컴파일 성공을 확인했습니다.
* 릴리즈·업데이트 기능 추가 후 `./gradlew clean compileJava processResources --no-daemon` 성공.
* JavaFX 및 외부 라이브러리를 포함한 타입 검사와 버전 리소스 생성까지 통과했습니다.
* GitHub Actions workflow YAML 구문 파싱 통과.
* Windows 실행, Microsoft 로그인, NeoForge 설치, 게임 접속 테스트는 수행하지 않았습니다.
* v0.2.0의 Windows ZIP 빌드·체크섬과 GitHub 릴리즈 게시를 확인했습니다.
* v0.2.1은 Inno Setup 설치 EXE로 전환하며 Release Windows workflow에서 설치 파일 컴파일·체크섬·게시를 확인합니다.

다음 확인 명령은 Windows x64의 JDK 21 환경에서 실행합니다.

```powershell
.\gradlew.bat build -x test
.\gradlew.bat packageWindows
```

컴파일·패키지 구성 검사 통과를 실제 Windows UI나 업데이트 교체 동작의 검증으로 간주하지 않습니다.

## 오류 팝업 및 빌드 Client ID 변경 검증

2026-11-10, macOS / Temurin JDK 21.0.6.

* `./gradlew compileJava processResources installDist --offline --no-daemon` 성공.
* 임시 UUID로 `.env`의 ID가 빌드 리소스에 포함되는 것을 확인했습니다.
* 환경변수 `MICROSOFT_CLIENT_ID`가 `.env`보다 우선하며, 잘못된 ID는 리소스 생성에 실패합니다.
* 빈 ID로 `packageWindows -x installDist` 실행 시 패키징 전 누락 검사에서 실패하는 것을 확인했습니다.
* 검증 후 `.env`는 빈 입력란으로 복원하고 개발용 배포물을 다시 빌드했습니다.
* 실제 UI 확인을 시도했으나 샌드박스에서는 JavaFX가 화면을 찾지 못했고, 별도 실행한 Java 창도 UI 자동화 도구가 인식하지 못했습니다.
* 로그 박스 제거·설정 화면·오류 팝업의 실제 화면, Microsoft 로그인, Windows 패키징 및 게임 비정상 종료는 실사용 검증하지 않았습니다.

## v0.2.2 초대 토큰 제거

* `compileJava processResources installDist --offline --no-daemon` 통과.
* 임시 Java HTTP 서버를 통해 API 요청과 해시 검증 다운로드에 Authorization 헤더를 보내지 않음을 확인.
* 예전 settings.json의 서버 목록·메모리 설정을 유지하고 token·clientId 필드를 다음 직렬화에서 제거하는 것을 확인.
* 생성된 빌드 메타데이터 버전 0.2.2 확인.
* 실제 Microsoft 로그인과 게임 접속은 검증하지 않았습니다. Windows 설치 파일과 체크섬은 릴리즈 workflow에서 검증합니다.
