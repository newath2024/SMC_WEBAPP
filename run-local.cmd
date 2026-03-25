@echo off
set SCRIPT_DIR=%~dp0
if exist "%SCRIPT_DIR%.env.local" (
    for /f "usebackq eol=# tokens=1,* delims==" %%A in ("%SCRIPT_DIR%.env.local") do (
        if not "%%~A"=="" set "%%~A=%%~B"
    )
)

set SPRING_PROFILES_ACTIVE=local
if "%LOCAL_SERVER_PORT%"=="" set LOCAL_SERVER_PORT=8082

if "%OPENAI_API_KEY%"=="" (
    echo OPENAI_API_KEY is not set. TradingView screenshot import will stay disabled.
) else (
    echo OPENAI_API_KEY loaded. TradingView screenshot import is enabled.
)

if exist "%SCRIPT_DIR%mvnw.cmd" (
    call "%SCRIPT_DIR%mvnw.cmd" spring-boot:run
) else (
    mvn spring-boot:run
)
