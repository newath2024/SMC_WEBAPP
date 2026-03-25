$ErrorActionPreference = "Stop"

$scriptRoot = if ($PSScriptRoot) { $PSScriptRoot } else { (Get-Location).Path }
$envFile = Join-Path $scriptRoot ".env.local"

if (Test-Path $envFile) {
    Get-Content $envFile | ForEach-Object {
        $line = $_.Trim()
        if (-not $line -or $line.StartsWith("#")) {
            return
        }

        $separatorIndex = $line.IndexOf("=")
        if ($separatorIndex -lt 1) {
            return
        }

        $name = $line.Substring(0, $separatorIndex).Trim()
        $value = $line.Substring($separatorIndex + 1).Trim()

        if (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'"))) {
            $value = $value.Substring(1, $value.Length - 2)
        }

        [System.Environment]::SetEnvironmentVariable($name, $value, "Process")
    }
}

$env:SPRING_PROFILES_ACTIVE = "local"
if (-not $env:LOCAL_SERVER_PORT) {
    $env:LOCAL_SERVER_PORT = "8082"
}

if ($env:OPENAI_API_KEY) {
    Write-Host "OPENAI_API_KEY loaded. TradingView screenshot import is enabled." -ForegroundColor Green
} else {
    Write-Host "OPENAI_API_KEY is not set. TradingView screenshot import will stay disabled." -ForegroundColor Yellow
}

$mavenWrapper = Join-Path $scriptRoot "mvnw.cmd"
if (Test-Path $mavenWrapper) {
    & $mavenWrapper "spring-boot:run"
} else {
    mvn spring-boot:run
}
