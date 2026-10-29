// Bitkit's bridge to Arkade: runs Ark Labs' TypeScript wallet, swap client and LNURL client inside a
// hidden WebView, and answers the native app over one JSON message channel.
//
// Native -> JS: window.bitkitArk.call(id, method, paramsJson)
// JS -> native: one JSON string per message, {id, ok, result|error} for calls and {event, ...} for events,
//   posted to window.BitkitArkNative.postMessage (Android) or webkit.messageHandlers.bitkitArk (iOS).
import {
    IndexedDBContractRepository,
    IndexedDBWalletRepository,
    MnemonicIdentity,
    Ramps,
    RestDelegateProvider,
    Wallet,
} from "@arkade-os/sdk";
import { createSwapClient, IndexedDbAssetSwapRepository } from "@arkade-os/swap";
import { arkadeLnurl } from "@arkade-os/lnurl-client/arkade";
import { HDKey } from "@scure/bip32";
import { mnemonicToSeedSync } from "@scure/bip39";
import betaSolverCard from "./beta-solver.card.json";

const ARK_ACCOUNT = 1; // Savings' Taproot keys use account 0'
const DB_PREFIX = "bitkit-ark";
const STATE_DATABASES = () => [`${DB_PREFIX}-wallet`, `${DB_PREFIX}-contracts`, `${DB_PREFIX}-swaps`];

let wallet;
let swaps;
let lnurl;
let config;
let receiver;
let lastHistoryKeys = null;

const post = (message) => {
    const json = JSON.stringify(message, (_, v) => (typeof v === "bigint" ? Number(v) : v));
    if (window.BitkitArkNative) window.BitkitArkNative.postMessage(json);
    else if (window.webkit?.messageHandlers?.bitkitArk) window.webkit.messageHandlers.bitkitArk.postMessage(json);
    else console.log("bitkitArk ->", json);
};
const log = (...args) => post({ event: "log", message: args.map(String).join(" ") });
const emit = (event, data = {}) => post({ event, ...data });

const requireWallet = () => {
    if (!wallet) throw new Error("Arkade wallet not started");
    return wallet;
};

const descriptorFor = (mnemonic, passphrase, isMainnet) => {
    const root = HDKey.fromMasterSeed(mnemonicToSeedSync(mnemonic, passphrase || undefined));
    const coin = isMainnet ? 0 : 1;
    const path = `86'/${coin}'/${ARK_ACCOUNT}'`;
    const account = root.derive(`m/${path}`);
    const fingerprint = root.fingerprint.toString(16).padStart(8, "0");
    return `tr([${fingerprint}/${path}]${account.publicExtendedKey}/0/*)`;
};

const swapSummary = (swap, outcome) => {
    const give = swap.route?.give;
    const take = swap.route?.take;
    const instrument = give?.instrument ?? take?.instrument;
    const direction = give?.corridor === "lightning" ? "receive" : take?.corridor === "lightning" ? "send" : "other";
    const invoice = (give?.instrument?.kind === "invoice" ? give.instrument : take?.instrument) ?? {};
    return {
        id: swap.id,
        direction,
        outcome: outcome ?? swap.outcome,
        giveSat: swap.give?.amount,
        takeSat: swap.take?.amount,
        feeSat: swap.fee?.amount,
        bolt11: invoice.bolt11 ?? instrument?.bolt11,
        paymentHash: invoice.paymentHash,
        createdAt: swap.createdAt,
        updatedAt: swap.updatedAt,
        blockedReason: swap.blockedReason,
    };
};

const balance = async () => {
    const b = await requireWallet().getBalance();
    return {
        available: b.available,
        settled: b.settled,
        preconfirmed: b.preconfirmed,
        recoverable: b.recoverable,
        boardingConfirmed: b.boarding.confirmed,
        boardingUnconfirmed: b.boarding.unconfirmed,
        total: b.total,
    };
};

