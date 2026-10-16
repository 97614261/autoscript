@echo off
@rem Compatibility entry; the real Gradle workspace lives in android/.
call "%~dp0android\gradlew.bat" -p "%~dp0android" %*
exit /b %ERRORLEVEL%
