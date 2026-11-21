@echo off
rem Build without Maven: compile to target\classes, copy web files, package target\fullcalmoney.jar
setlocal EnableDelayedExpansion
cd /d "%~dp0"

where javac >nul 2>nul
if errorlevel 1 (
  echo [ERROR] javac not found. Install JDK 17 or later and add it to PATH.
  exit /b 1
)

if exist target\classes rmdir /s /q target\classes
mkdir target\classes

rem javac argument file: quote each path and use forward slashes so paths with spaces work
if exist target\sources.txt del target\sources.txt
for /r "src\main\java" %%f in (*.java) do (
  set "p=%%f"
  echo "!p:\=/!">>target\sources.txt
)

javac --release 17 -encoding UTF-8 -d target\classes @target\sources.txt
if errorlevel 1 exit /b 1

xcopy /e /i /q /y "src\main\resources" "target\classes" >nul
if errorlevel 1 exit /b 1

jar --create --file target\fullcalmoney.jar --main-class com.fullmoney.WebApp -C target\classes .
if errorlevel 1 exit /b 1

echo Build OK: target\fullcalmoney.jar
exit /b 0
