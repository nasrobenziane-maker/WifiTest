package dz.stream.wifitest;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.wifi.ScanResult;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSuggestion;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

public class MainActivity extends Activity {
    // Les réseaux et mots de passe sont sauvegardés uniquement sur le téléviseur (stockage privé de l'application).
    private static final String PREFS = "wifi_networks";
    private static final String KEY_LIST = "list";
    private static final int REQ_ADD = 77;

    private SharedPreferences prefs;
    private WifiManager wifi;

    private LinearLayout listBox;
    private TextView status;
    private EditText newSsid;
    private EditText newPass;
    private String pendingSsid = "";

    private static final int REQ_LOC = 78;
    private LinearLayout scanBox;
    private BroadcastReceiver scanReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28, 24, 28, 24);
        root.setBackgroundColor(0xFF101820);

        root.addView(text("Wi-Fi Manager", 28, 0xFFFFFFFF));

        root.addView(text("إضافة شبكة جديدة", 20, 0xFF8BE9FD));
        root.addView(button("بحث عن الشبكات المتوفرة", v -> scanNetworks()));
        scanBox = new LinearLayout(this);
        scanBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(scanBox);
        newSsid = input("اسم الشبكة (SSID)", false);
        newPass = input("كلمة المرور", true);
        root.addView(newSsid);
        root.addView(newPass);
        root.addView(button("حفظ الشبكة", v -> addNetwork()));

        root.addView(text("الشبكات المحفوظة", 20, 0xFF8BE9FD));
        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(listBox);

        status = text("جاهز.", 18, 0xFFFFD166);
        root.addView(status);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);

        resyncSuggestions();
        refreshList();
    }

    // ---------- Stockage ----------

    private JSONArray load() {
        try {
            return new JSONArray(prefs.getString(KEY_LIST, "[]"));
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    private void save(JSONArray arr) {
        prefs.edit().putString(KEY_LIST, arr.toString()).apply();
    }

    private static void safePut(JSONObject o, String key, Object value) {
        try {
            o.put(key, value);
        } catch (JSONException ignored) {
        }
    }

    // ---------- Ajout / suppression / auto ----------

    private void addNetwork() {
        String ssid = newSsid.getText().toString().trim();
        String pass = newPass.getText().toString();
        if (ssid.isEmpty()) {
            show("اكتب اسم الشبكة أولًا.");
            return;
        }
        if (pass.length() < 8) {
            show("كلمة المرور يجب أن تكون 8 أحرف على الأقل.");
            return;
        }

        JSONArray arr = load();
        JSONArray out = new JSONArray();
        boolean auto = false;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            if (ssid.equals(o.optString("ssid"))) {
                auto = o.optBoolean("auto");
                if (auto) suggest(ssid, o.optString("pass"), false);
            } else {
                out.put(o);
            }
        }

        JSONObject entry = new JSONObject();
        safePut(entry, "ssid", ssid);
        safePut(entry, "pass", pass);
        safePut(entry, "auto", auto);
        out.put(entry);
        save(out);

        if (auto) suggest(ssid, pass, true);

        newSsid.setText("");
        newPass.setText("");
        show("تم حفظ الشبكة: " + ssid);
        refreshList();
    }

    private void deleteNetwork(String ssid) {
        JSONArray arr = load();
        JSONArray out = new JSONArray();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            if (ssid.equals(o.optString("ssid"))) {
                if (o.optBoolean("auto")) suggest(ssid, o.optString("pass"), false);
            } else {
                out.put(o);
            }
        }
        save(out);
        show("تم حذف " + ssid + " من التطبيق.");
        refreshList();
    }

    private void setAuto(String ssid, boolean on) {
        JSONArray arr = load();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && ssid.equals(o.optString("ssid"))) {
                safePut(o, "auto", on);
                suggest(ssid, o.optString("pass"), on);
            }
        }
        save(arr);
    }

    private WifiNetworkSuggestion makeSuggestion(String ssid, String pass) {
        return new WifiNetworkSuggestion.Builder()
                .setSsid(ssid)
                .setWpa2Passphrase(pass)
                .build();
    }

    // Enregistre ou retire la suggestion dans le système Android (connexion automatique).
    private void suggest(String ssid, String pass, boolean on) {
        List<WifiNetworkSuggestion> list = Collections.singletonList(makeSuggestion(ssid, pass));
        try {
            int result = on ? wifi.addNetworkSuggestions(list) : wifi.removeNetworkSuggestions(list);
            if (on && result == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
                show("تم تفعيل الاتصال التلقائي لـ " + ssid);
            } else if (on && result != WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE) {
                show("تعذّر تفعيل الاتصال التلقائي لـ " + ssid + " (رمز " + result + ")");
            } else if (!on) {
                show("تم إيقاف الاتصال التلقائي لـ " + ssid);
            }
        } catch (Exception e) {
            show("خطأ في الاتصال التلقائي: " + e.getMessage());
        }
    }

    private void resyncSuggestions() {
        JSONArray arr = load();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && o.optBoolean("auto")) {
                suggest(o.optString("ssid"), o.optString("pass"), true);
            }
        }
    }

    // ---------- Connexion ----------

    // Demande au système d'enregistrer le réseau comme un vrai réseau Wi-Fi (avec internet pour tout le téléviseur).
    private void saveToSystem(String ssid, String pass) {
        pendingSsid = ssid;
        ArrayList<WifiNetworkSuggestion> list = new ArrayList<>();
        list.add(makeSuggestion(ssid, pass));

        if (Build.VERSION.SDK_INT >= 30) {
            try {
                Intent i = new Intent(Settings.ACTION_WIFI_ADD_NETWORKS);
                i.putParcelableArrayListExtra(Settings.EXTRA_WIFI_NETWORK_LIST, list);
                startActivityForResult(i, REQ_ADD);
                show("وافق على النافذة التي تظهر لحفظ " + ssid);
                return;
            } catch (Exception ignored) {
                // Ce téléviseur n'a peut-être pas cet écran système : on passe à la suggestion.
            }
        }

        try {
            int result = wifi.addNetworkSuggestions(list);
            if (result == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS
                    || result == WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE) {
                show("هذا التلفاز لا يعرض نافذة الحفظ. سُجّلت الشبكة كاقتراح؛ وافق على إشعار النظام "
                        + "ثم اختر الشبكة من الإعدادات (اتصال يدوي).");
            } else {
                show("تعذّر حفظ الشبكة (رمز " + result + "). استخدم اتصال يدوي.");
            }
        } catch (Exception e) {
            show("خطأ: " + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_ADD) return;

        ArrayList<Integer> codes = data == null
                ? null : data.getIntegerArrayListExtra(Settings.EXTRA_WIFI_NETWORK_RESULT_LIST);
        if (codes != null && !codes.isEmpty()) {
            int code = codes.get(0);
            if (code == Settings.ADD_WIFI_RESULT_SUCCESS) {
                show("تم حفظ " + pendingSsid + " في النظام. إن كان التلفاز متصلًا بشبكة أخرى "
                        + "فاختر هذه الشبكة من الإعدادات (اتصال يدوي).");
            } else if (code == Settings.ADD_WIFI_RESULT_ALREADY_EXISTS) {
                show(pendingSsid + " محفوظة في النظام بالفعل. اختر الشبكة من الإعدادات إن لم تتصل.");
            } else {
                show("فشل حفظ " + pendingSsid + " في النظام.");
            }
        } else if (resultCode == RESULT_CANCELED) {
            show("أُلغي طلب الحفظ.");
        }
    }

    private void openManual() {
        try {
            startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (Exception e2) {
                show("تعذّر فتح الإعدادات على هذا التلفاز.");
            }
        }
    }

    // ---------- Recherche des réseaux ----------

    private void scanNetworks() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_LOC);
            show("اسمح بإذن الموقع ليتمكن التطبيق من عرض الشبكات.");
            return;
        }
        if (!wifi.isWifiEnabled()) {
            show("الواي فاي مُعطّل في التلفاز. فعّله من الإعدادات أولًا.");
            return;
        }

        stopListening();
        scanReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                stopListening();
                showScanResults();
            }
        };
        IntentFilter filter = new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(scanReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(scanReceiver, filter);
        }

        boolean started = wifi.startScan();
        if (started) {
            show("جاري البحث عن الشبكات ...");
        } else {
            // Android limite le nombre de recherches : on affiche les derniers résultats connus.
            stopListening();
            show("النظام يحدّ من عدد مرات البحث. أعرض آخر نتائج متوفرة.");
            showScanResults();
        }
    }

    private void showScanResults() {
        List<ScanResult> results;
        try {
            results = wifi.getScanResults();
        } catch (SecurityException e) {
            show("لا يوجد إذن لقراءة الشبكات. اسمح بإذن الموقع.");
            return;
        }
        scanBox.removeAllViews();
        if (results == null || results.isEmpty()) {
            show("لا توجد نتائج. تأكد أن خدمة الموقع (Location) مفعّلة في إعدادات التلفاز ثم أعد البحث.");
            return;
        }

        // On garde la meilleure ligne pour chaque nom de réseau.
        Map<String, ScanResult> best = new LinkedHashMap<>();
        for (ScanResult r : results) {
            String name = r.SSID;
            if (name == null || name.isEmpty()) continue;
            ScanResult old = best.get(name);
            if (old == null || r.level > old.level) best.put(name, r);
        }
        List<ScanResult> sorted = new ArrayList<>(best.values());
        Collections.sort(sorted, (a, b) -> b.level - a.level);

        for (ScanResult r : sorted) {
            final String ssid = r.SSID;
            int bars = WifiManager.calculateSignalLevel(r.level, 5);
            String band = r.frequency >= 5000 ? "5GHz" : "2.4GHz";
            scanBox.addView(button(ssid + "   •   " + band + "   •   إشارة " + bars + "/4",
                    v -> pickNetwork(ssid)));
        }
        show("وُجدت " + sorted.size() + " شبكة. اختر شبكتك من القائمة.");
    }

    private void pickNetwork(String ssid) {
        newSsid.setText(ssid);
        scanBox.removeAllViews();
        newPass.requestFocus();
        show("تم اختيار " + ssid + ". اكتب كلمة المرور ثم اضغط حفظ الشبكة.");
    }

    private void stopListening() {
        if (scanReceiver != null) {
            try {
                unregisterReceiver(scanReceiver);
            } catch (Exception ignored) {
            }
            scanReceiver = null;
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopListening();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LOC) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                scanNetworks();
            } else {
                show("لم يُمنح إذن الموقع، لذلك لا يمكن عرض الشبكات. اكتب اسم الشبكة يدويًا.");
            }
        }
    }

    // ---------- Interface ----------

    private void refreshList() {
        listBox.removeAllViews();
        JSONArray arr = load();
        if (arr.length() == 0) {
            listBox.addView(text("لا توجد شبكات محفوظة بعد.", 16, 0xFFAAB2BD));
            return;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            final String ssid = o.optString("ssid");
            final String pass = o.optString("pass");
            boolean auto = o.optBoolean("auto");

            listBox.addView(text(ssid, 20, 0xFF8BE9FD));

            CheckBox cb = new CheckBox(this);
            cb.setText("اتصال تلقائي عند توفر الشبكة");
            cb.setTextColor(0xFFFFFFFF);
            cb.setChecked(auto);
            cb.setOnCheckedChangeListener((buttonView, on) -> setAuto(ssid, on));
            listBox.addView(cb);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.addView(button("اتصال مباشر", v -> saveToSystem(ssid, pass)), weight());
            row.addView(button("اتصال يدوي", v -> openManual()), weight());
            row.addView(button("حذف", v -> deleteNetwork(ssid)), weight());
            listBox.addView(row);
        }
    }

    private void show(String message) {
        status.setText(message);
    }

    private TextView text(String s, int size, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setPadding(0, 12, 0, 12);
        t.setGravity(Gravity.START);
        return t;
    }

    private EditText input(String hint, boolean password) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(0xFF888888);
        e.setTextColor(0xFFFFFFFF);
        e.setInputType(password
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_CLASS_TEXT);
        return e;
    }

    private Button button(String text, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(text);
        b.setOnClickListener(listener);
        return b;
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }
}
