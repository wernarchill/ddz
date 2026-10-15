@echo off
REM 便捷入口：双击或命令行运行都可以。真正的逻辑在 build-apk.ps1。
setlocal
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build-apk.ps1" %*
if errorlevel 1 (
  echo.
  echo 构建未成功，请查看上面的输出。
  pause
  exit /b 1
)
echo.
pause
