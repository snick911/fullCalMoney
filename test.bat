@echo off
rem Build, then run the self check (calculation reference values + HTTP API). Exit code 0 = all passed.
setlocal EnableDelayedExpansion
cd /d "%~dp0"

call "%~dp0build.bat"
if errorlevel 1 exit /b 1

if exist target\test-classes rmdir /s /q target\test-classes
mkdir target\test-classes

if exist target\test-sources.txt del target\test-sources.txt
for /r "src\test\java" %%f in (*.java) do (
  set "p=%%f"
  echo "!p:\=/!">>target\test-sources.txt
)

javac --release 17 -encoding UTF-8 -cp target\classes -d target\test-classes @target\test-sources.txt
if errorlevel 1 exit /b 1

java -cp "target\classes;target\test-classes" com.fullmoney.CalculatorCheck
exit /b %errorlevel%
