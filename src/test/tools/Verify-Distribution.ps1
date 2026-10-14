param(
    [string]$Version = '',
    [string]$OutputDirectory = 'src/test/output/reports',
    [string]$BuildDirectory = 'target'
)

$ErrorActionPreference = 'Continue'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
Push-Location $projectRoot

try {
    if (-not $Version) {
        $Version = (& mvn help:evaluate '-Dexpression=project.version' -q -DforceStdout | Out-String).Trim()
        if ($LASTEXITCODE -ne 0 -or -not $Version) {
            throw 'Project version could not be read from pom.xml'
        }
    }

    & mvn dependency:build-classpath '-DincludeScope=runtime' '-Dmdep.outputFile=target/runtime-classpath.txt' -q
    if ($LASTEXITCODE -ne 0) {
        throw 'Runtime classpath build failed'
    }

    & python src/test/tools/audit_dependencies.py
    if ($LASTEXITCODE -ne 0) {
        throw 'Runtime license audit failed'
    }

    $runtimeClasspath = (Get-Content target/runtime-classpath.txt -Raw).Trim()
    $reportsDirectory = $OutputDirectory
    New-Item -ItemType Directory -Force $reportsDirectory | Out-Null

    & java '-Djava.awt.headless=true' -cp "$BuildDirectory/pdfloom-$Version.jar;$runtimeClasspath" src/test/java/dtm/pdfloom/examples/Report.java "$reportsDirectory/example-ordinary.pdf"
    if ($LASTEXITCODE -ne 0) {
        throw 'Ordinary JAR smoke test failed'
    }

    & java '-Djava.awt.headless=true' -cp "$BuildDirectory/pdfloom-$Version-all.jar" src/test/java/dtm/pdfloom/examples/Report.java "$reportsDirectory/example-shaded.pdf"
    if ($LASTEXITCODE -ne 0) {
        throw 'Shaded JAR smoke test failed'
    }

    & java '-Djava.awt.headless=true' -cp "$BuildDirectory/pdfloom-$Version-all.jar" src/test/tools/SmokeCheck.java "$reportsDirectory/example-ordinary.pdf" "$reportsDirectory/example-shaded.pdf"
    if ($LASTEXITCODE -ne 0) {
        throw 'Generated distribution PDF validation failed'
    }

    & python src/test/tools/verify_distribution.py $Version $BuildDirectory
    if ($LASTEXITCODE -ne 0) {
        throw 'Distribution verification failed'
    }
} finally {
    Pop-Location
}
