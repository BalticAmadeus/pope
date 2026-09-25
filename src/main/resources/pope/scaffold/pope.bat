@echo off
setlocal enabledelayedexpansion

set DIR=%~dp0
set POPE_SUBDIR=%DIR%.pope

rem Two layouts a scaffolded project can be in: Gradle's own files tucked
rem into .pope/ (new projects), or at this same root (legacy - e.g. the
rem real openedge-package-manager demo repo, which predates the .pope/
rem layout). Detected automatically so this one script works for both,
rem with no migration needed for existing projects. Invocation goes
rem through the :run_gradle subroutine below rather than building a
rem "-p ..." flag into a variable directly - embedding quotes inside a
rem batch variable's value doesn't substitute reliably at the call site.
if exist "%POPE_SUBDIR%\gradlew.bat" (
    set GRADLEW=%POPE_SUBDIR%\gradlew.bat
    set USE_SUBDIR=1
) else (
    set GRADLEW=%DIR%gradlew.bat
    set USE_SUBDIR=0
)

if "%~1"=="" goto usage
set COMMAND=%~1

if /I "%COMMAND%"=="version" (
    if not "%~2"=="" goto usage
    call :run_gradle popeVersion
    goto :eof
)

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
goto :eof

:usage
echo Usage:
echo   pope version                          print the installed pope plugin version
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
