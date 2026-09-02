@echo off
chcp 65001 > nul
echo ====================================================
echo  KBO 스코어보드 에뮬레이터 실행기
echo ====================================================
echo.

set SDK_DIR=C:\Users\HL_World\AppData\Local\Android\Sdk
set EMULATOR=%SDK_DIR%\emulator\emulator.exe
set ADB=%SDK_DIR%\platform-tools\adb.exe

echo 1. 에뮬레이터 창을 띄우는 중입니다...
start "" "%EMULATOR%" -avd Medium_Phone

echo 2. 에뮬레이터가 켜질 때까지 잠시 기다립니다...
"%ADB%" wait-for-device

:wait_boot
for /f "tokens=*" %%i in ('"%ADB%" shell getprop sys.boot_completed 2^>nul') do set BOOT=%%i
if not "%BOOT%"=="1" (
    timeout /t 2 > nul
    goto wait_boot
)

echo 3. 앱을 실행합니다...
"%ADB%" shell am start -n com.hlworld.kboalarm/.LauncherActivity

echo.
echo ====================================================
echo  실행 완료! 에뮬레이터 창에서 앱을 확인하세요.
echo ====================================================
timeout /t 5 > nul
