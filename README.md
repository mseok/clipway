# Clipway

Galaxy(Android) 폰과 Mac 사이에서 **클립보드**와 **문자 인증번호**를 이어 주는 앱입니다.
iPhone과 Mac 사이에서 되던 "폰에서 복사하고 Mac에서 붙여넣기", "문자로 온 인증번호를 Mac에서 바로 쓰기"를 Galaxy에서도 쓸 수 있게 합니다.

- 폰에서 복사하면 Mac에서 바로 붙여넣을 수 있고, 반대도 됩니다.
- 폰에 온 문자 인증번호가 Mac 화면에 뜨고 Mac 클립보드에 복사됩니다.
- 계정도 서버도 없습니다. 폰과 Mac이 직접 연결하며, 모든 내용은 종단 간 암호화됩니다.

파일 전송은 넣지 않았습니다. One UI 8.5부터 Quick Share가 AirDrop과 직접 통신하므로 그쪽을 쓰면 됩니다.

## 필요한 것

| | 조건 |
|---|---|
| Mac | Apple Silicon, macOS 14 이상 |
| 폰 | Android 13 이상 (Galaxy Z Fold 7, One UI 8.5에서 확인) |
| 네트워크 | 같은 Wi-Fi, 또는 양쪽에 [Tailscale](https://tailscale.com) |
| 복사 자동 감지 | 폰에 [Shizuku](https://shizuku.rikka.app) 설치 (없으면 수동 전송만 가능) |

## 설치

### Mac

터미널에 아래 한 줄을 붙여 넣습니다.

```sh
curl -fsSL https://github.com/mseok/clipway/releases/latest/download/install-mac.sh | bash
```

메뉴바에 폰 모양 아이콘이 생기면 설치된 것입니다. 메뉴에서 "로그인 시 실행"을 켜 두세요.

`Clipway.dmg`를 받아 응용 프로그램 폴더로 끌어다 놓아도 됩니다. 이 경우 처음 실행할 때 macOS가 확인되지 않은 개발자라며 막으므로, 시스템 설정 → 개인정보 보호 및 보안에서 "그래도 열기"를 한 번 눌러야 합니다.

### 폰

1. [릴리스 페이지](https://github.com/mseok/clipway/releases/latest)에서 `Clipway-android.apk`를 받아 설치합니다.
   설치가 막히면 설정 → 보안 및 개인정보 보호 → "보안 위험 자동 차단"을 잠시 끕니다.
2. 앱을 열고 "권한" 항목의 알림, 문자 수신, 배터리 제한 없음을 차례로 허용합니다.
   문자 수신 권한이 회색으로 막혀 있으면 앱 정보 화면 오른쪽 위 ⋮ 메뉴에서 "제한된 설정 허용"을 먼저 누릅니다.

AI 에이전트(Claude Code, Codex 등)에게 설치를 맡기려면 [docs/agent-setup.md](docs/agent-setup.md)를 읽게 하세요. 폰을 adb로 연결해 두면 `scripts/setup-phone.sh`가 설치, 권한, 페어링을 한 번에 처리합니다.

### 페어링

1. Mac 메뉴바 아이콘 → "새 폰 페어링"을 누르면 QR 코드가 나옵니다.
2. 폰 앱에서 "Mac 추가 (QR 스캔)"으로 스캔합니다. 기본 카메라로 스캔해도 됩니다.

Mac이 여러 대면 Mac마다 한 번씩 페어링합니다.

### 복사 자동 감지 켜기 (Shizuku)

Android는 백그라운드 앱이 클립보드를 읽지 못하게 막습니다. Shizuku를 쓰면 폰에서 복사하는 순간 Mac으로 전달됩니다.

1. 설정 → 휴대전화 정보 → 소프트웨어 정보 → "빌드번호"를 7번 눌러 개발자 옵션을 켭니다.
2. Play 스토어에서 Shizuku를 설치하고, 앱의 "무선 디버깅으로 시작"에서 "페어링" → "시작" 순서로 진행합니다.
3. Clipway 앱에서 "Shizuku 권한 허용"을 누릅니다. "복사 자동 감지: 켜짐"이 보이면 끝입니다.

이렇게 Shizuku를 무선 디버깅으로 한 번 시작해 두면, 폰을 재부팅해도 Wi-Fi에 연결된 상태에서는 Shizuku가 스스로 다시 시작합니다. 자동으로 켜지지 않았다면 Shizuku 앱에서 "시작"을 한 번 누르면 됩니다.

## 사용법

| 하고 싶은 것 | 방법 |
|---|---|
| 폰 → Mac 붙여넣기 | 폰에서 복사하고 Mac에서 ⌘V |
| Mac → 폰 붙여넣기 | Mac에서 복사하고 폰에서 붙여넣기 |
| 인증번호 | 문자가 오면 Mac 화면 오른쪽 위에 코드가 뜹니다. 바로 ⌘V |
| 자동 감지 없이 보내기 | 빠른 설정 패널의 "Mac으로 보내기" 타일, 공유 메뉴, 텍스트 선택 메뉴 |

폰 화면이 꺼져 있는 동안 Mac에서 복사한 내용은 폰을 켜는 순간 전달됩니다.

## 연결과 보안

- 폰이 Mac으로 접속합니다. 같은 Wi-Fi에서는 Bonjour로 Mac을 찾고, 밖에서는 Tailscale 주소로 접속합니다.
- 페어링할 때 QR 코드로 32바이트 키를 나눠 가집니다. 접속마다 X25519 키 교환을 하고, 이후 모든 내용은 AES-256-GCM으로 암호화합니다.
- 클립보드 내용은 로그에 남기지 않습니다(길이만 기록).
- 비밀번호 관리자가 "민감한 내용"으로 표시한 복사는 상대 기기에도 그렇게 표시되어 클립보드 기록 앱에 남지 않습니다.

## 알려진 한계

- 텍스트만 동기화합니다. 이미지와 파일은 지원하지 않습니다.
- 통신사 장문 문자(LMS)와 RCS(채팅+)로 오는 인증번호는 전달하지 못합니다.
- Mac 앱은 Apple 개발자 서명이 없어서, 브라우저로 내려받으면 처음 실행할 때 "그래도 열기"가 필요합니다. 터미널 설치 명령은 이 단계가 없습니다.
- Play 스토어에는 올릴 수 없습니다(문자 수신 권한 정책). APK로 직접 설치해야 합니다.

## 문제 해결

| 증상 | 확인할 것 |
|---|---|
| 폰 앱에 "연결 대기 중" | 같은 Wi-Fi인지, 밖이라면 양쪽 Tailscale이 켜져 있는지 |
| 폰에서 복사한 것이 안 넘어감 | 폰 앱의 "복사 자동 감지"가 켜짐인지. 꺼져 있으면 Shizuku 앱에서 "시작" |
| 인증번호가 안 뜸 | 문자 수신 권한, "인증번호를 Mac으로 전달" 스위치 |
| 한동안 쓰다가 끊김 | 폰 설정에서 Clipway와 Shizuku의 배터리를 "제한 없음"으로 |

## 소스에서 빌드

Xcode나 Android Studio 없이 빌드합니다. 도구는 저장소의 `.toolchain/` 안에만 설치됩니다.

```sh
scripts/setup-toolchain.sh      # JDK 17, Android SDK, Gradle (약 2GB)
mac/scripts/test.sh             # Swift 테스트
mac/scripts/build-app.sh --install
scripts/build-android.sh --install   # adb로 연결된 폰에 설치
scripts/package-release.sh      # dist/에 배포 파일 생성
```

프로토콜 설명과 기준 구현은 `testvectors/generate.py`에 있고, Swift와 Kotlin 테스트가 같은 벡터를 검증합니다.

## 크레딧

- 셸 권한으로 클립보드를 읽는 방식은 [scrcpy](https://github.com/Genymobile/scrcpy)의 `FakeContext`를 옮긴 것입니다 (Apache License 2.0).
- [Shizuku](https://github.com/RikkaApps/Shizuku)와 Shizuku API (MIT License).

자세한 내용은 `NOTICE`를 보세요.

## 라이선스

Apache License 2.0. `LICENSE`를 보세요.
