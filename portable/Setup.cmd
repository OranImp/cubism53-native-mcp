@echo off
setlocal DisableDelayedExpansion
set "NO_ARGS="
if "%~1"=="" set "NO_ARGS=1"
"%~dp0runtime\python\python.exe" -B "%~dp0app\launcher.py" setup %*
set "EXIT_CODE=%ERRORLEVEL%"
if defined NO_ARGS pause
exit /b %EXIT_CODE%
