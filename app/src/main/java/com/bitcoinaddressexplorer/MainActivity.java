package com.bitcoinaddressexplorer;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    private static final String API = "https://mempool.space/api";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private EditText addressInput, bulkInput, scanStartInput, scanCountInput;
    private TextView status, result, bulkResult, scanResult, savedView, fundedView;
    private Button checkButton, bulkButton, scanButton, quickButton;
    private TextView quickResult;
    private SharedPreferences prefs;
    private ScanDatabase database;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("explorer", MODE_PRIVATE);
        database = new ScanDatabase(this);
        buildUi();
        showSaved();
    }

    private int dp(float n) { return (int)(n * getResources().getDisplayMetrics().density); }
    private TextView text(String s, int size, boolean bold) {
        TextView v = new TextView(this); v.setText(s); v.setTextSize(size);
        v.setTextColor(0xff172338); v.setPadding(0, dp(6), 0, dp(6));
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }
    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout page = new LinearLayout(this); page.setOrientation(1);
        page.setPadding(dp(16), dp(18), dp(16), dp(28)); page.setBackgroundColor(0xfff3f6fa);
        scroll.addView(page);
        TextView title = text("₿ Bitcoin Address Explorer", 22, true);
        title.setTextColor(0xffd97800); page.addView(title);
        page.addView(text("کاوشگر مستقل آدرس‌های عمومی بیت‌کوین · شبکه اصلی", 14, false));
        page.addView(text("بررسی یک آدرس", 18, true));
        addressInput = new EditText(this); addressInput.setSingleLine(true);
        addressInput.setHint("آدرس عمومی BTC را وارد کنید"); addressInput.setTextDirection(View.TEXT_DIRECTION_LTR);
        page.addView(addressInput);
        checkButton = button("بررسی موجودی و تراکنش‌ها"); page.addView(checkButton);
        status = text("فقط آدرس عمومی را وارد کنید؛ عبارت بازیابی و کلید خصوصی هرگز وارد نشود.", 13, false); page.addView(status);
        result = text("", 15, false); page.addView(result);
        Button save = button("ذخیره آدرس بررسی‌شده"); page.addView(save);
        save.setOnClickListener(v -> {
            String a = addressInput.getText().toString().trim();
            if (!a.isEmpty()) { prefs.edit().putBoolean("a_"+a, true).apply(); database.addAddresses(java.util.Collections.singleton(a)); showSaved(); status.setText("آدرس در فهرست محلی ذخیره شد."); }
        });
        page.addView(text("بررسی گروهی", 18, true));
        bulkInput = new EditText(this); bulkInput.setMinLines(4); bulkInput.setGravity(Gravity.TOP);
        bulkInput.setHint("هر آدرس در یک خط (حداکثر ۱۰۰ آدرس)"); bulkInput.setTextDirection(View.TEXT_DIRECTION_LTR);
        page.addView(bulkInput);
        bulkButton = button("شروع بررسی گروهی"); page.addView(bulkButton);
        bulkResult = text("", 14, false); page.addView(bulkResult);

        page.addView(text("جست‌وجوی سریع آدرس‌های دارای موجودی", 18, true));
        page.addView(text("دریافت فهرست آماده ۱۰۰ آدرس بزرگ بیت‌کوین از یک شاخص عمومی به‌روز؛ بدون اسکن بلاک‌ها از ابتدا. این فهرست عمدتاً شامل آدرس‌های بزرگ صرافی‌ها و سرویس‌هاست و به معنی کیف پول گمشده یا قابل برداشت نیست.", 12, false));
        quickButton = button("شروع جست‌وجوی سریع ۱۰۰ آدرس"); page.addView(quickButton);
        quickResult = text("هنوز جست‌وجوی سریع انجام نشده است.", 13, false); page.addView(quickResult);

        page.addView(text("اسکن کامل تاریخچه بلاک‌چین (بسیار کند)", 18, true));
        page.addView(text("این روش از بلاک صفر شروع می‌کند و ممکن است بسیار طولانی شود یا به محدودیت سرویس برسد. برای جست‌وجوی سریع از دکمه بالاتر استفاده کن.", 12, false));
        scanButton = button("شروع اسکن کامل بلاک‌ها"); page.addView(scanButton);
        scanResult = text("اسکن کامل هنوز شروع نشده است.", 13, false); page.addView(scanResult);
        page.addView(text("آدرس‌های دارای موجودی", 18, true));
        fundedView = text(database.getFundedResults(100), 13, false); page.addView(fundedView);

        page.addView(text("آدرس‌های ذخیره‌شده", 18, true));
        savedView = text("", 13, false); page.addView(savedView);
        page.addView(text("این برنامه فقط آدرس‌های عمومی ظاهرشده در بلاک‌های اسکن‌شده یا واردشده را می‌شناسد. آدرس‌های استفاده‌نشده قابل کشف نیستند. خطای API به‌عنوان موجودی صفر گزارش نمی‌شود.", 12, false));
        checkButton.setOnClickListener(v -> checkSingle());
        bulkButton.setOnClickListener(v -> checkBulk());
        quickButton.setOnClickListener(v -> startRichListScan());
        scanButton.setOnClickListener(v -> startBlockScan());
        setContentView(scroll);
    }
    private Button button(String s) {
        Button b = new Button(this); b.setText(s); b.setAllCaps(false);
        b.setTextColor(0xffffffff); b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xffe58a16));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = dp(8); p.bottomMargin = dp(8); b.setLayoutParams(p);
        return b;
    }
    private boolean looksLikeAddress(String a) {
        return a.matches("^(1[1-9A-HJ-NP-Za-km-z]{25,34}|3[1-9A-HJ-NP-Za-km-z]{25,34}|bc1[ac-hj-np-z02-9]{11,71})$");
    }
    private JSONObject getJson(String path) throws Exception {
        HttpURLConnection c = (HttpURLConnection)new URL(API + path).openConnection();
        c.setRequestMethod("GET"); c.setConnectTimeout(15000); c.setReadTimeout(20000);
        c.setRequestProperty("Accept", "application/json");
        int code = c.getResponseCode();
        InputStream in = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096]; int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        in.close(); c.disconnect();
        if (code < 200 || code >= 300) throw new IOException(code == 429 ? "محدودیت درخواست؛ کمی بعد تلاش کنید." : "خطای سرویس: HTTP " + code);
        return new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
    }
    private long number(JSONObject o, String key) { return o.optLong(key, 0L); }
    private String btc(long sats) { return String.format(java.util.Locale.US, "%.8f BTC", sats / 100000000.0); }
    private JSONObject check(String a) throws Exception {
        if (!looksLikeAddress(a)) throw new IllegalArgumentException("ساختار آدرس معتبر نیست.");
        JSONObject d = getJson("/address/" + a);
        JSONObject chain = d.optJSONObject("chain_stats");
        JSONObject mem = d.optJSONObject("mempool_stats");
        if (chain == null) chain = new JSONObject();
        if (mem == null) mem = new JSONObject();
        long confirmed = number(chain,"funded_txo_sum") - number(chain,"spent_txo_sum");
        long pending = number(mem,"funded_txo_sum") - number(mem,"spent_txo_sum");
        JSONObject x = new JSONObject();
        x.put("address",a); x.put("confirmed",confirmed); x.put("pending",pending);
        x.put("txCount",number(chain,"tx_count") + number(mem,"tx_count"));
        return x;
    }
    private void checkSingle() {
        String a = addressInput.getText().toString().trim();
        checkButton.setEnabled(false); status.setText("در حال اتصال به شبکه اصلی بیت‌کوین...");
        result.setText("");
        worker.execute(() -> {
            try {
                JSONObject x = check(a);
                runOnUiThread(() -> {
                    result.setText("آدرس: " + a + "\nموجودی تأییدشده: " + btc(x.optLong("confirmed")) +
                        "\nموجودی در انتظار: " + btc(x.optLong("pending")) +
                        "\nتعداد تراکنش‌ها: " + x.optLong("txCount") +
                        "\n\nتاریخچه کامل: https://mempool.space/address/" + a);
                    status.setText("بررسی با موفقیت انجام شد."); checkButton.setEnabled(true);
                });
            } catch (Exception e) { runOnUiThread(() -> { status.setText("خطا: " + e.getMessage()); checkButton.setEnabled(true); }); }
        });
    }
    private void checkBulk() {
        String raw = bulkInput.getText().toString().trim();
        String[] lines = raw.split("\\s+");
        java.util.LinkedHashSet<String> unique = new java.util.LinkedHashSet<>();
        for (String s : lines) if (!s.isEmpty()) unique.add(s);
        if (unique.isEmpty()) { bulkResult.setText("ابتدا آدرس وارد کنید."); return; }
        if (unique.size() > 100) { bulkResult.setText("حداکثر ۱۰۰ آدرس در هر نوبت مجاز است."); return; }
        bulkButton.setEnabled(false);
        worker.execute(() -> {
            StringBuilder out = new StringBuilder();
            int i = 0;
            for (String a : unique) {
                i++;
                try {
                    JSONObject x = check(a);
                    out.append(i).append(") ").append(a).append("\n   موجودی: ").append(btc(x.optLong("confirmed")))
                       .append(" | تراکنش: ").append(x.optLong("txCount")).append("\n");
                    prefs.edit().putBoolean("a_"+a, true).apply();
                    database.addCheckedAddress(a, x.optLong("confirmed"), x.optLong("pending"), x.optLong("txCount"));
                } catch (Exception e) {
                    out.append(i).append(") ").append(a).append("\n   خطا: ").append(e.getMessage()).append("\n");
                }
                final String partial = out.toString();
                runOnUiThread(() -> bulkResult.setText(partial));
                try { Thread.sleep(1200); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); break; }
            }
            runOnUiThread(() -> { bulkButton.setEnabled(true); status.setText("بررسی گروهی پایان یافت."); showSaved(); });
        });
    }
    private void startRichListScan() {
        quickButton.setEnabled(false);
        quickResult.setText("در حال دریافت فهرست عمومی آدرس‌های دارای موجودی...");
        worker.execute(() -> RichListScanner.scan(this, new RichListScanner.Callback() {
            @Override public void onComplete(int count, String source) {
                runOnUiThread(() -> {
                    quickResult.setText("دریافت موفق: " + count +
                        " آدرس دارای موجودی از فهرست عمومی.\nمنبع: " + source +
                        "\nموجودی‌ها مربوط به آخرین به‌روزرسانی منبع هستند.");
                    fundedView.setText(database.getFundedResults(100));
                    quickButton.setEnabled(true);
                    showSaved();
                });
            }
            @Override public void onError(String message) {
                runOnUiThread(() -> {
                    quickResult.setText("جست‌وجوی سریع ناموفق بود: " + message +
                        "\nهیچ موجودی ساختگی یا صفر ثبت نشد.");
                    quickButton.setEnabled(true);
                });
            }
        }));
    }

    private void startBlockScan() {
        if (BlockAddressScanner.isRunning()) {
            BlockAddressScanner.stop();
            scanResult.setText("درخواست توقف ثبت شد؛ پس از پایان درخواست جاری متوقف می‌شود...");
            scanButton.setEnabled(false);
            return;
        }
        scanButton.setText("توقف اسکن");
        scanButton.setEnabled(true);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        scanResult.setText("در حال شروع اسکن از بلاک صفر...");
        worker.execute(() -> BlockAddressScanner.scan(this, new BlockAddressScanner.Callback() {
            @Override public void onProgress(String message) {
                runOnUiThread(() -> scanResult.setText(message));
            }
            @Override public void onFundedAddress(String address, long confirmed, long pending, long txCount) {
                runOnUiThread(() -> fundedView.setText(database.getFundedResults(100)));
            }
            @Override public void onComplete(int blocks, int added, int total, int checked,
                                             int nextHeight, boolean reachedTip) {
                runOnUiThread(() -> {
                    scanResult.setText((reachedTip ? "اسکن تا آخرین بلاک فعلی تمام شد." : "اسکن متوقف شد.") +
                        "\nبلاک‌های این نوبت: " + blocks +
                        "\nآدرس‌های تازه: " + added +
                        "\nکل آدرس‌های ذخیره‌شده: " + total +
                        "\nآدرس‌های بررسی‌شده: " + checked +
                        "\nارتفاع ادامه بعدی: " + nextHeight);
                    fundedView.setText(database.getFundedResults(100));
                    scanButton.setText("شروع / ادامه جست‌وجوی خودکار");
                    scanButton.setEnabled(true);
                    getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    showSaved();
                });
            }
            @Override public void onError(String message) {
                runOnUiThread(() -> {
                    scanResult.setText("اسکن متوقف شد: " + message +
                        "\nپیشرفت ذخیره شده است؛ با زدن دکمه دوباره ادامه می‌دهد.");
                    fundedView.setText(database.getFundedResults(100));
                    scanButton.setText("ادامه جست‌وجوی خودکار");
                    scanButton.setEnabled(true);
                    getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    showSaved();
                });
            }
        }));
    }
    private void showSaved() {
        int count = database.getTotalCount();
        savedView.setText(count == 0 ? "هنوز آدرسی ذخیره نشده است." :
            "تعداد کل آدرس‌های ذخیره‌شده: " + count + "\n" + database.getAddressList(300));
    }
    @Override protected void onDestroy() {
        BlockAddressScanner.stop();
        getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        worker.shutdownNow();
        database.close();
        super.onDestroy();
    }
}