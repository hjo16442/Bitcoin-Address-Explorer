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
import java.util.Map;
import java.util.Set;

public final class BlockAddressScanner {
    private static final String API = "https://mempool.space/api";
    private static final long REQUEST_DELAY_MS = 1200L;
    private static final int BALANCE_CHECK_LIMIT = 20;
    private static final String QUEUE_KEY = "scan_balance_queue";
    private static final String NEXT_HEIGHT_KEY = "scan_next_height";

    public interface Callback {
        void onProgress(String message);
        void onComplete(int blocksScanned, int newAddresses, int totalAddresses,
                        int checkedNow, int remaining, String fundedResults, int nextHeight);
        void onError(String message);
    }

    private BlockAddressScanner() {}

    public static void scan(Context context, String ignoredStartText, String countText, Callback callback) {
        SharedPreferences prefs = context.getSharedPreferences("explorer", Context.MODE_PRIVATE);
        try {
            int count = Integer.parseInt(countText);
            if (count < 1 || count > 10) throw new IllegalArgumentException("تعداد بلاک باید بین ۱ تا ۱۰ باشد.");
            int tip = Integer.parseInt(request("/blocks/tip/height"));
            int start = prefs.getInt(NEXT_HEIGHT_KEY, 0);
            if (start > tip) start = tip;
            int end = Math.min(tip, start + count - 1);

            Set<String> found = new LinkedHashSet<>();
            int newAddresses = 0;
            int blocksDone = 0;
            for (int height = start; height <= end; height++) {
                String hash = request("/block-height/" + height).trim();
                JSONObject block = new JSONObject(request("/block/" + hash));
                int txCount = block.optInt("tx_count", 0);
                for (int offset = 0; offset < txCount; offset += 25) {
                    JSONArray txs = new JSONArray(request("/block/" + hash + "/txs/" + offset));
                    if (txs.length() == 0) break;
                    for (int i = 0; i < txs.length(); i++) {
                        JSONObject tx = txs.optJSONObject(i);
                        JSONArray outputs = tx == null ? null : tx.optJSONArray("vout");
                        if (outputs == null) continue;
                        for (int j = 0; j < outputs.length(); j++) {
                            JSONObject output = outputs.optJSONObject(j);
                            String address = output == null ? "" : output.optString("scriptpubkey_address", "");
                            if (!address.isEmpty() && !prefs.getBoolean("a_" + address, false)) found.add(address);
                        }
                    }
                    if (txs.length() < 25) break;
                }
                SharedPreferences.Editor editor = prefs.edit();
                for (String address : found) {
                    if (!prefs.getBoolean("a_" + address, false)) {
                        editor.putBoolean("a_" + address, true);
                        newAddresses++;
                    }
                }
                editor.apply();
                blocksDone++;
                callback.onProgress("استخراج بلاک " + height + " از " + tip + "\nآدرس‌های تازه: " + found.size());
            }

            JSONArray oldQueue = new JSONArray(prefs.getString(QUEUE_KEY, "[]"));
            Set<String> queue = new LinkedHashSet<>();
            for (int i = 0; i < oldQueue.length(); i++) queue.add(oldQueue.optString(i));
            queue.addAll(found);

            int checked = 0;
            StringBuilder funded = new StringBuilder();
            java.util.Iterator<String> iterator = queue.iterator();
            while (iterator.hasNext() && checked < BALANCE_CHECK_LIMIT) {
                String address = iterator.next();
                JSONObject data = new JSONObject(request("/address/" + address));
                JSONObject chain = data.optJSONObject("chain_stats");
                JSONObject mem = data.optJSONObject("mempool_stats");
                long confirmed = chain == null ? 0 : chain.optLong("funded_txo_sum") - chain.optLong("spent_txo_sum");
                long pending = mem == null ? 0 : mem.optLong("funded_txo_sum") - mem.optLong("spent_txo_sum");
                long txs = (chain == null ? 0 : chain.optLong("tx_count")) +
                           (mem == null ? 0 : mem.optLong("tx_count"));
                if (confirmed > 0 || pending > 0) {
                    funded.append(address).append("\nموجودی تأییدشده: ")
                        .append(String.format(java.util.Locale.US, "%.8f BTC", confirmed / 100000000.0))
                        .append("\nموجودی در انتظار: ")
                        .append(String.format(java.util.Locale.US, "%.8f BTC", pending / 100000000.0))
                        .append("\nتعداد تراکنش: ").append(txs).append("\n\n");
                }
                iterator.remove();
                checked++;
                callback.onProgress("بررسی موجودی: " + checked + "/" + BALANCE_CHECK_LIMIT +
                    "\nآدرس‌های باقی‌مانده در صف: " + queue.size());
            }

            JSONArray savedQueue = new JSONArray();
            for (String address : queue) savedQueue.put(address);
            int nextHeight = end + 1;
            prefs.edit().putString(QUEUE_KEY, savedQueue.toString()).putInt(NEXT_HEIGHT_KEY, nextHeight).apply();

            int total = 0;
            for (Map.Entry<String, ?> entry : prefs.getAll().entrySet())
                if (entry.getKey().startsWith("a_")) total++;
            callback.onComplete(blocksDone, newAddresses, total, checked, queue.size(), funded.toString(), nextHeight);
        } catch (Exception e) {
            callback.onError(e.getMessage() == null ? "خطای نامشخص" : e.getMessage());
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
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            input.close();
        }
        connection.disconnect();
        if (code == 429) throw new IOException("API محدودیت درخواست (HTTP 429) اعمال کرد؛ کمی بعد دوباره امتحان کن.");
        if (code < 200 || code >= 300) throw new IOException("خطای API: HTTP " + code);
        return output.toString(StandardCharsets.UTF_8.name());
    }
}