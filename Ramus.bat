@echo off
setlocal enabledelayedexpansion
rem Launcher for Ramus. Optional argument: a .rms model file to open
rem (you can also drag a .rms file onto this .bat).

set "APPDIR=%~dp0"
set "JAR=%APPDIR%dist\ramus.jar"

if not exist "%JAR%" (
    echo [Ramus] Not built yet: "%JAR%"
    echo         Build it with:  gradlew.bat :local-client:shadowJar
    echo         then copy local-client\build\libs\ramus.jar into dist\
    pause
    exit /b 1
)

set "JAVA_EXE="

rem 1) JAVA_HOME
if defined JAVA_HOME (
    if exist "%JAVA_HOME%\bin\javaw.exe" set "JAVA_EXE=%JAVA_HOME%\bin\javaw.exe"
)

rem 2) anything on PATH
if not defined JAVA_EXE (
    for %%J in (javaw.exe) do (
        if not defined JAVA_EXE if not "%%~$PATH:J"=="" set "JAVA_EXE=%%~$PATH:J"
    )
)

rem 3) runtimes commonly present on this machine
if not defined JAVA_EXE (
    for %%D in (
        "%USERPROFILE%\curseforge\minecraft\Install\java\Jre_21"
        "%ProgramFiles%\Eclipse Adoptium\jdk-21"
        "%ProgramFiles%\Eclipse Adoptium\jdk-17"
        "%ProgramFiles%\Java\jdk-21"
        "%ProgramFiles%\Java\jdk-17"
    ) do (
        if not defined JAVA_EXE if exist "%%~D\bin\javaw.exe" set "JAVA_EXE=%%~D\bin\javaw.exe"
    )
)

if not defined JAVA_EXE (
    echo [Ramus] Java not found.
    echo         Install a JRE/JDK 17+ or set JAVA_HOME, then run this file again.
    pause
    exit /b 1
)

start "Ramus" "%JAVA_EXE%" -Xmx1024m -jar "%JAR%" %*
endlocal
