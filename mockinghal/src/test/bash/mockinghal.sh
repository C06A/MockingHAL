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
(( "$(cat root.code)" == "200" )) || (hal::log::error "Fail to get Root resource with status $(cat root.status)"; exit 100)
hal::log::ok "Got Root resource"

(export HTTP_IN_HEADERS="Accept:$(hal.sh root.json links mocking:hal type)"; \
 GET "$ROOT_URL$(hal.sh root.json links mocking:hal href)" \
  | rename.sh root-hal+json \
  | cleanup.sh -- body curl status code \
  | prettyprint.sh >/dev/null
)
(( "$(cat root-hal+json.code)" == "200" )) || (hal::log::error "Fail to get Root HAL resource with status $(cat root-hal+json.status)"; exit 110)
hal::log::ok "Got Root HAL resource"

(export HTTP_IN_HEADERS="Accept:$(hal.sh root.json links mocking:xml type)"; \
 GET "$ROOT_URL$(hal.sh root.json links mocking:xml href)" \
  | rename.sh root-hal+xml \
  | cleanup.sh -- body curl status code \
  | prettyprint.sh >/dev/null
)
(( "$(cat root-hal+xml.code)" == "200" )) || (hal::log::error "Fail to get XML Root resource with status $(cat root-hal+xml.status)"; exit 120)
hal::log::ok "Got Root XML resource"

(export HTTP_IN_HEADERS="Accept:$(hal.sh root.json links mocking:yaml type)"; \
 GET "$ROOT_URL$(hal.sh root.json links mocking:yaml href)" \
  | rename.sh root-hal+yaml \
  | cleanup.sh -- body curl status code \
  | prettyprint.sh >/dev/null
)
(( "$(cat root-hal+yaml.code)" == "200" )) || (hal::log::error "Fail to get Root YAML resource with status $(cat root-hal+yaml.status)"; exit 130)
hal::log::ok "Got Root YAML resource"


(export HTTP_IN_HEADERS="Accept:text/html"; \
 GET "$ROOT_URL$(hal.sh root.json docs mocking:hal)" \
  | rename.sh docs-hal+json \
  | cleanup.sh -- body curl status code \
  | prettyprint.sh >/dev/null
)
(( "$(cat docs-hal+json.code)" == "200" )) || (hal::log::error "Fail to get Root HAL resource with status $(cat docs-hal+json.status)"; exit 110)
hal::log::ok "Got documentation for HAL JSON"
open docs-hal+json.html

(export HTTP_IN_HEADERS="Accept:text/html"; \
 GET "$ROOT_URL$(hal.sh root.json docs mocking:yaml)" \
  | rename.sh docs-hal+yaml \
  | cleanup.sh -- body curl status code \
  | prettyprint.sh >/dev/null
)
(( "$(cat docs-hal+yaml.code)" == "200" )) || (hal::log::error "Fail to get Root HAL resource with status $(cat docs-hal+yaml.status)"; exit 110)
hal::log::ok "Got documentation for HAL YAML"
open docs-hal+yaml.html
