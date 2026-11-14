# Bitcoin public-address indexer (Mainnet)

This service scans Bitcoin mainnet blocks in height order and stores addresses exposed by transaction output scripts. It resumes from the last committed block after restart and exposes a paginated read-only API.

## Important limits

- It finds addresses that appear in parseable transaction outputs. It cannot discover addresses that were generated but never used on-chain.
- It does **not** identify owners or private keys and must never accept seed phrases or private keys.
- This first version indexes address appearance, first/last-seen block height, and transaction count. It does not calculate balances or label an address as currently funded.
- A full historical scan is a large, long-running workload. Bitcoin Core must run unpruned with `txindex=1`; allow substantial disk space, bandwidth, RAM, and time. The initial chain download and scan may take days or longer depending on hardware and connection.
- Some output scripts do not expose a conventional address. Multisig and nonstandard scripts may not map cleanly to one address; the service only stores addresses returned by Bitcoin Core's `scriptPubKey.addresses` or `scriptPubKey.address` fields.
- Keep Bitcoin RPC private. Do not expose port 8332 to the public internet.

## Run on a Linux VPS

1. Install Docker Engine and the Docker Compose plugin.
2. Copy `.env.example` to `.env` and set long, unique RPC credentials.
3. Run `docker compose up -d --build` from this directory.
4. The API listens on port `8080`. Put it behind HTTPS and add access controls before exposing it publicly.
5. Check progress at `/status`; browse records at `/addresses?limit=100&offset=0`.

Example:

```bash
curl http://127.0.0.1:8080/status
curl 'http://127.0.0.1:8080/addresses?limit=100&offset=0&sort=last_seen'
```

The API currently returns public address strings and block metadata only. Do not interpret `tx_count` as current balance or current activity. A later version can add a proper UTXO/address-balance index and authenticated Android client access.