const vtxos = async () => {
    const list = await requireWallet().getVtxos();
    return list.map((v) => ({
        txid: v.txid,
        vout: v.vout,
        value: v.value,
        state: v.virtualStatus?.state,
        batchExpiry: v.virtualStatus?.batchExpiry,
        createdAt: v.createdAt instanceof Date ? v.createdAt.getTime() : v.createdAt,
        isPreconfirmed: v.isPreconfirmed,
        isSwept: v.isSwept,
    }));
};

const history = async () => {
    const txs = await requireWallet().getTransactionHistory();
    return txs.map((t) => ({
        key: [t.key?.boardingTxid, t.key?.commitmentTxid, t.key?.arkTxid].filter(Boolean).join(":"),
        boardingTxid: t.key?.boardingTxid || undefined,
        commitmentTxid: t.key?.commitmentTxid || undefined,
        arkTxid: t.key?.arkTxid || undefined,
        type: t.type,
        tag: t.tag,
        amount: t.amount,
        settled: t.settled,
        createdAt: t.createdAt,
    }));
};

// Emits history entries that weren't there on the last pass, so native records offline receives
// (lnurl-server claims) and boarding/offboard settlements as they land.
const pollHistory = async () => {
    if (!wallet) return;
    try {
        const entries = await history();
        const keys = new Set(entries.map((e) => `${e.key}:${e.settled}`));
        if (lastHistoryKeys) {
            const fresh = entries.filter((e) => !lastHistoryKeys.has(`${e.key}:${e.settled}`));
            if (fresh.length) emit("history", { entries: fresh, balance: await balance() });
        }
        lastHistoryKeys = keys;
    } catch (e) {
        log("history poll failed", e?.message ?? e);
    }
};

// One claim at a time: two concurrent claims would each register an address, and the server allows one per wallet.
let receiverPromise;
const lightningAddress = async () => {
    if (!lnurl) throw new Error("Lightning address service not configured");
    receiverPromise ??= (async () => {
        const owned = await lnurl.owned({ refresh: true });
        if (owned) return owned;
        return lnurl.claim({}).catch(async (e) => {
            const raced = await lnurl.owned({ refresh: true });
            if (raced) return raced;
            throw e;
        });
    })();
    try {
        receiver = await receiverPromise;
    } catch (e) {
        receiverPromise = undefined;
        throw e;
    }
    return { handle: receiver.handle, lightningAddress: receiver.lightningAddress, lnurl: receiver.lnurl };
};

// IndexedDB dump/import for backup: the whole Arkade state as one JSON document.
const openDb = (name) =>
    new Promise((resolve, reject) => {
        const req = indexedDB.open(name);
        req.onsuccess = () => resolve(req.result);
        req.onerror = () => reject(req.error);
    });
const promisify = (req) =>
    new Promise((resolve, reject) => {
        req.onsuccess = () => resolve(req.result);
        req.onerror = () => reject(req.error);
    });
const encodeValue = (v) =>
    JSON.parse(
        JSON.stringify(v, function (k, val) {
            const raw = this[k];
            if (raw instanceof Uint8Array) return { $u8: btoa(String.fromCharCode(...raw)) };
            if (raw instanceof Date) return { $date: raw.getTime() };
            if (typeof raw === "bigint") return { $big: raw.toString() };
            return val;
        }),
    );
const decodeValue = (v) =>
    JSON.parse(JSON.stringify(v), (_, val) => {
        if (val && typeof val === "object") {
            if ("$u8" in val) return Uint8Array.from(atob(val.$u8), (c) => c.charCodeAt(0));
            if ("$date" in val) return new Date(val.$date);
            if ("$big" in val) return BigInt(val.$big);
        }
        return val;
    });

