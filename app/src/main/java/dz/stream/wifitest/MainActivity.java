package dz.stream.wifitest;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSpecifier;
import android.net.wifi.WifiNetworkSuggestion;
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
import java.util.Collections;
import java.util.List;

public class MainActivity extends Activity {
    // Les réseaux et les mots de passe sont sauvegardés uniquement sur le téléviseur (stockage privé de l'application).
    private static final String PREFS = "wifi_networks";
    private static final String KEY_LIST = "list";

    private SharedPreferences prefs;
    private ConnectivityManager cm;
    private WifiManager wifi;
    private ConnectivityManager.NetworkCallback callback;

    private LinearLayout listBox;
    private TextView status;
    private EditText newSsid;
    private EditText newPass;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28, 24, 28, 24);
        root.setBackgroundColor(0xFF101820);

        root.addView(text("Wi-Fi Manager", 28, 0xFFFFFFFF));

        root.addView(text("إضافة شبكة جديدة", 20, 0xFF8BE9FD));
        newSsid = input("اسم الشبكة (SSID)", false);
        newPass = input("كلمة المرور", true);
        root.addView(newSsid);
        root.addView(newPass);
        root.addView(button("حفظ الشبكة", v -> addNetwork()));

        root.addView(text("الشبكات المحفوظة", 20, 0xFF8BE9FD));
        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(listBox);

        root.addView(button("قطع طلب الاتصال المباشر الحالي", v -> {
            releaseRequest();
            show("تم قطع طلب الاتصال المباشر.");
        }));

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
                // Même réseau déjà enregistré : on remplace le mot de passe et on garde le réglage auto.
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
        show("تم حذف " + ssid);
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
        show((on ? "تم تفعيل" : "تم إيقاف") + " الاتصال التلقائي لـ " + ssid);
    }

    // Enregistre ou retire la shared suggestion dans le système Android.
    private void suggest(String ssid, String pass, boolean on) {
        List<WifiNetworkSuggestion> list = Collections.singletonList(
                new WifiNetworkSuggestion.Builder()
                        .setSsid(ssid)
                        .setWpa2Passphrase(pass)
                        .build());
        try {
            int result = on ? wifi.addNetworkSuggestions(list) : wifi.removeNetworkSuggestions(list);
            if (on && result != WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
                show("تعذّر تفعيل الاتصال التلقائي لـ " + ssid + " (رمز " + result + ")");
            }
        } catch (Exception e) {
            show("خطأ في الاتصال التلقائي: " + e.getMessage());
        }
    }

    // Au démarrage, on réinscrit les réseaux dont le réglage auto est actif.
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

    private void connectDirect(String ssid, String pass) {
        releaseRequest();

        WifiNetworkSpecifier spec = new WifiNetworkSpecifier.Builder()
                .setSsid(ssid)
                .setWpa2Passphrase(pass)
                .build();

        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(spec)
                .build();

        callback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                runOnUiThread(() -> show("نجح الاتصال بـ " + ssid));
            }

            @Override
            public void onUnavailable() {
                runOnUiThread(() -> show("فشل الاتصال بـ " + ssid + " (رُفض الطلب أو انتهت المهلة)"));
            }
        };

        show("جاري الاتصال المباشر بـ " + ssid + " ... وافق على النافذة إن ظهرت.");
        cm.requestNetwork(request, callback, 30000);
    }

    private void openManual() {
        try {
            startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
        } catch (Exception e) {
            show("تعذّر فتح إعدادات الواي فاي على هذا التلفاز.");
        }
    }

    private void releaseRequest() {
        if (callback != null) {
            try {
                cm.unregisterNetworkCallback(callback);
            } catch (Exception ignored) {
            }
            callback = null;
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
            row.addView(button("اتصال مباشر", v -> connectDirect(ssid, pass)), weight());
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
