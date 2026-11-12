# /// script
# requires-python = ">=3.10"
# dependencies = ["embit==0.8.0"]
# ///
"""Fund an address on Spark's regtest chain from the shared test wallet.

Use it on a dev build running on Spark's chain (see `spark-regtest-esplora.py`): fund the Savings
address to test Savings to Spending and the deposit claim, or Spark's deposit address on the Spark
screen to fund Spending directly. It spends from the wallet behind the SPARK_REGTEST_MNEMONIC
secret (BIP84 m/84'/1'/0'/0/0, one P2WPKH address for receive and change) and broadcasts through
Lightspark's regtest mempool API. Lightspark mines about once a minute.

Usage:
  SPARK_REGTEST_MNEMONIC="<12 words>" uv run scripts/spark-regtest-fund.py <bcrt1… address> [sats]
  SPARK_REGTEST_MNEMONIC="<12 words>" uv run scripts/spark-regtest-fund.py --balance

Env: SPARK_REGTEST_API (default https://regtest-mempool.us-west-2.sparkinfra.net/api),
     SPARK_REGTEST_FEERATE (sat/vB, default 2).
"""
import json
import os
import random
import sys
import urllib.error
import urllib.request

from embit import bip32, bip39, script
from embit.networks import NETWORKS
from embit.transaction import SIGHASH, Transaction, TransactionInput, TransactionOutput

NET = NETWORKS["regtest"]
API = os.environ.get("SPARK_REGTEST_API", "https://regtest-mempool.us-west-2.sparkinfra.net/api").rstrip("/")
FEERATE = int(os.environ.get("SPARK_REGTEST_FEERATE", "2"))
DUST = 546


class BroadcastError(Exception):
    pass


def http(method, path, body=None):
    # The API answers a broadcast without text/plain with a bare "code -1"
    request = urllib.request.Request(API + path, data=body, method=method,
                                     headers={"User-Agent": "spark-regtest-fund", "Content-Type": "text/plain"})
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return response.read().decode()
    except urllib.error.HTTPError as e:
        message = f"{method} {path} -> {e.code}: {e.read().decode()}"
        if method == "POST":
            raise BroadcastError(message)
        sys.exit(message)


def wallet():
    words = os.environ.get("SPARK_REGTEST_MNEMONIC", "").strip()
    if not words:
        sys.exit("Set SPARK_REGTEST_MNEMONIC to the shared Spark regtest wallet's recovery phrase")
    root = bip32.HDKey.from_seed(bip39.mnemonic_to_seed(words), version=NET["xprv"])
    key = root.derive("m/84h/1h/0h/0/0")
    spk = script.p2wpkh(key.get_public_key())
    return key, spk, spk.address(NET)


def vsize(inputs, dest_spk):
    # 10.5 overhead + 68 per P2WPKH input + destination output + P2WPKH change output, rounded up
    return 11 + 68 * inputs + 9 + len(dest_spk.data) + 31 + 2


def pick(coins, amount, dest_spk, skip):
    # A random confirmed coin that covers the send, so CI jobs and agents spending at once
    # rarely pick the same one; else the largest confirmed coins together
    coins = [c for c in coins if c["status"]["confirmed"] and f'{c["txid"]}:{c["vout"]}' not in skip]
    fee = FEERATE * vsize(1, dest_spk)
    single = [c for c in coins if c["value"] >= amount + fee]
    if single:
        return [random.choice(single)], fee
    picked, total = [], 0
    for coin in sorted(coins, key=lambda c: -c["value"]):
        picked.append(coin)
        total += coin["value"]
        fee = FEERATE * vsize(len(picked), dest_spk)
        if total >= amount + fee:
            return picked, fee
    sys.exit(f"Insufficient confirmed funds: have {total} sats, need {amount} + fee")


def send(key, spk, address, dest, amount):
    dest_spk = script.address_to_scriptpubkey(dest)
    coins = json.loads(http("GET", f"/address/{address}/utxo"))
    skip = set()
    for attempt in range(3):
        picked, fee = pick(coins, amount, dest_spk, skip)
        change = sum(c["value"] for c in picked) - amount - fee
        outputs = [TransactionOutput(amount, dest_spk)]
        if change >= DUST:
            outputs.append(TransactionOutput(change, spk))
        tx = Transaction(vin=[TransactionInput(bytes.fromhex(c["txid"]), c["vout"]) for c in picked], vout=outputs)
        pub = key.get_public_key()
        for i, coin in enumerate(picked):
            sighash = tx.sighash_segwit(i, script.p2pkh(pub), coin["value"])
            tx.vin[i].witness = script.Witness([key.key.sign(sighash).serialize() + bytes([SIGHASH.ALL]), pub.sec()])
        try:
            return http("POST", "/tx", tx.serialize().hex().encode()).strip()
        except BroadcastError as e:
            # The address index lags the mempool: a coin another sender just spent still shows
            print(f"{e}; retrying with another coin", file=sys.stderr)
            skip.update(f'{c["txid"]}:{c["vout"]}' for c in picked)
    sys.exit("Broadcast failed three times")


def main():
    args = sys.argv[1:]
    if not args or args[0] in ("-h", "--help"):
        sys.exit(__doc__)
    key, spk, address = wallet()
    if args[0] == "--balance":
        coins = json.loads(http("GET", f"/address/{address}/utxo"))
        confirmed = sum(c["value"] for c in coins if c["status"]["confirmed"])
        print(json.dumps({"address": address, "confirmed": confirmed, "unconfirmed": sum(c["value"] for c in coins) - confirmed}))
        return
    amount = int(args[1]) if len(args) > 1 else 100_000
    print(send(key, spk, address, args[0], amount))


if __name__ == "__main__":
    main()
