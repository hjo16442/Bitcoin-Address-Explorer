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
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class BlockAddressScanner {
    private static final String API = "https://mempool.space/api";
    private static final long REQUEST_DELAY_MS = 1200L;

    public interface Callback {
        void onProgress(String message);
        void onComplete(int blocksScanned, int newAddresses, int totalAddresses);
        void onError(String message);
    }

    private BlockAddressScanner() {}

    public static void scan(Context context, String startText, String countText, Callback callback) {
        SharedPreferences prefs = context.getSharedPreferences("explorer", Context.MODE_PRIVATE);
        try {
            int count = Integer.parseInt(countText);
            if (count < 1 || count > 10) throw new IllegalArgumentException("تعداد بلاک باید بین ۱ تا ۱۰ باشد.");
            int tip = Integer.parseInt(request("/blocks/tip/height"));
            int start = startText.isEmpty() ? Math.max(0, tip - count + 1) : Integer.parseInt(startText);
            if (start < 0 || start > tip || (long) start + count - 1 > tip)
                throw new IllegalArgumentException("بازه انتخابی خارج از ارتفاع بلاک‌های موجود است. آخرین ارتفاع: " + tip);

            Set<String> found = new HashSet<>();
            int newAddresses = 0;
            int blocksDone = 0;

            for (int height = start; height < start + count; height++) {
                String hash = request("/block-height/" + height).trim();
                JSONObject block = new JSONObject(request("/block/" + hash));
                int txCount = block.optInt("tx_count", 0);
                int blockNew = 0;

                for (int offset = 0; offset < txCount; offset += 25) {
                    JSONArray txs = new JSONArray(request("/block/" + hash + "/txs/" + offset));
                    if (txs.length() == 0) break;
                    for (int i = 0; i < txs.length(); i++) {
                        JSONObject tx = txs.optJSONObject(i);
                        if (tx == null) continue;
                        JSONArray outputs = tx.optJSONArray("vout");
                        if (outputs == null) continue;
                        for (int j = 0; j < outputs.length(); j++) {
                            JSONObject output = outputs.optJSONObject(j);
                            if (output == null) continue;
                            String address = output.optString("scriptpubkey_address", "");
                            if (!address.isEmpty()) found.add(address);
                        }
                    }
                    if (txs.length() < 25) break;
                }

                SharedPreferences.Editor editor = prefs.edit();
                for (String address : found) {
                    if (!prefs.getBoolean("a_" + address, false)) {
                        editor.putBoolean("a_" + address, true);
                        newAddresses++;
                        blockNew++;
                    }
                }
                editor.apply();
                blocksDone++;
                callback.onProgress("اسکن بلاک " + height + " از " + (start + count - 1) +
                    "\nتراکنش‌های بلاک: " + txCount + "\nآدرس‌های جدید این بلاک: " + blockNew +
                    "\nآدرس‌های جدید در این نوبت: " + newAddresses);
            }

            int total = 0;
            for (Map.Entry<String, ?> entry : prefs.getAll().entrySet())
                if (entry.getKey().startsWith("a_")) total++;
            callback.onComplete(blocksDone, newAddresses, total);
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
