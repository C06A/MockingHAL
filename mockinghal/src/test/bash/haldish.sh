#!/usr/bin/env bash

set -euo pipefail

_SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
_ROOT_DIR="$(cd "${_SELF_DIR}/../../.." && pwd)"

HAL_LIB_DIR="${_ROOT_DIR}/build/haldish"

. $HAL_LIB_DIR/env.sh

cwd=$HAL_LIB_DIR/../haldish_test
hal::fs::is_dir $cwd || hal::fs::mkdir_p $cwd
cd $cwd

ROOT_URL="http://localhost:8080"

GET $ROOT_URL \
  | rename.sh root \
  | prettyprint.sh \
  | cleanup.sh -- code curl json status >/dev/null
(( "$(cat root.code)" == "200" )) || (hal::log::error "Fail to get Root resource: $(cat root.status)"; exit 100)
hal::log::ok "Got Root resource"

POST $ROOT_URL$(hal.sh root.json links self href) \
    -f "$_SELF_DIR/../resources/haldish/hal_demo.yaml" \
  | rename.sh root_config \
  | prettyprint.sh \
  | cleanup.sh -- code curl status >/dev/null
(( "$(cat root_config.code)" == "201" )) || (hal::log::error "Fail to upload HAL demo configuration: $(cat root_config.status)"; exit 100)
hal::log::ok "Uploaded HAL demo configuration"

GET $ROOT_URL \
  | rename.sh haldish_root \
  | prettyprint.sh \
  | cleanup.sh -- code curl json status >/dev/null
(( "$(cat haldish_root.code)" == "200" )) || (hal::log::error "Fail to get HAL demo Root resource: $(cat haldish_root.status)"; exit 110)
hal::log::ok "Got HAL demo Root resource"

GET "${ROOT_URL}$(hal.sh haldish_root.json links demo:json href)" \
  | rename.sh haldish_json \
  | prettyprint.sh \
  | cleanup.sh -- code curl json status >/dev/null
(( "$(cat haldish_json.code)" == "200" )) || (hal::log::error "Fail to get HAL demo JSON resource: $(cat haldish_json.status)"; exit 120)
hal::log::ok "Got HAL demo JSON resource"

GET "${ROOT_URL}$(hal.sh haldish_root.json links demo:object href)" \
  | rename.sh haldish_object \
  | prettyprint.sh \
  | cleanup.sh -- code curl json status >/dev/null
(( "$(cat haldish_object.code)" == "200" )) || (hal::log::error "Fail to get HAL demo Object resource: $(cat haldish_object.status)"; exit 130)
hal::log::ok "Got HAL demo Object resource"

GET "${ROOT_URL}$(hal.sh haldish_root.json links demo:array href)" \
  | rename.sh haldish_array \
  | prettyprint.sh \
  | cleanup.sh -- code curl json status >/dev/null
(( "$(cat haldish_array.code)" == "200" )) || (hal::log::error "Fail to get HAL demo Array resource: $(cat haldish_array.status)"; exit 140)
hal::log::ok "Got HAL demo Array resource"

GET "${ROOT_URL}$(hal.sh haldish_root.json embeddeds demo:null links self href)" \
  | rename.sh haldish_null \
  | cleanup.sh -- code curl body headers status >/dev/null
(( "$(cat haldish_null.code)" == "204" )) || (hal::log::error "Fail to get HAL demo Null resource: $(cat haldish_null.status)"; exit 150)
(( $(cat haldish_null.body | wc -c) == 0 )) || (hal::log::error "Body of HAL demo Null resource is not empty: $(cat haldish_null.body)"; exit 151)
hal::log::ok "Got HAL demo Null resource"




DELETE $ROOT_URL \
  | rename.sh reset \
  | prettyprint.sh \
  | cleanup.sh -- code curl json status >/dev/null
(( "$(cat reset.code)" == "204" )) || (hal::log::error "Configuration was not reset: $(cat reset.status)"; exit 250)
hal::log::ok "Configuration reset"

GET $ROOT_URL \
  | rename.sh reset_root \
  | prettyprint.sh \
  | cleanup.sh -- code curl json status >/dev/null
(( "$(cat reset_root.code)" == "200" )) || (hal::log::error "Reset Root resource: $(cat reset_root.status)"; exit 251)
(( $(diff reset_root.json root.json | wc -l) == 0 )) || (hal::log::error "Reset Root resource mismatches original one:
$(diff reset_root.json root.json)"; exit 252)
hal::log::ok "Got reset Root resource"
