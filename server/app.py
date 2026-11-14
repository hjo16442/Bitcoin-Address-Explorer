import os
import sqlite3
import time
import threading
from typing import Any

import requests
from fastapi import FastAPI, HTTPException, Query

RPC_URL = os.getenv("BTC_RPC_URL", "http://127.0.0.1:8332")
RPC_USER = os.getenv("BTC_RPC_USER", "")
RPC_PASSWORD = os.getenv("BTC_RPC_PASSWORD", "")
DB_PATH = os.getenv("DB_PATH", "/data/addresses.sqlite3")
SCAN_INTERVAL = int(os.getenv("SCAN_INTERVAL_SECONDS", "60"))
RPC_TIMEOUT = int(os.getenv("BTC_RPC_TIMEOUT_SECONDS", "120"))

app = FastAPI(title="Bitcoin Public Address Indexer", version="0.1.0")
scan_lock = threading.Lock()


def connect_db():
    os.makedirs(os.path.dirname(DB_PATH) or ".", exist_ok=True)
    db = sqlite3.connect(DB_PATH, timeout=60)
    db.row_factory = sqlite3.Row
    db.execute("PRAGMA journal_mode=WAL")
    db.execute("PRAGMA synchronous=NORMAL")
    return db


def init_db():
    with connect_db() as db:
        db.execute("""CREATE TABLE IF NOT EXISTS addresses (
            address TEXT PRIMARY KEY,
            first_seen_height INTEGER NOT NULL,
            last_seen_height INTEGER NOT NULL,
            tx_count INTEGER NOT NULL DEFAULT 0
        )""")
        db.execute("""CREATE TABLE IF NOT EXISTS state (
            key TEXT PRIMARY KEY,
            value TEXT NOT NULL
        )""")
        db.execute("CREATE INDEX IF NOT EXISTS idx_addresses_last_seen ON addresses(last_seen_height DESC)")
        db.execute("INSERT OR IGNORE INTO state(key, value) VALUES('next_height', '0')")


def rpc(method: str, params: list[Any] | None = None):
    if not RPC_USER or not RPC_PASSWORD:
        raise RuntimeError("Bitcoin RPC credentials are not configured")
    response = requests.post(
        RPC_URL,
        json={"jsonrpc": "1.0", "id": "address-indexer", "method": method, "params": params or []},
        auth=(RPC_USER, RPC_PASSWORD),
        timeout=RPC_TIMEOUT,
    )
    response.raise_for_status()
    payload = response.json()
    if payload.get("error"):
        raise RuntimeError(str(payload["error"]))
    return payload["result"]


def get_next_height(db) -> int:
    row = db.execute("SELECT value FROM state WHERE key='next_height'").fetchone()
    return int(row["value"]) if row else 0


def scan_one_block(height: int):
    block_hash = rpc("getblockhash", [height])
    block = rpc("getblock", [block_hash, 2])
    if not isinstance(block, dict) or "tx" not in block:
        raise RuntimeError(f"Unexpected block response at height {height}")

    with connect_db() as db:
        for tx in block["tx"]:
            seen_in_tx = set()
            for output in tx.get("vout", []):
                script = output.get("scriptPubKey") or {}
                candidates = script.get("addresses") or ([script["address"]] if script.get("address") else [])
                for address in candidates:
                    if isinstance(address, str) and address:
                        seen_in_tx.add(address)
            for address in seen_in_tx:
                db.execute("""INSERT INTO addresses(address, first_seen_height, last_seen_height, tx_count)
                    VALUES(?, ?, ?, 1)
                    ON CONFLICT(address) DO UPDATE SET
                      last_seen_height=excluded.last_seen_height,
                      tx_count=addresses.tx_count+1""", (address, height, height))
        db.execute("UPDATE state SET value=? WHERE key='next_height'", (str(height + 1),))


def scanner_loop():
    while True:
        try:
            if scan_lock.acquire(blocking=False):
                try:
                    with connect_db() as db:
                        height = get_next_height(db)
                    tip = int(rpc("getblockcount"))
                    if height <= tip:
                        scan_one_block(height)
                    else:
                        time.sleep(SCAN_INTERVAL)
                finally:
                    scan_lock.release()
            else:
                time.sleep(2)
        except Exception as exc:
            print(f"Indexer error: {exc}", flush=True)
            time.sleep(SCAN_INTERVAL)


@app.on_event("startup")
def startup():
    init_db()
    threading.Thread(target=scanner_loop, daemon=True).start()


@app.get("/health")
def health():
    return {"ok": True, "service": "bitcoin-public-address-indexer"}


@app.get("/status")
def status():
    try:
        with connect_db() as db:
            next_height = get_next_height(db)
            count = db.execute("SELECT COUNT(*) AS n FROM addresses").fetchone()["n"]
        try:
            tip = int(rpc("getblockcount"))
        except Exception:
            tip = None
        return {"next_block_to_scan": next_height, "node_tip_height": tip,
                "blocks_remaining": max(0, tip - next_height + 1) if tip is not None else None,
                "unique_addresses_indexed": count}
    except Exception as exc:
        raise HTTPException(status_code=503, detail=str(exc))


@app.get("/addresses")
def addresses(limit: int = Query(100, ge=1, le=500), offset: int = Query(0, ge=0),
              sort: str = Query("last_seen", pattern="^(last_seen|first_seen|tx_count)$")):
    order = {"last_seen": "last_seen_height DESC", "first_seen": "first_seen_height ASC", "tx_count": "tx_count DESC"}[sort]
    with connect_db() as db:
        total = db.execute("SELECT COUNT(*) AS n FROM addresses").fetchone()["n"]
        rows = db.execute(f"SELECT address, first_seen_height, last_seen_height, tx_count FROM addresses ORDER BY {order}, address LIMIT ? OFFSET ?", (limit, offset)).fetchall()
    return {"total": total, "limit": limit, "offset": offset, "items": [dict(row) for row in rows]}
