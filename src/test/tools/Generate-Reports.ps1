param(
    [switch]$SkipBuild,
    [switch]$IsolatedBuild,
    [string]$OutputDirectory = 'src/test/output/reports'
)

$ErrorActionPreference = 'Continue'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
Push-Location $projectRoot

try {
    $buildDirectory = 'target'
    $verificationArguments = @('verify', '-q')
    if ($IsolatedBuild) {
        $buildDirectory = 'target/isolated'
        $verificationArguments = @('-Pisolated-build', 'verify', '-q')
    }
    if (-not $SkipBuild) {
        & mvn @verificationArguments
        if ($LASTEXITCODE -ne 0) {
            throw 'Maven verification failed'
        }
    }

    & mvn dependency:build-classpath '-Dmdep.outputFile=target/test-classpath.txt' -q
    if ($LASTEXITCODE -ne 0) {
        throw 'Test classpath build failed'
    }

    $testClasspath = (Get-Content target/test-classpath.txt -Raw).Trim()
    & java '-Djava.awt.headless=true' -cp "$buildDirectory/classes;$buildDirectory/test-classes;$testClasspath" dtm.pdfloom.examples.GenerateReports $OutputDirectory
    if ($LASTEXITCODE -ne 0) {
        throw 'Report generation failed'
    }
} finally {
    Pop-Location
}
