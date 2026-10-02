#!/usr/bin/env bash
# Compiles SpecConvert and packages it into target/spec-convert.jar
# Requires Maven; installs the shaded 0.8 SDK dependency into the local Maven repository if not present.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Ensure the 0.8 SDK shaded coordinate is installed locally
echo "Ensuring 0.8 SDK dependency is installed..."
mvn dependency:get -q -Dartifact=io.serverlessworkflow:serverlessworkflow-api:4.1.0.Final
mvn install:install-file -q \
  -Dfile="${HOME}/.m2/repository/io/serverlessworkflow/serverlessworkflow-api/4.1.0.Final/serverlessworkflow-api-4.1.0.Final.jar" \
  -DgroupId=io.serverlessworkflow.v08 \
  -DartifactId=serverlessworkflow-api \
  -Dversion=4.1.0.Final \
  -Dpackaging=jar

echo "Building with Maven..."
mvn -f "$SCRIPT_DIR/pom.xml" package -q

echo "Build complete"
