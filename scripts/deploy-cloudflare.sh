#!/usr/bin/env zsh
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
python3 "${ROOT_DIR}/cloudflare-worker/extract-worker.py"
cd "${ROOT_DIR}/cloudflare-worker"
npx --yes wrangler deploy
