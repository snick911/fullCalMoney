@echo off
rem Build and start the web site. Default http://localhost:8080/
rem   Change port:          set PORT=9090 ^& run.bat
rem   Allow phones on LAN:  set HOST=0.0.0.0 ^& run.bat
cd /d "%~dp0"

call "%~dp0build.bat"
if errorlevel 1 (
  pause
  exit /b 1
)

java -jar target\fullcalmoney.jar
