#!/usr/bin/env bash
set -euo pipefail

usage() {
    echo "Uso: $0 [--version VERSAO (padrão: versão do pom.xml)] [--output DIRETORIO] [--build-directory DIRETORIO]"
}

version=
output_directory=src/test/output/reports
build_directory=target

while [ "$#" -gt 0 ]; do
    case "$1" in
        --version)
            version="$2"
            shift
            ;;
        --output)
            output_directory="$2"
            shift
            ;;
        --build-directory)
            build_directory="$2"
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            usage
            exit 2
            ;;
    esac
    shift
done

cd "$(dirname "$0")/../../.."

if [ -z "$version" ]; then
    version="$(mvn help:evaluate -Dexpression=project.version -q -DforceStdout)"
fi

if command -v python3 > /dev/null 2>&1; then
    python=python3
else
    python=python
fi

mvn dependency:build-classpath -DincludeScope=runtime -Dmdep.outputFile=target/runtime-classpath.txt -q
"$python" src/test/tools/audit_dependencies.py

runtime_classpath="$(tr -d '\r\n' < target/runtime-classpath.txt)"
mkdir -p "$output_directory"

java -Djava.awt.headless=true \
    -cp "$build_directory/pdfloom-$version.jar:$runtime_classpath" \
    src/test/java/dtm/pdfloom/examples/Report.java "$output_directory/example-ordinary.pdf"

java -Djava.awt.headless=true \
    -cp "$build_directory/pdfloom-$version-all.jar" \
    src/test/java/dtm/pdfloom/examples/Report.java "$output_directory/example-shaded.pdf"

java -Djava.awt.headless=true \
    -cp "$build_directory/pdfloom-$version-all.jar" \
    src/test/tools/SmokeCheck.java "$output_directory/example-ordinary.pdf" "$output_directory/example-shaded.pdf"

"$python" src/test/tools/verify_distribution.py "$version" "$build_directory"
