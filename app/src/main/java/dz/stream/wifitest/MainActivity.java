package dz.stream.wifitest;

import android.app.Activity;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {
    // Noms des réseaux seulement. Les mots de passe se tapent sur l'écran, jamais dans le code.
    private static final String[] SSIDS = {"D LINK 10_Plus_5G", "D-10"};

    private ConnectivityManager cm;
    private ConnectivityManager.NetworkCallback callback;
    private TextView status;
    private final EditText[] passwords = new EditText[SSIDS.length];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(28, 24, 28, 24);
        layout.setBackgroundColor(0xFF101820);

        layout.addView(text("Wi-Fi Test", 28, 0xFFFFFFFF));
        layout.addView(text("اكتب كلمة مرور كل شبكة ثم اضغط اتصال", 18, 0xFFE0E6ED));

        for (int i = 0; i < SSIDS.length; i++) {
            final int index = i;
            layout.addView(text(SSIDS[i], 20, 0xFF8BE9FD));

            passwords[i] = new EditText(this);
            passwords[i].setHint("كلمة المرور");
            passwords[i].setHintTextColor(0xFF888888);
            passwords[i].setTextColor(0xFFFFFFFF);
            passwords[i].setInputType(
                    InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            layout.addView(passwords[i]);

            Button connect = new Button(this);
            connect.setText("اتصال بـ " + SSIDS[i]);
            connect.setOnClickListener(v -> connect(index));
            layout.addView(connect);
        }

        Button disconnect = new Button(this);
        disconnect.setText("قطع طلب الاتصال");
        disconnect.setOnClickListener(v -> releaseRequest());
        layout.addView(disconnect);

        status = text("جاهز.", 18, 0xFFFFD166);
        layout.addView(status);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(layout);
        setContentView(scroll);
    }

    private void connect(int index) {
        final String ssid = SSIDS[index];
        String pass = passwords[index].getText().toString();
        if (pass.length() < 8) {
            show("كلمة المرور قصيرة أو فارغة (8 أحرف على الأقل).");
            return;
        }

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

        show("جاري الاتصال بـ " + ssid + " ... وافق على النافذة التي تظهر.");
        cm.requestNetwork(request, callback, 30000);
    }

    private void releaseRequest() {
        if (callback != null) {
            try {
                cm.unregisterNetworkCallback(callback);
            } catch (Exception ignored) {
            }
            callback = null;
            show("تم قطع طلب الاتصال.");
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
}
