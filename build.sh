# Compiles SpecConvert and packages it into target/spec-convert.jar
# Requires Maven; dependencies are resolved from configured repositories.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "Building with Maven..."
mvn -f "$SCRIPT_DIR/pom.xml" package -q

echo "Build complete"
