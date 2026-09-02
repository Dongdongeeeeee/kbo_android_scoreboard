@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"
title KBO Scoreboard - APK Builder
set "GRADLE_USER_HOME=%~dp0.gradle-home"

echo ===================================================
echo   Building the APK. Please wait.
echo ===================================================
echo.

rem Use the working JDK installed with this project first.
set "JAVA_HOME=%USERPROFILE%\.jdks\ms-17.0.20.1"
if not exist "%JAVA_HOME%\bin\java.exe" set "JAVA_HOME=%USERPROFILE%\.jdks\jbr-21.0.11"
if not exist "%JAVA_HOME%\bin\java.exe" set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
if not exist "%JAVA_HOME%\bin\java.exe" goto no_java
set "PATH=%JAVA_HOME%\bin;%PATH%"

echo Java: %JAVA_HOME%
echo.
call "%~dp0gradlew.bat" :app:assembleDebug
set "BUILD_ERROR=%ERRORLEVEL%"

if "%BUILD_ERROR%"=="0" if exist "%~dp0app\build\outputs\apk\debug\app-debug.apk" (
    echo.
    echo ===================================================
    echo   [SUCCESS] APK build complete.
    echo ===================================================
    echo.
    echo APK path:
    echo %~dp0app\build\outputs\apk\debug\app-debug.apk
    echo.
    echo Drag app-debug.apk from the opened folder into LDPlayer.
    start "" explorer.exe "%~dp0app\build\outputs\apk\debug"
) else (
    echo.
    echo ===================================================
    echo   [ERROR] APK build failed.
    echo ===================================================
    echo Check the error message above.
)

echo.
pause
exit /b %BUILD_ERROR%

:no_java
echo [ERROR] No working Java JDK was found.
echo.
echo Install Android Studio or JDK 17, then run this file again.
echo The bundled Android Studio JDK on this PC is not usable.
echo.
pause
exit /b 1
