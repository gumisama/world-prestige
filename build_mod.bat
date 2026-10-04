@echo off
setlocal EnableExtensions

set "FORGE_VERSION=47.4.10"
set "MC_VERSION=1.20.1"
set "WORK=%~dp0build_work"
set "OUT=%~dp0built"
set "ZIP=%WORK%\forge-mdk.zip"
set "URL=https://maven.minecraftforge.net/net/minecraftforge/forge/%MC_VERSION%-%FORGE_VERSION%/forge-%MC_VERSION%-%FORGE_VERSION%-mdk.zip"

if exist "%WORK%" rmdir /s /q "%WORK%"
if exist "%OUT%" rmdir /s /q "%OUT%"
mkdir "%WORK%"
mkdir "%OUT%"

echo [1/5] Downloading Forge MDK %MC_VERSION%-%FORGE_VERSION% ...
powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-WebRequest -Uri '%URL%' -OutFile '%ZIP%' -UseBasicParsing"
if errorlevel 1 goto :fail

echo [2/5] Extracting Forge MDK ...
powershell -NoProfile -ExecutionPolicy Bypass -Command "Expand-Archive -Path '%ZIP%' -DestinationPath '%WORK%' -Force"
if errorlevel 1 goto :fail

del /q "%ZIP%"

echo [3/5] Installing World Prestige source ...
xcopy "%~dp0build.gradle" "%WORK%\build.gradle" /Y >nul
xcopy "%~dp0gradle.properties" "%WORK%\gradle.properties" /Y >nul
xcopy "%~dp0settings.gradle" "%WORK%\settings.gradle" /Y >nul
if exist "%WORK%\src" rmdir /s /q "%WORK%\src"
xcopy "%~dp0src" "%WORK%\src" /E /I /Y >nul

if not exist "%WORK%\gradlew.bat" (
    echo ERROR: Forge MDK did not contain gradlew.bat.
    goto :fail
)

echo [4/5] Building ...
pushd "%WORK%"
call gradlew.bat --no-daemon build
set "BUILD_RC=%ERRORLEVEL%"
popd
if not "%BUILD_RC%"=="0" goto :fail


echo [5/5] Copying JAR ...
copy "%WORK%\build\libs\worldprestige-0.3.0.jar" "%OUT%\worldprestige-0.3.0.jar" /Y >nul
if errorlevel 1 goto :fail

echo.
echo BUILD SUCCESSFUL
echo Output: %OUT%\worldprestige-0.3.0.jar
echo.
exit /b 0

:fail
echo.
echo BUILD FAILED. The temporary build directory was left at:
echo %WORK%
exit /b 1
