# Runs SpecConvert. Build first with ./build.sh
# Usage: ./run.sh <input-file> [output-file]
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="$SCRIPT_DIR/target/spec-convert.jar"

if [[ ! -f "$JAR" ]]; then
  echo "Error: $JAR not found. Run ./build.sh first." >&2
  exit 1
fi

java -jar "$JAR" "$@"
