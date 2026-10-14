#!/usr/bin/env bash
set -euo pipefail

usage() {
    echo "Uso: $0 [--skip-build] [--isolated-build] [--output DIRETORIO]"
}

skip_build=false
build_directory=target
maven_arguments=(verify -q)
output_directory=src/test/output/reports

while [ "$#" -gt 0 ]; do
    case "$1" in
        --skip-build)
            skip_build=true
            ;;
        --isolated-build)
            build_directory=target/isolated
            maven_arguments=(-Pisolated-build verify -q)
            ;;
        --output)
            output_directory="$2"
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

if [ "$skip_build" = false ]; then
    mvn "${maven_arguments[@]}"
fi

mvn dependency:build-classpath -Dmdep.outputFile=target/test-classpath.txt -q
test_classpath="$(tr -d '\r\n' < target/test-classpath.txt)"

java -Djava.awt.headless=true \
    -cp "$build_directory/classes:$build_directory/test-classes:$test_classpath" \
    dtm.pdfloom.examples.GenerateReports "$output_directory"