const exportState = async () => {
    const dump = { version: 1, databases: {} };
    const existing = new Set((await indexedDB.databases()).map((d) => d.name));
    for (const name of STATE_DATABASES()) {
        if (!existing.has(name)) continue;
        const db = await openDb(name);
        const stores = {};
        for (const storeName of db.objectStoreNames) {
            const tx = db.transaction(storeName, "readonly");
            const store = tx.objectStore(storeName);
            const [keys, values] = await Promise.all([promisify(store.getAllKeys()), promisify(store.getAll())]);
            stores[storeName] = {
                keyPath: store.keyPath,
                rows: values.map((value, i) => ({ key: store.keyPath ? undefined : encodeValue(keys[i]), value: encodeValue(value) })),
            };
        }
        dump.databases[name] = { version: db.version, stores };
        db.close();
    }
    return JSON.stringify(dump);
};

const importState = async (json) => {
    const dump = JSON.parse(json);
    let rows = 0;
    for (const [name, { stores }] of Object.entries(dump.databases ?? {})) {
        const db = await openDb(name);
        for (const [storeName, { rows: entries }] of Object.entries(stores)) {
            if (!db.objectStoreNames.contains(storeName)) continue;
            const tx = db.transaction(storeName, "readwrite");
            const store = tx.objectStore(storeName);
            for (const { key, value } of entries) {
                store.put(decodeValue(value), key === undefined ? undefined : decodeValue(key));
                rows++;
            }
            await new Promise((resolve, reject) => {
                tx.oncomplete = resolve;
                tx.onerror = () => reject(tx.error);
            });
        }
        db.close();
    }
    return { rows };
};

