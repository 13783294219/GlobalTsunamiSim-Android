@echo off
setlocal
where gradle >nul 2>nul
if errorlevel 1 (
  echo ERROR: Gradle not found. Open this folder in Android Studio and use Build ^> Build APK(s).
  pause
  exit /b 1
)
gradle.bat assembleDebug
if errorlevel 1 exit /b %errorlevel%
echo APK output: app\build\outputs\apk\debug\app-debug.apk
pause
