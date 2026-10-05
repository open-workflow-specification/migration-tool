#!/usr/bin/env bash
# Compiles SpecConvert and packages it into target/spec-convert.jar
# Requires Maven; installs the shaded 0.8 SDK dependency into the local Maven repository if not present.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

SDK_VERSION="${SDK_VERSION:-4.2.0.Final}"

# Ensure the 0.8 SDK shaded coordinate is installed locally
echo "Ensuring SDK dependency is installed (version ${SDK_VERSION})..."
mvn dependency:get -q -Dartifact=io.serverlessworkflow:serverlessworkflow-api:"${SDK_VERSION}"
mvn install:install-file -q \
  -Dfile="${HOME}/.m2/repository/io/serverlessworkflow/serverlessworkflow-api/${SDK_VERSION}/serverlessworkflow-api-${SDK_VERSION}.jar" \
  -DgroupId=io.serverlessworkflow.v08 \
  -DartifactId=serverlessworkflow-api \
  -Dversion="${SDK_VERSION}" \
  -Dpackaging=jar

echo "Building with Maven..."
mvn -f "$SCRIPT_DIR/pom.xml" package -q

echo "Build complete"
