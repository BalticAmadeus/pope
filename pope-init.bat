@echo off
setlocal enabledelayedexpansion

rem Interactively adds pope wiring to the current directory (your project -
rem new or existing). Run this FROM your project's own directory, pointing
rem at wherever you cloned pope:
rem   \path\to\pope\pope-init.bat
rem Prompts for registries, then delegates to pope's own scaffoldProject
rem Gradle task (see build.gradle.kts) to actually write/patch files.

set TOOL_DIR=%~dp0
if %TOOL_DIR:~-1%==\ set TOOL_DIR=%TOOL_DIR:~0,-1%
set TARGET_DIR=%CD%
set REGISTRIES=

:registry_loop
set /p PREFIX="Registry prefix (e.g. ba.), or leave blank to stop adding registries: "
if "%PREFIX%"=="" goto scaffold

set /p URL="Catalog URL for %PREFIX%: "
if "%URL%"=="" (
    echo No URL given, skipping this registry.
    goto registry_loop
)

if "%REGISTRIES%"=="" (
    set REGISTRIES=%PREFIX%=%URL%
) else (
    set REGISTRIES=!REGISTRIES!,%PREFIX%=%URL%
)

set /p AGAIN="Add another registry? [y/n]: "
if /I "%AGAIN:~0,1%"=="Y" goto registry_loop

:scaffold
set OUTPUT_FILE=%TEMP%\pope-init-%RANDOM%.log
call "%TOOL_DIR%\gradlew.bat" -p "%TOOL_DIR%" scaffoldProject -PtargetDir="%TARGET_DIR%" -Pregistries="%REGISTRIES%" > "%OUTPUT_FILE%" 2>&1
set SCAFFOLD_RESULT=%ERRORLEVEL%
type "%OUTPUT_FILE%"

if %SCAFFOLD_RESULT%==0 (
    del "%OUTPUT_FILE%"
    goto offer_global_cli
)

findstr /C:"PACKAGE_NAME_REQUIRED" "%OUTPUT_FILE%" >nul
if %ERRORLEVEL%==0 (
    del "%OUTPUT_FILE%"
    echo.
    echo Couldn't automatically determine package_name for your project.
    set /p PACKAGE_NAME="Enter package_name (e.g. example.myproject): "
    if "!PACKAGE_NAME!"=="" (
        echo package_name is required.
        exit /b 1
    )
    call "%TOOL_DIR%\gradlew.bat" -p "%TOOL_DIR%" scaffoldProject -PtargetDir="%TARGET_DIR%" -Pregistries="%REGISTRIES%" -PpackageName="!PACKAGE_NAME!"
    if %ERRORLEVEL% neq 0 exit /b %ERRORLEVEL%
    goto offer_global_cli
) else (
    del "%OUTPUT_FILE%"
    exit /b 1
)

:offer_global_cli
rem Already on PATH? Don't ask - just say so and move on, rather than
rem asking a question whose answer install.ps1 would report after the fact.
powershell -NoProfile -ExecutionPolicy Bypass -File "%TOOL_DIR%\cli\install.ps1" -Check
if %ERRORLEVEL%==0 (
    echo (global pope CLI is already set up - "pope install" already works from any project^)
    exit /b 0
)
set /p ADD_GLOBAL_CLI="Add the global pope CLI to PATH, so \"pope install\" works from any project without .\pope? [y/n]: "
if /I "%ADD_GLOBAL_CLI:~0,1%"=="N" exit /b 0
powershell -NoProfile -ExecutionPolicy Bypass -File "%TOOL_DIR%\cli\install.ps1"
exit /b 0