const methods = {
    async start(params) {
        if (wallet) return methods.info();
        config = params;
        const isMainnet = params.network === "bitcoin";
        const descriptor = descriptorFor(params.mnemonic, params.passphrase, isMainnet);
        const identity = MnemonicIdentity.fromMnemonic(params.mnemonic, { descriptor, passphrase: params.passphrase || undefined });
        const storage = {
            walletRepository: new IndexedDBWalletRepository(`${DB_PREFIX}-wallet`),
            contractRepository: new IndexedDBContractRepository(`${DB_PREFIX}-contracts`),
        };
        const swapRepository = new IndexedDbAssetSwapRepository(`${DB_PREFIX}-swaps`);
        if (params.backup) {
            // Reads first, so each database exists with its schema, then the restored rows go in before the wallet
            // and the swap client read them.
            await Promise.all([
                storage.walletRepository.getWalletState(),
                storage.contractRepository.getContractsPage(undefined, { limit: 1 }),
                swapRepository.getAssetSwapsPage({ limit: 1 }),
            ]).catch((e) => log("opening databases for import failed", e?.message ?? e));
            const imported = await importState(params.backup).catch((e) => ({ error: e?.message ?? String(e) }));
            log("imported Arkade backup", JSON.stringify(imported));
        }
        wallet = await Wallet.create({
            identity,
            arkServerUrl: params.arkServerUrl,
            delegateProvider: params.delegateUrl ? new RestDelegateProvider(params.delegateUrl) : undefined,
            storage,
        });
        if (params.restore) {
            const t = Date.now();
            await wallet.restore?.().catch((e) => log("restore scan failed", e?.message ?? e));
            log(`restore scan took ${Date.now() - t} ms`);
        }
        swaps = createSwapClient({
            wallet,
            repository: swapRepository,
            discovery: isMainnet ? { localCards: [{ card: betaSolverCard, network: "bitcoin" }] } : undefined,
        });
        await swaps.ready;
        swaps.onUpdate(({ swap, outcome }) => emit("swap", { swap: swapSummary(swap, outcome) }));
        if (params.lnurlUrl) lnurl = arkadeLnurl({ wallet, baseUrl: params.lnurlUrl });
        await pollHistory();
        setInterval(pollHistory, 15_000);
        return methods.info();
    },

    async info() {
        const w = requireWallet();
        const info = await w.getArkadeInfo();
        return {
            arkAddress: await w.getAddress(),
            boardingAddress: await w.getBoardingAddress(),
            network: info.network,
            dust: info.dust,
            unilateralExitDelay: info.unilateralExitDelay,
            boardingExitDelay: info.boardingExitDelay,
            fees: info.fees,
            delegate: Boolean(config?.delegateUrl),
        };
    },

    balance,
    vtxos,
    history,

    async swaps() {
        return (await swaps.swaps()).map((s) => swapSummary(s));
    },

    async markets() {
        return (await swaps.markets()).map((m) => ({ solver: m.solver, pair: m.pair, card: m.card }));
    },

    // Invoice for exactly amountSat: the payer pays it, Spending gets it less the solver's fee.
    async receive({ amountSat, maxFeeSat }) {
        const quote = await swaps.quote({ via: "lightning", amount: BigInt(amountSat), amountOn: "give" });
        const fee = quote.give.amount - quote.take.amount;
        if (maxFeeSat !== undefined && fee > BigInt(maxFeeSat)) throw new Error(`MaxFeeExceeded: ${fee} > ${maxFeeSat}`);
        const swap = await swaps.accept(quote);
        if (swap.artifact?.kind !== "invoice") throw new Error("expected an invoice");
        return { ...swapSummary(swap), bolt11: swap.artifact.bolt11, expiresAt: quote.expiresAt };
    },

    async quotePay({ bolt11 }) {
        const quote = await swaps.quote({ to: bolt11 });
        return { feeSat: quote.give.amount - quote.take.amount, giveSat: quote.give.amount, takeSat: quote.take.amount };
    },

    async pay({ bolt11, maxFeeSat }) {
        const result = await swaps.pay(bolt11, {
            maxFee: maxFeeSat !== undefined ? { amount: BigInt(maxFeeSat), asset: "arkade:bitcoin/slip44:0" } : undefined,
        });
        if (result.kind !== "swap") return { kind: result.kind, txid: result.txid };
        return { kind: "swap", ...swapSummary(result.swap) };
    },

    async sendArk({ address, amountSat }) {
        const txid = await requireWallet().send({ address, amount: Number(amountSat) });
        return { txid };
    },

    // Boards confirmed on-chain deposits now instead of waiting for the manager's next pass.
    async settle() {
        const w = requireWallet();
        const info = await w.getArkadeInfo();
        const boarding = await w.getBoardingUtxos();
        if (!boarding.length) return { txid: null };
        const txid = await new Ramps(w).onboard(info.fees, boarding);
        return { txid };
    },

    async renew() {
        const manager = await requireWallet().getVtxoManager();
        const txid = await manager.renewVtxos();
        return { txid };
    },

    async offboardFee() {
        const info = await requireWallet().getArkadeInfo();
        return { fees: info.fees };
    },

    async offboard({ address, amountSat }) {
        const w = requireWallet();
        const info = await w.getArkadeInfo();
        const txid = await new Ramps(w).offboard(address, info.fees, amountSat ? BigInt(amountSat) : undefined);
        return { txid };
    },

    lightningAddress,

    async delegateInfo() {
        const manager = await requireWallet().getDelegateManager();
        return manager ? { configured: true } : { configured: false };
    },

    exportState,
    importState: ({ json }) => importState(json),

    async wipe() {
        wallet = undefined;
        swaps = undefined;
        lnurl = undefined;
        receiver = undefined;
        receiverPromise = undefined;
        lastHistoryKeys = null;
        for (const name of STATE_DATABASES()) await promisify(indexedDB.deleteDatabase(name)).catch(() => {});
        return { wiped: true };
    },
};

window.bitkitArk = {
    async call(id, method, paramsJson) {
        try {
            const fn = methods[method];
            if (!fn) throw new Error(`unknown method ${method}`);
            const result = await fn(paramsJson ? JSON.parse(paramsJson) : {});
            post({ id, ok: true, result: result ?? null });
        } catch (e) {
            post({ id, ok: false, error: `${e?.name ?? "Error"}: ${e?.message ?? e}` });
        }
    },
};

window.addEventListener("unhandledrejection", (e) => log("unhandled", e.reason?.message ?? e.reason));
emit("ready");
