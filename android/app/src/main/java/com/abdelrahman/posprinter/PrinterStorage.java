package com.abdelrahman.posprinter;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/* loaded from: classes7.dex */
public class PrinterStorage {
    private static final String KEY_LAST_PRINTER = "last_connected_printer";
    private static final String KEY_SAVED_PRINTERS = "saved_printers_history";
    private static final String PREF_NAME = "PrinterConfig";
    private SharedPreferences prefs;

    public PrinterStorage(Context context) {
        this.prefs = context.getSharedPreferences(PREF_NAME, 0);
    }

    public void savePrinter(String name, String address, String type) {
        try {
            JSONObject lastPrinter = new JSONObject();
            lastPrinter.put("name", name);
            lastPrinter.put("address", address);
            lastPrinter.put("type", type);
            this.prefs.edit().putString(KEY_LAST_PRINTER, lastPrinter.toString()).apply();
            addToHistory(lastPrinter);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public JSONObject getLastPrinter() {
        String json = this.prefs.getString(KEY_LAST_PRINTER, null);
        if (json != null) {
            try {
                return new JSONObject(json);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return null;
    }

    private void addToHistory(JSONObject newPrinter) throws JSONException {
        try {
            String historyJson = this.prefs.getString(KEY_SAVED_PRINTERS, "[]");
            JSONArray historyArray = new JSONArray(historyJson);
            JSONArray newArray = new JSONArray();
            newArray.put(newPrinter);
            for (int i = 0; i < historyArray.length(); i++) {
                JSONObject existing = historyArray.getJSONObject(i);
                if (!existing.getString("address").equals(newPrinter.getString("address"))) {
                    newArray.put(existing);
                }
            }
            this.prefs.edit().putString(KEY_SAVED_PRINTERS, newArray.toString()).apply();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public String getSavedPrintersJson() {
        return this.prefs.getString(KEY_SAVED_PRINTERS, "[]");
    }

    public void removePrinter(String address) throws JSONException {
        try {
            String historyJson = this.prefs.getString(KEY_SAVED_PRINTERS, "[]");
            JSONArray historyArray = new JSONArray(historyJson);
            JSONArray newArray = new JSONArray();
            for (int i = 0; i < historyArray.length(); i++) {
                JSONObject existing = historyArray.getJSONObject(i);
                if (!existing.getString("address").equals(address)) {
                    newArray.put(existing);
                }
            }
            this.prefs.edit().putString(KEY_SAVED_PRINTERS, newArray.toString()).apply();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
