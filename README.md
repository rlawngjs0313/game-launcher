# Game Launcher

Windows 10 이상 **x64**용 JavaFX Minecraft 런처. Java 21과 Gradle을 사용합니다.
ARM64 네이티브 패키징은 이번 구현 범위에서 제외합니다.

## 시작

```powershell
.\gradlew.bat build -x test
.\gradlew.bat run
```

Windows x64에서 Java가 없는 PC에 전달할 앱 이미지 만들기:

```powershell
.\gradlew.bat packageWindows
```

`build/package/GameLauncher` **폴더 전체**를 ZIP으로 전달합니다. exe만 전달하면 안 됩니다.
앱 이미지에는 런처 실행용 Java가 포함됩니다. 게임용 Java는 별도로 설치합니다.
패키징은 대상 Windows에서 실행해야 하며 ARM64 네이티브 이미지를 만들지 않습니다.

## 최초 설정

1. 설정 → 준비한 Microsoft 애플리케이션의 Client ID 입력.
2. 해당 애플리케이션에는 개인 Microsoft 계정 로그인과 데스크톱 리디렉션
   `http://localhost`를 설정합니다. 로그인은 브라우저 + Authorization Code/PKCE입니다.
   Client Secret은 사용하지 않습니다. Minecraft Services API 이용 가능 여부는 해당 앱 등록 상태에 따라 달라집니다.
3. 게임 Java 경로는 비우면 필요한 버전의 Adoptium JRE를 자동 설치합니다.
   직접 지정할 때는 서버 Minecraft 버전이 요구하는 Java의 `java.exe` 전체 경로를 입력합니다.
4. 서버 추가 → 이름, 게임 주소, API 기본 주소, 서버 ID, 선택적으로 초대 토큰 입력.

예:

```text
게임 주소: mc.example.com:25565
API 주소: https://launcher.example.com
서버 ID: ars-server
```

API 주소는 HTTPS가 기본이며 `localhost` 개발 주소만 HTTP를 허용합니다.
초대 토큰이 설정된 Backend라면 같은 토큰을 입력합니다.

## 접속 동작

홈 → 서버 접속 → 서버 메타데이터 → 필요한 Minecraft/NeoForge/Java 설치 안내 →
로컬 `mods` 목록 전송 → 필수 모드/버전 조건 비교 → 충돌 선택 → 다운로드 및 해시 확인 →
Microsoft 계정 확인 → 게임 실행 및 서버 자동 접속.

* Minecraft 클라이언트·라이브러리·에셋은 Mojang 배포 목록에서 받습니다.
* NeoForge는 공식 installer를 받아 클라이언트 프로필을 생성합니다.
* 최초 구현 대상은 Minecraft 1.21.1 + NeoForge 21.1.x입니다.
  바닐라 프로필도 처리합니다. 다른 버전은 메타데이터 기반으로 처리하지만 개별 호환성 검증은 하지 않았습니다.
* 자동 접속은 지원 버전에서 Quick Play 인수를 사용합니다.
* 상태·접속 인원은 30초마다 갱신합니다. 표시 지연시간은 Backend → 게임 서버의 측정값입니다.
* 서버가 오프라인이거나 API 오류이면 해당 상태를 표시합니다.

## 모드 선택 규칙

필수 모드의 `minimumVersion`과 Maven 버전 범위 조건을 **모두** 만족해야 합니다.
같은 ID의 복수 JAR, 다른 서버 필수 버전 등의 충돌에서는 체크박스 창을 엽니다.

* 서버 조건을 만족하는 기존 모드는 유지할 수 있습니다.
* 무관한 개인 모드는 기본 선택되어 유지됩니다.
* 같은 모드 ID를 중복 선택하면 적용되지 않습니다.
* 선택 해제한 기존 JAR는 **백업 없이 삭제**됩니다.
* 모든 다운로드·해시·메타데이터 확인 후 변경합니다. 디스크 오류 등 변경 도중 실패했을 때
  삭제한 파일을 복원하지는 않으므로 표시된 선택을 확인하세요.
* 최소 버전을 만족해도 모드 자체의 네트워크 프로토콜이 달라 실제 접속에 실패할 수 있습니다.
  런처 버전 조건은 게임 모드 자체의 호환성 판정을 대체하지 않습니다.
* 개인 모드의 모든 상호 호환성을 보장하지 않습니다.

## 저장 위치

```text
%LOCALAPPDATA%/GameLauncher/
  settings.json
  account.dpapi
  instances/<API주소와 서버ID의 해시>/
    mods/
    game/                 버전, 로더, 라이브러리, 에셋, Java
    logs/launcher-game.log
    instance.json
    game-process.json
```

공식 `.minecraft`와 별도의 서버별 인스턴스를 사용합니다.
개인 모드는 해당 인스턴스의 `mods` 폴더에 넣으세요.
계정의 갱신 토큰은 Windows 사용자 DPAPI로 암호화합니다.
Microsoft 토큰은 동기화 Backend에 보내지 않습니다.
게임 실행 중 인스턴스 변경과 같은 인스턴스의 중복 실행을 차단합니다.

## 개발 및 검증 범위

단일 `main` 브랜치, 독립 저장소 `rlawngjs0313/game-launcher`.
Backend의 Java 소스를 런타임에 직접 참조하지 않습니다.
검증 범위는 사용자 요청에 따라 구현 및 컴파일/구문 검사입니다.
Microsoft 로그인, NeoForge 설치, Windows 패키징, 실제 서버 접속은 사용자 실사용 검증 대상입니다.
빌드에 처음 필요한 Maven/Gradle 의존성을 받으려면 인터넷 연결이 필요합니다.
