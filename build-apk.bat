@echo off
setlocal
call "%~dp0gradlew.bat" assembleDebug
if errorlevel 1 (
  echo.
  echo Build failed. Check Android Studio / Android SDK setup and the error output above.
  exit /b 1
)
echo.
echo APK built at: %~dp0app\build\outputs\apk\debug\app-debug.apk
exit /b 0
