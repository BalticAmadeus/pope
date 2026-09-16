@echo off
setlocal enabledelayedexpansion

rem Global pope CLI - install this once (put this file's directory on
rem PATH) and `pope install`/`pope propath`/`pope registry add` work from
rem inside ANY pope-managed project, no per-project ".\pope.bat" needed.
rem
rem Unlike the per-project pope.bat (copied into each scaffolded project
rem by scaffoldProject, which finds its target project via its OWN file
rem location - %~dp0), this script finds its target project from your
rem CURRENT DIRECTORY, walking upward looking for openedge-project.json -
rem the same way git/npm find their project root from any subfolder.
rem That's what makes it safe to put on PATH: it always operates on
rem whatever project you're actually standing in, never on wherever this
rem script itself happens to be installed.
rem
rem The per-project pope.bat still exists and still matters - it's what
rem makes a project fully self-contained and usable without any global
rem setup (e.g. in CI, or on a machine where this hasn't been installed).
rem This is a local-dev convenience layered on top, not a replacement.

set "SEARCH_DIR=%CD%"

:find_root
if exist "%SEARCH_DIR%\openedge-project.json" goto found

for %%I in ("%SEARCH_DIR%\..") do set "PARENT_DIR=%%~fI"
if /I "%PARENT_DIR%"=="%SEARCH_DIR%" (
    echo Not inside a pope project - no openedge-project.json found in %CD% or any parent directory. 1>&2
    exit /b 1
)
set "SEARCH_DIR=%PARENT_DIR%"
goto find_root

:found
set "PROJECT_ROOT=%SEARCH_DIR%"
set "POPE_SUBDIR=%PROJECT_ROOT%\.pope"

rem Two layouts a scaffolded project can be in: Gradle's own files tucked
rem into .pope/ (new projects), or at the project root (legacy - e.g. the
rem real openedge-package-manager demo repo, which predates the .pope/
rem layout). Detected automatically so this one script works for both.
if exist "%POPE_SUBDIR%\gradlew.bat" (
    set "GRADLEW=%POPE_SUBDIR%\gradlew.bat"
    set "USE_SUBDIR=1"
) else (
    set "GRADLEW=%PROJECT_ROOT%\gradlew.bat"
    set "USE_SUBDIR=0"
)

if not exist "%GRADLEW%" (
    echo Found %PROJECT_ROOT%\openedge-project.json, but no gradlew.bat there ^(or in .pope\^) - is this really a pope project? 1>&2
    exit /b 1
)

if "%~1"=="" goto usage
set COMMAND=%~1

if /I "%COMMAND%"=="install" (
    if "%~2"=="" (
        call :run_gradle popeInstall
    ) else (
        call :run_gradle popeInstall "-PpopeAdd=%~2"
    )
    goto :eof
)

if /I "%COMMAND%"=="uninstall" (
    if "%~2"=="" (
        goto usage
    ) else (
        call :run_gradle popeUninstall "-PpopeUninstall=%~2"
    )
    goto :eof
)

if /I "%COMMAND%"=="propath" (
    if "%~2"=="" (
        call :run_gradle popePropath
    ) else if /I "%~2"=="--tests" (
        call :run_gradle popePropath -PpopeIncludeTests
    ) else (
        goto usage
    )
    goto :eof
)

if /I "%COMMAND%"=="registry" (
    if /I not "%~2"=="add" goto usage
    if "%~3"=="" (
        set /p PREFIX="Registry prefix (e.g. ba.): "
        if "!PREFIX!"=="" (
            echo Registry prefix is required.
            exit /b 1
        )
        set /p URL="Catalog URL: "
        if "!URL!"=="" (
            echo Catalog URL is required.
            exit /b 1
        )
        call :run_gradle popeRegistryAdd "-PregistryPrefix=!PREFIX!" "-PcatalogUrl=!URL!"
        goto :eof
    )
    if "%~5"=="" if not "%~4"=="" (
        call :run_gradle popeRegistryAdd "-PregistryPrefix=%~3" "-PcatalogUrl=%~4"
        goto :eof
    )
    if not "%~5"=="" (
        call :run_gradle popeRegistryAdd "-PregistryPrefix=%~3" "-PcatalogUrl=%~4" "-PregistryName=%~5"
        goto :eof
    )
    goto usage
)

if /I "%COMMAND%"=="prune" (
    if "%~2"=="" (
        call :run_gradle popePrune
    ) else if /I "%~2"=="--dry-run" (
        call :run_gradle popePrune -PpopeDryRun
    ) else (
        goto usage
    )
    goto :eof
)

goto usage

:run_gradle
if "%USE_SUBDIR%"=="1" (
    call "%GRADLEW%" -p "%POPE_SUBDIR%" %*
) else (
    call "%GRADLEW%" %*
)
echo.
goto :eof

:usage
echo Usage (running against %PROJECT_ROOT%):
echo   pope install                          resolve declared dependencies
echo   pope install ^<package^>[:^<versionSpec^>] add + resolve a dependency in one step
echo   pope uninstall ^<package^>              remove a dependency and clean up its files
echo   pope propath [--tests]                 print the generated PROPATH
echo                                          (--tests also includes buildPath's "test" entries)
echo   pope registry add [^<prefix^> ^<url^> [^<name^>]]  add a registry to pope-registries.properties
echo                                          (interactive if prefix/url are omitted)
echo   pope prune [--dry-run]                 remove pope_packages/ entries no longer part of
echo                                          the resolved dependency graph
exit /b 1
