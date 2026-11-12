#!/usr/bin/env bash
# NEIS launcher — now a shim to the Core v1.0 platform launcher.
#
# The MVP chain (mvp-chain / mvp-addresses) is retired: the same SSAFP
# stages (WEBSCRAPE - SIMPLIFY - ANALYZE - FILTER - PRINT) run as the
# configured chain core-chain-ssafp through the platform's executor.
# See core/ (kit) and core/README.md.
exec "$(dirname "$0")/core/start.sh" "$@"
