@echo off
setlocal
set "GRADLE_VERSION=8.10.2"
set "GRADLE_DIST=%USERPROFILE%\.gradle\custom-wrapper\gradle-%GRADLE_VERSION%"
if not exist "%GRADLE_DIST%\bin\gradle.bat" (
  echo Downloading Gradle %GRADLE_VERSION% for the first build...
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $base=Join-Path $env:USERPROFILE '.gradle\custom-wrapper'; New-Item -ItemType Directory -Force -Path $base | Out-Null; $zip=Join-Path $env:TEMP 'gradle-%GRADLE_VERSION%-bin.zip'; Invoke-WebRequest -Uri 'https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip' -OutFile $zip; Expand-Archive -Path $zip -DestinationPath $base -Force"
  if errorlevel 1 exit /b 1
)
call "%GRADLE_DIST%\bin\gradle.bat" %*
exit /b %ERRORLEVEL%
