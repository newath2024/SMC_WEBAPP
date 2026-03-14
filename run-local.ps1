$ErrorActionPreference = "Stop"

$env:SPRING_PROFILES_ACTIVE = "local"
if (-not $env:LOCAL_SERVER_PORT) {
    $env:LOCAL_SERVER_PORT = "8082"
}

mvn spring-boot:run
