package com.bitcoinaddressexplorer;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class BlockAddressScanner {
    private static final String API = "https://mempool.space/api";
    private static final long REQUEST_DELAY_MS = 1200L;
    private static final int BALANCE_BATCH = 20;
    private static volatile boolean running = false;

    public interface Callback {
        void onProgress(String message);
        void onFundedAddress(String address, long confirmed, long pending, long txCount);
        void onComplete(int blocksScanned, int newAddresses, int totalAddresses,
                        int checkedAddresses, int nextHeight, boolean reachedTip);
        void onError(String message);
    }

    private BlockAddressScanner() {}
    public static boolean isRunning() { return running; }
    public static void stop() { running = false; }

    public static void scan(Context context, Callback callback) {
        if (running) return;
        running = true;
        SharedPreferences prefs = context.getSharedPreferences("explorer", Context.MODE_PRIVATE);
        ScanDatabase database = new ScanDatabase(context);
        int blocksDone = 0, newAddresses = 0;
        int height = prefs.getInt("scan_next_height", 0);
        try {
            if (!prefs.getBoolean("scan_db_seeded", false)) {
                Set<String> previous = new LinkedHashSet<>();
                for (String key : prefs.getAll().keySet())
                    if (key.startsWith("a_")) previous.add(key.substring(2));
                database.addAddresses(previous);
                prefs.edit().putBoolean("scan_db_seeded", true).apply();
            }

            int tip = Integer.parseInt(request("/blocks/tip/height").trim());
            while (running) {
                List<String> pending = database.getPending(BALANCE_BATCH);
                if (!pending.isEmpty()) {
                    for (String address : pending) {
                        if (!running) break;
                        JSONObject data = new JSONObject(request("/address/" + address));
                        JSONObject chain = data.optJSONObject("chain_stats");
                        JSONObject mem = data.optJSONObject("mempool_stats");
                        long confirmed = chain == null ? 0 : chain.optLong("funded_txo_sum") - chain.optLong("spent_txo_sum");
                        long unconfirmed = mem == null ? 0 : mem.optLong("funded_txo_sum") - mem.optLong("spent_txo_sum");
                        long txCount = (chain == null ? 0 : chain.optLong("tx_count")) +
                                       (mem == null ? 0 : mem.optLong("tx_count"));
                        database.markChecked(address, confirmed, unconfirmed, txCount);
                        if (confirmed > 0 || unconfirmed > 0)
                            callback.onFundedAddress(address, confirmed, unconfirmed, txCount);
                        callback.onProgress("بررسی موجودی آدرس‌ها: " + database.getCheckedCount() +
                            " از " + database.getTotalCount() + "\nبلاک بعدی: " + height);
                    }
                    continue;
                }

                if (height > tip) break;
                String hash = request("/block-height/" + height).trim();
                JSONObject block = new JSONObject(request("/block/" + hash));
                int txCount = block.optInt("tx_count", 0);
                Set<String> addresses = new LinkedHashSet<>();
                for (int offset = 0; offset < txCount && running; offset += 25) {
                    JSONArray txs = new JSONArray(request("/block/" + hash + "/txs/" + offset));
                    if (txs.length() == 0) break;
                    for (int i = 0; i < txs.length(); i++) {
                        JSONObject tx = txs.optJSONObject(i);
                        JSONArray outputs = tx == null ? null : tx.optJSONArray("vout");
                        if (outputs == null) continue;
                        for (int j = 0; j < outputs.length(); j++) {
                            JSONObject output = outputs.optJSONObject(j);
                            String address = output == null ? "" : output.optString("scriptpubkey_address", "");
                            if (!address.isEmpty()) addresses.add(address);
                        }
                    }
                    if (txs.length() < 25) break;
                }
                if (!running) break;
                int added = database.addAddresses(addresses);
                newAddresses += added;
                height++;
                prefs.edit().putInt("scan_next_height", height).apply();
                blocksDone++;
                callback.onProgress("استخراج بلاک " + (height - 1) + " از " + tip +
                    "\nآدرس تازه: " + added + "\nکل آدرس‌ها: " + database.getTotalCount() +
                    "\nصف بررسی موجودی به‌صورت خودکار پردازش می‌شود.");
            }

            boolean reachedTip = height > tip;
            running = false;
            callback.onComplete(blocksDone, newAddresses, database.getTotalCount(),
                database.getCheckedCount(), height, reachedTip);
        } catch (Exception e) {
            running = false;
            callback.onError(e.getMessage() == null ? "خطای نامشخص" : e.getMessage());
        } finally {
            database.close();
        }
    }

    private static String request(String path) throws Exception {
        Thread.sleep(REQUEST_DELAY_MS);
        HttpURLConnection connection = (HttpURLConnection) new URL(API + path).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("Accept", "application/json");
        int code = connection.getResponseCode();
        InputStream input = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (input != null) {
            byte[] buffer = new byte[8192]; int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            input.close();
        }
        connection.disconnect();
        if (code == 429) throw new IOException("سرویس محدودیت درخواست (HTTP 429) اعمال کرد؛ آدرس‌ها و ارتفاع ذخیره شده‌اند، بعداً دوباره شروع کن.");
        if (code < 200 || code >= 300) throw new IOException("خطای سرویس: HTTP " + code);
        return output.toString(StandardCharsets.UTF_8.name());
    }
}