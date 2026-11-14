package com.bitcoinaddressexplorer;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RichListScanner {
    private static final String SOURCE = "https://openbitcoin.com/rich-list";
    private static final Pattern ROW = Pattern.compile("(?is)<tr\\b[^>]*>(.*?)</tr>");
    private static final Pattern ADDRESS = Pattern.compile("(?:[13][1-9A-HJ-NP-Za-km-z]{25,34}|bc1[ac-hj-np-z02-9]{11,71})");
    private static final Pattern BALANCE = Pattern.compile("([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*BTC", Pattern.CASE_INSENSITIVE);

    public interface Callback {
        void onComplete(int count, String source);
        void onError(String message);
    }

    private RichListScanner() {}

    public static void scan(Context context, Callback callback) {
        ScanDatabase database = new ScanDatabase(context);
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(SOURCE).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(30000);
            connection.setRequestProperty("Accept", "text/html");
            connection.setRequestProperty("User-Agent", "BitcoinAddressExplorer/1.0 (Android)");
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new java.io.IOException("منبع فهرست پاسخ HTTP " + code + " داد.");
            }
            InputStream input = connection.getInputStream();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
                if (output.size() > 5 * 1024 * 1024) {
                    throw new java.io.IOException("حجم صفحه منبع غیرمنتظره است.");
                }
            }
            input.close();
            String html = output.toString(StandardCharsets.UTF_8.name())
                .replace("&nbsp;", " ").replace("&#160;", " ");
            Matcher rows = ROW.matcher(html);
            Set<String> seen = new LinkedHashSet<>();
            int saved = 0;
            while (rows.find()) {
                String row = rows.group(1);
                Matcher addressMatcher = ADDRESS.matcher(row);
                if (!addressMatcher.find()) continue;
                String address = addressMatcher.group();
                if (!seen.add(address)) continue;
                Matcher balanceMatcher = BALANCE.matcher(row.substring(addressMatcher.end()));
                if (!balanceMatcher.find()) continue;
                String amountText = balanceMatcher.group(1).replace(",", "");
                double btc = Double.parseDouble(amountText);
                long sats = Math.round(btc * 100000000.0);
                if (sats <= 0) continue;
                database.addCheckedAddress(address, sats, 0L, 0L);
                saved++;
            }
            if (saved == 0) {
                throw new java.io.IOException("صفحه دریافت شد، اما قالب فهرست قابل تشخیص نبود؛ هیچ موجودی ثبت نشد.");
            }
            callback.onComplete(saved, SOURCE);
        } catch (Exception e) {
            callback.onError(e.getMessage() == null ? "خطای نامشخص در دریافت فهرست" : e.getMessage());
        } finally {
            if (connection != null) connection.disconnect();
            database.close();
        }
    }
}
