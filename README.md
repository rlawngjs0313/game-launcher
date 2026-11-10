# Game Launcher

Windows 10 이상 **x64**용 JavaFX Minecraft 런처. Java 21과 Gradle을 사용합니다.
ARM64 네이티브 패키징은 이번 구현 범위에서 제외합니다.

## Windows에서 다운로드

[최신 릴리즈 다운로드](https://github.com/rlawngjs0313/game-launcher/releases/latest)

1. Assets에서 `GameLauncher-버전-windows-x64-setup.exe` 하나를 받습니다.
2. EXE를 실행하면 바로 설치가 진행되고 완료 후 런처가 열립니다.
3. 이후 시작 메뉴의 **Game Launcher**에서 실행합니다. 별도의 Java 설치는 필요하지 않습니다.

설치 위치는 `%LOCALAPPDATA%/Programs/GameLauncher`이며 관리자 권한을 요구하지 않습니다.
Windows 설정의 설치된 앱에서 제거할 수 있습니다. 제거해도 계정·게임 데이터는 별도 사용자 데이터 폴더에 유지됩니다.
`Source code.zip`은 개발용 소스이므로 실행 파일 EXE를 선택하세요.
일반 사용자는 Gradle이나 JDK를 설치할 필요가 없습니다.

## 런처 업데이트

기본적으로 런처 시작 시 GitHub의 최신 안정 릴리즈를 한 번 확인합니다.
홈의 **업데이트 확인** 버튼으로 직접 확인할 수도 있습니다.
현재보다 높은 `major.minor.patch` 버전일 때 변경 내용과 다운로드 용량을 표시합니다.

**업데이트 후 재시작**을 누르면 다음 순서로 진행합니다.

```text
설치 EXE 다운로드 → SHA-256·크기 검증 → 별도 Java 도우미 시작
→ 현재 런처 종료 → 설치 EXE로 업데이트 → 새 런처 시작
```

새 런처가 시작됐다는 확인을 받지 못하면 현재 버전의 설치 EXE로 복구합니다.
복구용 설치 EXE도 확인·다운로드하며 `installer-cache`에 보관합니다. 모드 백업 기능과는 별개입니다.
업데이트 도우미가 종료된 다음에는 현재 버전의 복구 설치 파일만 캐시에 유지합니다.
`%LOCALAPPDATA%/GameLauncher`의 계정·서버 목록·게임·모드는 업데이트 대상에 포함하지 않습니다.
실행 중인 게임은 먼저 종료해야 업데이트할 수 있습니다.
다운로드/검증/쓰기 권한 확인에 실패하면 현재 런처를 종료하지 않습니다.
인터넷 연결 실패나 새 릴리즈 부재는 기존 버전의 사용을 막지 않습니다.

자동 교체는 이 기능이 포함된 Windows 릴리즈 앱에서 지원합니다.
기존 0.1.0 및 0.2.0 ZIP 빌드는 최초 한 번 새 설치 EXE를 직접 실행해야 합니다.
업데이트 실패 기록은 `%LOCALAPPDATA%/GameLauncher/updates/update-*/`에 남깁니다.
개발 실행(`gradlew run`)에서는 버전 확인만 가능하며 설치 EXE를 실행하지 않습니다.

## 시작

```powershell
.\gradlew.bat build -x test
.\gradlew.bat run
```

Windows x64에서 설치 전 앱 이미지 구성 확인용 빌드:

```powershell
.\gradlew.bat packageWindows
```

`build/package/GameLauncher`는 설치 EXE에 포함할 중간 산출물입니다. 실제 배포는 아래 `releaseWindows`를 사용합니다.
런처 실행용 Java가 포함되며 게임용 Java는 별도로 설치합니다.
패키징은 대상 Windows에서 실행해야 하며 ARM64 네이티브 이미지를 만들지 않습니다.

배포 설치 EXE와 체크섬을 로컬에서 만들려면 Inno Setup 6 이상을 설치하고 다음을 실행합니다.
컴파일러가 기본 경로에 없다면 `ISCC_EXE` 환경변수로 `ISCC.exe` 경로를 지정합니다.

```powershell
.\gradlew.bat releaseWindows -x test
```

출력 위치는 `build/releases/`입니다.

## 새 버전 릴리즈하기

버전 원본은 `version.properties` 하나입니다. UI, 빌드 정보, EXE 파일명과 앱 버전은 여기서 생성합니다.

1. `version.properties`의 버전을 올립니다. 예: `version=0.3.0`.
2. `docs/releases/v0.3.0.md`에 해당 버전의 변경사항을 작성하고 `main`에 커밋·push합니다.
3. GitHub Actions → **Release Windows** → **Run workflow**, branch `main`을 선택합니다.
4. Windows 빌드와 패키징 성공 후 해당 버전의 GitHub Release가 게시됩니다.

또는 `main`의 해당 커밋에 `v0.3.0` 태그를 push해 같은 작업을 실행할 수 있습니다.
태그와 `version.properties`는 일치해야 합니다. 이미 게시한 릴리즈 파일은 덮어쓰지 않으며,
수정판은 새 버전으로 게시합니다. 브랜치는 `main` 하나를 사용합니다.
릴리즈 제목은 `v0.3.0` 형식이며 본문은 해당 변경사항 파일의 내용입니다.
게시 중인 릴리즈는 draft 상태로 파일을 모두 올린 뒤 공개하므로 불완전한 파일 묶음이
업데이트 대상으로 선택되지 않습니다.

친구들이 Microsoft Client ID를 직접 입력하지 않게 하려면 저장소의
Settings → Secrets and variables → Actions → **Variables**에
`MICROSOFT_CLIENT_ID`를 설정한 후 릴리즈하세요. 이는 공개 애플리케이션 ID이며
Client Secret이나 개인 액세스 토큰을 넣는 항목이 아닙니다.
설정된 Client ID는 앱의 기본값으로 포함되고, 없으면 기존처럼 런처 설정에서 입력합니다.

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
GitHub 릴리즈 확인에는 개인 액세스 토큰을 앱에 포함하지 않습니다. 배포 저장소는 공개여야 합니다.
