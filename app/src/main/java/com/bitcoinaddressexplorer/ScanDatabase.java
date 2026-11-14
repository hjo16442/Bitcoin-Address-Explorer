package com.bitcoinaddressexplorer;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.content.ContentValues;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class ScanDatabase extends SQLiteOpenHelper {
    public ScanDatabase(Context context) { super(context, "bitcoin_scan.db", null, 1); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE addresses (address TEXT PRIMARY KEY, checked INTEGER NOT NULL DEFAULT 0, confirmed INTEGER NOT NULL DEFAULT 0, pending INTEGER NOT NULL DEFAULT 0, tx_count INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX idx_addresses_pending ON addresses(checked)");
        db.execSQL("CREATE INDEX idx_addresses_funded ON addresses(confirmed, pending)");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) { }
    public synchronized int addAddresses(Collection<String> addresses) {
        SQLiteDatabase db = getWritableDatabase(); int added = 0; db.beginTransaction();
        try {
            for (String address : addresses) {
                ContentValues v = new ContentValues(); v.put("address", address);
                if (db.insertWithOnConflict("addresses", null, v, SQLiteDatabase.CONFLICT_IGNORE) != -1) added++;
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        return added;
    }
    public synchronized void addCheckedAddress(String address, long confirmed, long pending, long txCount) {
        ContentValues v = new ContentValues(); v.put("address", address); v.put("checked", 1);
        v.put("confirmed", confirmed); v.put("pending", pending); v.put("tx_count", txCount);
        getWritableDatabase().insertWithOnConflict("addresses", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }
    public synchronized List<String> getPending(int limit) {
        List<String> result = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery("SELECT address FROM addresses WHERE checked=0 ORDER BY rowid LIMIT ?", new String[]{String.valueOf(limit)});
        try { while (c.moveToNext()) result.add(c.getString(0)); } finally { c.close(); }
        return result;
    }
    public synchronized void markChecked(String address, long confirmed, long pending, long txCount) {
        ContentValues v = new ContentValues(); v.put("checked", 1); v.put("confirmed", confirmed);
        v.put("pending", pending); v.put("tx_count", txCount);
        getWritableDatabase().update("addresses", v, "address=?", new String[]{address});
    }
    public synchronized int getTotalCount() {
        Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM addresses", null);
        try { return c.moveToFirst() ? c.getInt(0) : 0; } finally { c.close(); }
    }
    public synchronized int getCheckedCount() {
        Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM addresses WHERE checked=1", null);
        try { return c.moveToFirst() ? c.getInt(0) : 0; } finally { c.close(); }
    }
    public synchronized String getAddressList(int limit) {
        StringBuilder out = new StringBuilder();
        Cursor c = getReadableDatabase().rawQuery("SELECT address FROM addresses ORDER BY rowid DESC LIMIT ?", new String[]{String.valueOf(limit)});
        try { while (c.moveToNext()) out.append(c.getString(0)).append("\n"); } finally { c.close(); }
        return out.toString();
    }
    public synchronized String getFundedResults(int limit) {
        StringBuilder out = new StringBuilder();
        Cursor c = getReadableDatabase().rawQuery("SELECT address, confirmed, pending, tx_count FROM addresses WHERE confirmed>0 OR pending>0 ORDER BY confirmed DESC, pending DESC LIMIT ?", new String[]{String.valueOf(limit)});
        try {
            while (c.moveToNext()) {
                out.append(c.getString(0))
                    .append("\nموجودی تأییدشده: ").append(String.format(java.util.Locale.US, "%.8f BTC", c.getLong(1) / 100000000.0))
                    .append("\nموجودی در انتظار: ").append(String.format(java.util.Locale.US, "%.8f BTC", c.getLong(2) / 100000000.0))
                    .append("\nتعداد تراکنش: ").append(c.getLong(3)).append("\n\n");
            }
        } finally { c.close(); }
        return out.length() == 0 ? "هنوز آدرس دارای موجودی پیدا نشده است." : out.toString();
    }
}