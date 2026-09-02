# KBO Android Scoreboard

안드로이드에서 상단 알림이 계속 떠 있는 형태의 KBO 스코어보드 앱입니다.

## What it does

- 오늘 KBO 경기 전체를 가져옵니다
- 앱 안에서 오늘 경기 목록과 점수를 보여줍니다
- 상단에 상시 표시되는 알림을 유지합니다
- 알림을 펼치면 경기별 점수 목록이 보입니다
- 취소 경기는 `취소`로 표시합니다
- 1분마다 자동 갱신합니다

## Android behavior

이 앱은 `foreground service + ongoing notification` 방식으로 동작합니다.
Android 14 이상에서는 foreground service type과 권한 선언이 필요합니다.

## Data source

- KBO 공식 daily schedule
- KBO 공식 scoreboard

## Next step

이 폴더를 Android Studio에서 열고 `app` 모듈을 실행하면 됩니다.

실행 흐름

1. Android Studio로 `kbo_android_scoreboard` 폴더를 엽니다.
2. 기기 또는 에뮬레이터를 연결합니다.
3. `Run`을 누릅니다.
4. 앱에서 `알림 시작`을 누르면 상단 알림이 살아납니다.

참고

- Android 13 이상에서는 알림 권한을 허용해야 합니다.
- Android 14 이상에서는 foreground service 관련 권한이 필요합니다.
