@echo off
set SPRING_PROFILES_ACTIVE=local
if "%LOCAL_SERVER_PORT%"=="" set LOCAL_SERVER_PORT=8082

if exist mvnw.cmd (
    call mvnw.cmd spring-boot:run
) else (
    mvn spring-boot:run
)
