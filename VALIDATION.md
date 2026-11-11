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

## 서버 목록 UI/UX 개선

2026-11-11, macOS / Temurin JDK 21.0.6 / JavaFX 21.0.5.

* Taste Skill 2.5.1의 정보 위계, 정렬, 일관된 버튼·색상, 키보드 포커스, 필드별 오류 표시 원칙을 JavaFX에 적용했습니다. 웹사이트용 레이아웃·효과·브라우저 gate는 네이티브 앱에 적용하지 않았습니다.
* `./gradlew compileJava processResources installDist --offline --no-daemon` 통과.
* 실제 JavaFX 창에서 기본 Scene 1040×720과 최소 창 850×600(이 환경의 Scene 850×572)을 렌더링하고 PNG를 확인했습니다.
* 임시 검증 프로그램으로 빈 목록·미선택 시 접속 비활성화, 선택 요약 갱신, 작업 중 목록·버튼 잠금, 유휴 상태의 취소 버튼 숨김을 확인했습니다.
* 긴 서버 이름의 줄바꿈과 가로 스크롤 부재, 서버 행이 목록 너비 안에 들어오는 것을 확인했습니다.
* 서버 등록의 네 필드 오류가 입력창 아래에 표시되고 대화상자가 열린 상태를 유지하는 것을 확인했습니다. 오류 표시 후 창이 커지며 저장 버튼이 창 안에 남는 것도 확인했습니다.
* 메모리에 숫자가 아닌 값을 입력하면 저장을 막고 오류를 표시하며, 직접 입력한 8192 MB는 설정에 반영되는 것을 확인했습니다.
* 실제 백그라운드 검증 작업을 시작한 뒤 취소하여 인터럽트·유휴 상태 복구·미선택 접속 비활성화를 확인했습니다.
* 화면과 검증 프로그램은 `build/ui-review/`에 보관합니다. 화면의 서버명·인원·응답시간은 검증용 데이터입니다. `LOCALAPPDATA`를 임시 폴더로 지정하여 실제 설정·계정·게임 데이터를 사용하지 않았습니다.
* JavaFX 클래스패스 실행 시 unnamed module 구성 경고가 발생했으며, CSS 파싱 오류나 검증 실패는 없었습니다.
* Windows 실기기 화면, Microsoft 로그인, 실제 서버 상태 API, 게임 설치·모드 동기화·게임 접속, 설치 EXE·자동 업데이트는 이번 검증 범위에 포함하지 않았습니다.

### 긴 오류 메시지 표시

* 오류 본문을 읽기 전용 TextArea로 변경하여 줄바꿈·스크롤·텍스트 선택을 지원합니다. 창 크기 조절과 전체 내용 복사 버튼을 추가했습니다.
* `compileJava processResources installDist --offline --no-daemon` 통과.
* 실제 macOS JavaFX 창에서 150줄 이상의 메시지가 원문과 동일하게 보존되고 전체 선택되는 것, 마지막 줄까지 스크롤되는 것, 가로 스크롤 없이 줄바꿈되는 것을 확인했습니다.
* 680×396 Scene과 축소한 540×372 Scene에서 화면을 확인하고, 크기 조절 후 닫기 버튼이 창 안에 남는 것을 검증했습니다.
* 전체 복사 버튼의 존재를 확인했습니다. 사용자의 클립보드를 덮어쓰지 않기 위해 버튼의 실제 클립보드 쓰기는 실행하지 않았습니다.
* 검증용 메시지·프로그램·캡처는 `build/ui-review/`에 보관합니다. Windows 실기기 검증은 수행하지 않았습니다.

### 팝업 머리글 색상

* 제목·아이콘을 포함한 공통 팝업 머리글에 민트색(`#69c9a5`) 배경을 적용하고 본문은 흰 배경과 24px 여백으로 분리했습니다.
* `compileJava processResources installDist --offline --no-daemon` 통과. 실제 JavaFX 오류·서버 추가·설정 팝업을 캡처하고 긴 메시지 스크롤·크기 조절·필드 오류 표시 검증을 다시 통과했습니다.

## 브라우저 로그인 안내와 런처 자동 복귀

2026-11-11, macOS / Temurin JDK 21.0.6.

* localhost 인증 콜백의 단순 텍스트 응답을 민트색 HTML 안내 화면으로 변경했습니다. 성공·취소·유효하지 않은 요청을 구분하며, 브라우저 단계의 완료를 최종 Minecraft 로그인 성공으로 표시하지 않습니다.
* HTML 본문 전송을 마친 뒤에 인증 결과를 전달하므로 인증 스레드의 서버 종료가 안내 페이지 전송보다 먼저 실행되지 않습니다.
* 유효한 콜백만 런처 복귀를 요청합니다. 약 700ms 후 최소화를 복원하고 창을 앞으로 가져옵니다. 플랫폼이 지원하면 앱 전면 활성화도 요청하며, 이미 오류 대화상자가 열려 있으면 해당 창을 활성화합니다.
* `compileJava processResources installDist --offline --no-daemon` 통과.
* 임시 localhost HTTP 서버에서 성공·취소·잘못된 state·state 누락·POST·빈 code·code와 error 동시 수신·다른 경로의 응답을 확인했습니다. state 검증과 인증 결과 전달, HTML 전체 수신, 캐시 방지, HTML에 인증 코드·state를 반영하지 않는 것을 확인했습니다.
* 인증 결과 직후 서버를 종료하는 경우에도 성공 HTML 전체를 수신했습니다. 유효하지 않은 콜백은 인증을 완료하거나 창 복귀를 요청하지 않습니다.
* macOS에서 일반 JavaFX 포커스 요청만으로는 실제 포커스 검증이 실패하여, 지원되는 `Desktop.requestForeground(true)` 요청을 추가했습니다. 이후 최소화 복원·런처 포커스·오류 대화상자 포커스 검증을 통과했습니다.
* Taste Skill의 Chrome 캡처 스크립트로 안내 화면의 1040×780·390×844 표시를 확인했습니다. 검증 프로그램과 HTML·PNG는 `build/ui-review/`에 보관합니다.
* 실제 Microsoft OAuth 로그인과 Windows에서 브라우저→런처 전환은 수행하지 않았습니다. OS가 포커스 이동을 제한하면 직접 런처를 선택해야 하며, HTML에도 이 안내를 표시합니다.
