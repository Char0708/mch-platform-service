$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$toolRoot = 'F:\CodexSystemTools\maternal-child-health-platform\env-001'
$largeDataRoot = 'H:\DevEnvironment'
$jdkArchiveName = 'OpenJDK21U-jdk_x64_windows_hotspot_21.0.12_8.zip'
$jdkArchiveSha256 = '9ba963ee2371874a74185d18bc7bb2ab9407df7683300855ed7606e0662321d0'
$jdkArchiveUrl = "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12%2B8/$jdkArchiveName"
$jdkHome = Join-Path $toolRoot 'java\jdk-21.0.12+8'
$jdkArchive = Join-Path $toolRoot "downloads\$jdkArchiveName"
$mavenUserHome = Join-Path $largeDataRoot 'Maven\user-home'
$mavenLocalRepository = Join-Path $largeDataRoot 'Maven\repository'
$taskTempRoot = Join-Path $largeDataRoot 'Temp\ENV-001'
$mysqlImage = 'mysql:8.4'

function Invoke-CheckedCommand {
    param(
        [Parameter(Mandatory)]
        [string]$Description,
        [Parameter(Mandatory)]
        [scriptblock]$Command
    )

    & $Command
    if ($LASTEXITCODE -ne 0) {
        throw "$Description failed with exit code $LASTEXITCODE."
    }
}

New-Item -ItemType Directory -Force -Path (Split-Path -Parent $jdkArchive), (Split-Path -Parent $jdkHome), $mavenUserHome, $mavenLocalRepository, $taskTempRoot | Out-Null

if (-not (Test-Path -LiteralPath $jdkArchive -PathType Leaf)) {
    "Downloading Eclipse Temurin 21 from $jdkArchiveUrl" | Write-Output
    Invoke-WebRequest -Uri $jdkArchiveUrl -OutFile $jdkArchive
}

$actualJdkArchiveSha256 = (Get-FileHash -LiteralPath $jdkArchive -Algorithm SHA256).Hash.ToLowerInvariant()
if ($actualJdkArchiveSha256 -ne $jdkArchiveSha256) {
    throw "Temurin archive SHA-256 mismatch. Expected $jdkArchiveSha256, found $actualJdkArchiveSha256."
}

$javaExecutable = Join-Path $jdkHome 'bin\java.exe'
if (-not (Test-Path -LiteralPath $javaExecutable -PathType Leaf)) {
    Expand-Archive -LiteralPath $jdkArchive -DestinationPath (Split-Path -Parent $jdkHome) -Force
}
if (-not (Test-Path -LiteralPath $javaExecutable -PathType Leaf)) {
    throw "Portable Java 21 extraction did not create the expected executable: $javaExecutable"
}

$previousJavaHome = $env:JAVA_HOME
$previousPath = $env:PATH
$previousMavenUserHome = $env:MAVEN_USER_HOME
$previousTemp = $env:TEMP
$previousTmp = $env:TMP

