@echo off
setlocal enabledelayedexpansion

rem Bootstraps pope into the CURRENT directory (your project - new or
rem existing), with NO local clone of pope needed: this script is meant
rem to be downloaded once (e.g. via Invoke-WebRequest from GitHub) and
rem run from inside your project. It writes just enough to resolve pope
rem from its published Maven repo (https://balticamadeus.github.io/pope/)
rem - the Gradle wrapper, settings.gradle.kts, build.gradle.kts - then
rem lets Gradle pull the plugin itself and finish setup via the popeInit
rem task (which does what this script can't: read pope's own code to
rem infer package_name, etc.). See docs/decisions/0008-plugin-publishing-v1.md.

set "POPE_REPO_RAW=https://raw.githubusercontent.com/BalticAmadeus/pope/main"
set "TARGET_DIR=%CD%"
set "REGISTRIES="

set "POPE_VERSION=%~1"
if "%POPE_VERSION%"=="" (
    set /p POPE_VERSION="pope version to use (see https://github.com/BalticAmadeus/pope/releases): "
    if "!POPE_VERSION!"=="" (
        echo A version is required.
        exit /b 1
    )
)

:registry_loop
set /p PREFIX="Registry prefix (e.g. ba), or leave blank to stop adding registries: "
if "%PREFIX%"=="" goto fetch_wrapper

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

:ask_again
set /p AGAIN="Add another registry? [yes/no]: "
if /I "%AGAIN%"=="y" goto registry_loop
if /I "%AGAIN%"=="yes" goto registry_loop
if /I "%AGAIN%"=="n" goto fetch_wrapper
if /I "%AGAIN%"=="no" goto fetch_wrapper
echo Please enter 'yes' or 'no'.
goto ask_again

:fetch_wrapper
echo Fetching the Gradle wrapper...
if not exist gradle\wrapper mkdir gradle\wrapper
powershell -NoProfile -Command "Invoke-WebRequest -Uri '%POPE_REPO_RAW%/gradlew' -OutFile 'gradlew'" || exit /b 1
powershell -NoProfile -Command "Invoke-WebRequest -Uri '%POPE_REPO_RAW%/gradlew.bat' -OutFile 'gradlew.bat'" || exit /b 1
powershell -NoProfile -Command "Invoke-WebRequest -Uri '%POPE_REPO_RAW%/gradle/wrapper/gradle-wrapper.jar' -OutFile 'gradle\wrapper\gradle-wrapper.jar'" || exit /b 1
powershell -NoProfile -Command "Invoke-WebRequest -Uri '%POPE_REPO_RAW%/gradle/wrapper/gradle-wrapper.properties' -OutFile 'gradle\wrapper\gradle-wrapper.properties'" || exit /b 1

if exist settings.gradle.kts (
    echo   settings.gradle.kts already exists - left untouched. Needs a pluginManagement{} repositories{}
    echo   block pointing at https://balticamadeus.github.io/pope/
    goto write_build_file
)
for %%I in ("%TARGET_DIR%") do set "ROOT_PROJECT_NAME=%%~nxI"
(
    echo pluginManagement {
    echo     repositories {
    echo         maven {
    echo             url = uri("https://balticamadeus.github.io/pope/"^)
    echo         }
    echo         gradlePluginPortal(^)
    echo     }
    echo }
    echo.
    echo rootProject.name = "%ROOT_PROJECT_NAME%"
) > settings.gradle.kts

:write_build_file
if exist build.gradle.kts (
    echo   build.gradle.kts already exists - left untouched. Needs
    echo   id("io.github.balticamadeus.pope"^) version "%POPE_VERSION%" applied.
    goto run_pope_init
)
(
    echo plugins {
    echo     id("io.github.balticamadeus.pope"^) version "%POPE_VERSION%"
    echo }
) > build.gradle.kts

:run_pope_init
set OUTPUT_FILE=%TEMP%\pope-init-%RANDOM%.log
call gradlew.bat popeInit > "%OUTPUT_FILE%" 2>&1
set INIT_RESULT=%ERRORLEVEL%
type "%OUTPUT_FILE%"

if %INIT_RESULT%==0 (
    del "%OUTPUT_FILE%"
    goto apply_registries
)

findstr /C:"Could not infer package_name" "%OUTPUT_FILE%" >nul
if %ERRORLEVEL%==0 (
    del "%OUTPUT_FILE%"
    echo.
    echo Couldn't automatically determine the package name for your project.
    set /p PACKAGE_NAME="Enter it now (e.g. example.myproject): "
    if "!PACKAGE_NAME!"=="" (
        echo A package name is required.
        exit /b 1
    )
    call gradlew.bat popeInit "-PpopePackageName=!PACKAGE_NAME!"
    if %ERRORLEVEL% neq 0 exit /b %ERRORLEVEL%
) else (
    del "%OUTPUT_FILE%"
    exit /b 1
)

:apply_registries
if "%REGISTRIES%"=="" goto offer_global_cli
for %%R in (%REGISTRIES:,= %) do (
    for /f "tokens=1,2 delims==" %%A in ("%%R") do (
        call gradlew.bat popeRegistryAdd "-PregistryPrefix=%%A" "-PcatalogUrl=%%B"
    )
)

:offer_global_cli
set "CLI_DIR=%USERPROFILE%\.pope\cli"
if not exist "%CLI_DIR%" mkdir "%CLI_DIR%"
powershell -NoProfile -Command "Invoke-WebRequest -Uri '%POPE_REPO_RAW%/cli/pope.bat' -OutFile '%CLI_DIR%\pope.bat'" || exit /b 1
powershell -NoProfile -Command "Invoke-WebRequest -Uri '%POPE_REPO_RAW%/cli/install.ps1' -OutFile '%CLI_DIR%\install.ps1'" || exit /b 1

rem Already on PATH? Don't ask - just say so and move on, rather than
rem asking a question whose answer install.ps1 would report after the fact.
powershell -NoProfile -ExecutionPolicy Bypass -File "%CLI_DIR%\install.ps1" -Check
if %ERRORLEVEL%==0 (
    echo (global pope CLI is already set up - "pope install" already works from any project^)
    exit /b 0
)
:ask_add_global_cli
set /p ADD_GLOBAL_CLI="Add the global pope CLI to PATH, so \"pope install\" works from any project without .\gradlew? [yes/no]: "
if /I "%ADD_GLOBAL_CLI%"=="y" goto do_add_global_cli
if /I "%ADD_GLOBAL_CLI%"=="yes" goto do_add_global_cli
if /I "%ADD_GLOBAL_CLI%"=="n" exit /b 0
if /I "%ADD_GLOBAL_CLI%"=="no" exit /b 0
echo Please enter 'yes' or 'no'.
goto ask_add_global_cli

:do_add_global_cli
powershell -NoProfile -ExecutionPolicy Bypass -File "%CLI_DIR%\install.ps1"
exit /b 0
