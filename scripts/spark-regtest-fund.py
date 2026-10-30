#!/usr/bin/env python3
"""Fund an address on Spark's regtest chain from Lightspark's regtest faucet.

Use it on a dev build running on Spark's chain (see `spark-regtest-esplora.py`): fund the Savings
address to test Savings to Spending and the deposit claim, or Spark's deposit address on the Spark
screen to fund Spending directly. Lightspark's API faucet takes HTTP basic auth: set
FAUCET_USERNAME and FAUCET_PASSWORD (issued by Lightspark). The web faucet at
https://app.lightspark.com/regtest-faucet works without them, by hand.

Usage:
  FAUCET_USERNAME=… FAUCET_PASSWORD=… python3 scripts/spark-regtest-fund.py <bcrt1… address> [sats]
"""
import base64
import json
import os
import sys
import urllib.request

FAUCET_URL = os.environ.get("FAUCET_URL", "https://api.lightspark.com/graphql/spark/rc")
QUERY = (
    "mutation RequestRegtestFunds($address: String!, $amount_sats: Long!) "
    "{ request_regtest_funds(input: {address: $address, amount_sats: $amount_sats}) { transaction_hash } }"
)


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    address = sys.argv[1]
    amount_sats = int(sys.argv[2]) if len(sys.argv) > 2 else 100_000
    username, password = os.environ.get("FAUCET_USERNAME"), os.environ.get("FAUCET_PASSWORD")
    if not username or not password:
        sys.exit("Set FAUCET_USERNAME and FAUCET_PASSWORD, or use https://app.lightspark.com/regtest-faucet")
    body = json.dumps({
        "operationName": "RequestRegtestFunds",
        "variables": {"address": address, "amount_sats": amount_sats},
        "query": QUERY,
    }).encode()
    request = urllib.request.Request(FAUCET_URL, data=body, method="POST")
    request.add_header("Content-Type", "application/json")
    request.add_header("Authorization", "Basic " + base64.b64encode(f"{username}:{password}".encode()).decode())
    with urllib.request.urlopen(request, timeout=60) as response:
        result = json.load(response)
    if result.get("errors"):
        sys.exit("Faucet returned errors: " + ", ".join(e["message"] for e in result["errors"]))
    print(result["data"]["request_regtest_funds"]["transaction_hash"])


if __name__ == "__main__":
    main()
