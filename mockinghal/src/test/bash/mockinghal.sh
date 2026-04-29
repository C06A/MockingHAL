#!/usr/bin/env bash

set -euo pipefail

_SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
_ROOT_DIR="$(cd "${_SELF_DIR}/../../.." && pwd)"

HAL_LIB_DIR="${_ROOT_DIR}/build/haldish"

if [ -f "$HAL_LIB_DIR/env.sh" ]
then
  . $HAL_LIB_DIR/env.sh
else
  echo 'mockinghal.sh: HALDiSh dependency is not setup. Execute task ":mockinghal:setup"'
  exit 1
fi

cwd=$HAL_LIB_DIR/../mockinghal-test
hal::fs::is_dir "$cwd" || hal::fs::mkdir_p "$cwd"
cd "$cwd"

ROOT_URL="http://localhost:8080"

GET $ROOT_URL \
  | rename.sh root \
  | cleanup.sh -- body curl status code \
  | prettyprint.sh >/dev/null
(( "$(cat root.code)" == "200" )) || (hal::log::error "Fail to get Root resource"; exit 100)
hal::log::ok "Got Root resource"

(export HTTP_IN_HEADERS="Accept:$(hal.sh root.json links mocking:hal type)"; \
 GET "$ROOT_URL$(hal.sh root.json links mocking:hal href)" \
  | rename.sh root-hal+json \
  | cleanup.sh -- body curl status code \
  | prettyprint.sh >/dev/null
)
(( "$(cat root-hal+json.code)" == "200" )) || (hal::log::error "Fail to get Root HAL resource"; exit 110)
hal::log::ok "Got Root HAL resource"

(export HTTP_IN_HEADERS="Accept:$(hal.sh root.json links mocking:xml type)"; \
 GET "$ROOT_URL$(hal.sh root.json links mocking:xml href)" \
  | rename.sh root-hal+xml \
  | cleanup.sh -- body curl status code \
  | prettyprint.sh >/dev/null
)
(( "$(cat root-hal+xml.code)" == "200" )) || (hal::log::error "Fail to get XML Root resource"; exit 120)
hal::log::ok "Got Root XML resource"

(export HTTP_IN_HEADERS="Accept:$(hal.sh root.json links mocking:yaml type)"; \
 GET "$ROOT_URL$(hal.sh root.json links mocking:yaml href)" \
  | rename.sh root-hal+yaml \
  | cleanup.sh -- body curl status code \
  | prettyprint.sh >/dev/null
)
(( "$(cat root-hal+yaml.code)" == "200" )) || (hal::log::error "Fail to get Root YAML resource"; exit 130)
hal::log::ok "Got Root YAML resource"
