# 컴파일 및 배포 검증 기록

2026-11-10, macOS / Temurin JDK 21.0.6.

* 초기 샌드박스 실행은 Gradle 로컬 소켓 제한으로 실패했습니다.
* 이후 허용된 실행 환경에서 기존 소스의 실제 Java 컴파일 성공을 확인했습니다.
* 릴리즈·업데이트 기능 추가 후 `./gradlew clean compileJava processResources --no-daemon` 성공.
* JavaFX 및 외부 라이브러리를 포함한 타입 검사와 버전 리소스 생성까지 통과했습니다.
* GitHub Actions workflow YAML 구문 파싱 통과.
* Windows 실행, Microsoft 로그인, NeoForge 설치, 게임 접속 테스트는 수행하지 않았습니다.
* Windows ZIP 생성과 GitHub 릴리즈 게시 확인은 Release Windows workflow에서 별도로 진행합니다.

다음 확인 명령은 Windows x64의 JDK 21 환경에서 실행합니다.

```powershell
.\gradlew.bat build -x test
.\gradlew.bat packageWindows
```

컴파일·패키지 구성 검사 통과를 실제 Windows UI나 업데이트 교체 동작의 검증으로 간주하지 않습니다.
