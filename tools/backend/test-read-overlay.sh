#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "$0")/../.." && pwd)
# build-sdk.sh must export and build the pinned source first.
export CARGO_TARGET_DIR="$root/core/sdk/build/native/target"
export CARGO_BUILD_JOBS=2
cd "$root/core/sdk/build/native/source"
cargo test --locked -p tuta-sdk velin_
