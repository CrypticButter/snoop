#!/usr/bin/env bash

set -e

if [[ $GITHUB_ACTIONS == "" ]]; then
  echo "This script is supposed to run on CI, not in your dev machine."
  exit 1
fi

do_cleanup() {
  patterns=(xvfb chromium java)
  for pattern in ${patterns[*]}; do
    pkill -f $pattern || true
  done
}

trap do_cleanup EXIT

# Start funnel for kaocha-cljs2
nohup clojure -Sdeps '{:deps {lambdaisland/funnel {:mvn/version "0.1.42"}}}' -m lambdaisland.funnel -v &

# Setup chromium flags to use in CI environment
export CHROMIUM_USER_FLAGS="--no-first-run --no-default-browser-check"
if [[ $whoami == "root" ]]; then
  export CHROMIUM_USER_FLAGS="$CHROMIUM_USER_FLAGS --no-sandbox"
fi

nohup xvfb-run -e /dev/stdout --server-args=":99.0 -screen 0 1360x1020x24 -ac +extension RANDR" chromium-browser &
export DISPLAY=:99.0
bb test cljs