try {
    $env:JAVA_HOME = $jdkHome
    $env:PATH = "$(Join-Path $jdkHome 'bin');$previousPath"
    $env:MAVEN_USER_HOME = $mavenUserHome
    $env:TEMP = $taskTempRoot
    $env:TMP = $taskTempRoot

    "Maven user home: $mavenUserHome" | Write-Output
    "Maven local repository: $mavenLocalRepository" | Write-Output
    "Task temporary directory: $taskTempRoot" | Write-Output

    $javaVersion = & $javaExecutable -version 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0 -or $javaVersion -notmatch 'version "21\.0\.12"') {
        throw "Portable Java version check failed:`n$javaVersion"
    }
    $javaVersion.TrimEnd() | Write-Output

    Push-Location $repositoryRoot
    try {
        $mavenVersion = & .\mvnw.cmd -version 2>&1 | Out-String
        if ($LASTEXITCODE -ne 0 -or $mavenVersion -notmatch 'Apache Maven 3\.9\.16' -or $mavenVersion -notmatch 'Java version: 21\.0\.12') {
            throw "Maven Wrapper version check failed:`n$mavenVersion"
        }
        $mavenVersion.TrimEnd() | Write-Output

        & pwsh -NoLogo -NoProfile -File scripts/verify-engineering-boundaries.ps1
        if ($LASTEXITCODE -ne 0) {
            throw "Engineering boundary verification failed with exit code $LASTEXITCODE."
        }

        $dockerCommand = Get-Command docker -ErrorAction SilentlyContinue
        $dockerExecutable = if ($dockerCommand) {
            $dockerCommand.Source
        }
        else {
            'H:\DevEnvironment\Docker\Desktop\resources\bin\docker.exe'
        }
        if (-not (Test-Path -LiteralPath $dockerExecutable -PathType Leaf)) {
            throw 'Docker-compatible container client is unavailable; MySQL 8.4 Testcontainers verification cannot run.'
        }
        $dockerBin = Split-Path -Parent $dockerExecutable
        if ($dockerBin -notin ($env:PATH -split ';')) {
            $env:PATH = "$dockerBin;$env:PATH"
        }

        $dockerServerVersion = & $dockerExecutable version --format '{{.Server.Version}}' 2>&1 | Out-String
        if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($dockerServerVersion)) {
            throw "Docker-compatible container daemon is unavailable:`n$dockerServerVersion"
        }
        "Container daemon version: $($dockerServerVersion.Trim())" | Write-Output

        Invoke-CheckedCommand -Description "Pulling $mysqlImage" -Command {
            & $dockerExecutable pull $mysqlImage
        }

        $imageId = & $dockerExecutable image inspect $mysqlImage --format '{{.Id}}' 2>&1 | Out-String
        if ($LASTEXITCODE -ne 0 -or $imageId -notmatch '^sha256:') {
            throw "MySQL image inspection failed:`n$imageId"
        }
        "Verified image $mysqlImage as $($imageId.Trim())." | Write-Output

        $containersBefore = @(& $dockerExecutable ps --filter "label=org.testcontainers=true" --filter "ancestor=$mysqlImage" --format '{{.ID}}')
        if ($LASTEXITCODE -ne 0) {
            throw 'Unable to inventory pre-existing Testcontainers MySQL containers.'
        }

        Invoke-CheckedCommand -Description 'Maven verification' -Command {
            & .\mvnw.cmd -B --no-transfer-progress "-Dmaven.repo.local=$mavenLocalRepository" verify
        }

        $reportPath = Join-Path $repositoryRoot 'target\surefire-reports\TEST-io.github.char0708.mch.platform.MchPlatformServiceApplicationTests.xml'
        if (-not (Test-Path -LiteralPath $reportPath -PathType Leaf)) {
            throw "Surefire report is missing: $reportPath"
        }

        [xml]$report = Get-Content -LiteralPath $reportPath -Raw
        $suite = $report.testsuite
        if ([int]$suite.tests -ne 3 -or [int]$suite.failures -ne 0 -or [int]$suite.errors -ne 0 -or [int]$suite.skipped -ne 0) {
            throw "Expected exactly three passing tests; found tests=$($suite.tests), failures=$($suite.failures), errors=$($suite.errors), skipped=$($suite.skipped)."
        }

        $expectedTests = @('contextLoads', 'engineeringSchemaProbeIsMigrated', 'moduleStructureIsValid')
        $actualTests = @($suite.testcase | ForEach-Object { $_.name })
        foreach ($expectedTest in $expectedTests) {
            if ($expectedTest -notin $actualTests) {
                throw "Expected test is absent from the Surefire report: $expectedTest"
            }
        }

        $containersAfter = @(& $dockerExecutable ps --filter "label=org.testcontainers=true" --filter "ancestor=$mysqlImage" --format '{{.ID}}')
        if ($LASTEXITCODE -ne 0) {
            throw 'Unable to inventory Testcontainers MySQL containers after verification.'
        }
        $newResidualContainers = @($containersAfter | Where-Object { $_ -and $_ -notin $containersBefore })
        if ($newResidualContainers.Count -gt 0) {
            throw "Verification left new MySQL Testcontainers running: $($newResidualContainers -join ', ')."
        }

        'LOCAL_ENVIRONMENT_VERIFICATION=PASSED' | Write-Output
    }
    finally {
        Pop-Location
    }
}
finally {
    $env:JAVA_HOME = $previousJavaHome
    $env:PATH = $previousPath
    $env:MAVEN_USER_HOME = $previousMavenUserHome
    $env:TEMP = $previousTemp
    $env:TMP = $previousTmp
}
