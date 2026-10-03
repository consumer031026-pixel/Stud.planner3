package com.studentplanner.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.PendingPurchasesParams;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.QueryPurchasesParams;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.MessageDigest;
import java.util.Collections;
import java.util.List;

public class MainActivity extends Activity {
    // Subscription product id you create in Google Play Console (Monetize > Subscriptions).
    static final String SKU = "pro_monthly";
    // SHA-256 (hex, no colons) of YOUR release signing certificate. Leave empty while testing.
    // When set, a repackaged copy signed with any other key closes itself.
    static final String EXPECTED_SIG = "";

    private WebView web;
    private ValueCallback<Uri[]> picker;
    private BillingClient bc;
    private ProductDetails details;
    private volatile boolean pro = false;
    private volatile String priceText = "\u20B910 / month";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (!sigOk()) { finish(); return; }
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);      // android_asset still loads
        s.setAllowContentAccess(false);
        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return !r.getUrl().toString().startsWith("file:///android_asset/");
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (picker != null) picker.onReceiveValue(null);
                picker = cb;
                try {
                    startActivityForResult(p.createIntent(), 7);
                } catch (Exception e) {
                    picker = null;
                    return false;
                }
                return true;
            }
        });
        web.loadUrl("file:///android_asset/index.html");
        initBilling();
    }

    @Override
    protected void onActivityResult(int rq, int rs, Intent d) {
        if (rq == 7 && picker != null) {
            picker.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(rs, d));
            picker = null;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (bc != null && bc.isReady()) queryPurchases();
    }

    // ---- Google Play Billing ----
    private void initBilling() {
        bc = BillingClient.newBuilder(this)
            .setListener((r, list) -> {
                if (r.getResponseCode() == BillingClient.BillingResponseCode.OK && list != null) handle(list);
            })
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .build();
        bc.startConnection(new BillingClientStateListener() {
            @Override public void onBillingSetupFinished(BillingResult r) {
                if (r.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                    queryPurchases();
                    queryDetails();
                }
            }
            @Override public void onBillingServiceDisconnected() { }
        });
    }

    private void queryPurchases() {
        bc.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build(),
            (r, list) -> { if (r.getResponseCode() == BillingClient.BillingResponseCode.OK) handle(list); });
    }

    private void queryDetails() {
        QueryProductDetailsParams q = QueryProductDetailsParams.newBuilder()
            .setProductList(Collections.singletonList(
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(SKU).setProductType(BillingClient.ProductType.SUBS).build()))
            .build();
        bc.queryProductDetailsAsync(q, (r, list) -> {
            if (r.getResponseCode() == BillingClient.BillingResponseCode.OK && list != null && !list.isEmpty()) {
                details = list.get(0);
                List<ProductDetails.SubscriptionOfferDetails> o = details.getSubscriptionOfferDetails();
                if (o != null && !o.isEmpty()) {
                    priceText = o.get(0).getPricingPhases().getPricingPhaseList().get(0).getFormattedPrice() + " / month";
                }
            }
        });
    }

    private void handle(List<Purchase> list) {
        boolean p = false;
        for (Purchase pu : list) {
            if (pu.getPurchaseState() == Purchase.PurchaseState.PURCHASED && pu.getProducts().contains(SKU)) {
                p = true;
                if (!pu.isAcknowledged()) {
                    bc.acknowledgePurchase(
                        AcknowledgePurchaseParams.newBuilder().setPurchaseToken(pu.getPurchaseToken()).build(), r -> { });
                }
            }
        }
        pro = p;
        runOnUiThread(() -> web.evaluateJavascript("window.onPro&&onPro()", null));
    }

    private void subscribe() {
        runOnUiThread(() -> {
            if (bc == null || details == null) return;
            List<ProductDetails.SubscriptionOfferDetails> o = details.getSubscriptionOfferDetails();
            if (o == null || o.isEmpty()) return;
            BillingFlowParams.ProductDetailsParams pd = BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(details).setOfferToken(o.get(0).getOfferToken()).build();
            bc.launchBillingFlow(this, BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(Collections.singletonList(pd)).build());
        });
    }

    // ---- reminders ----
    private PendingIntent pi(int id, String t) {
        Intent i = new Intent(this, ReminderReceiver.class);
        i.putExtra("t", t);
        i.putExtra("i", id);
        return PendingIntent.getBroadcast(this, id, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void schedule(String json) {
        try {
            AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
            SharedPreferences sp = getSharedPreferences("r", MODE_PRIVATE);
            int old = sp.getInt("n", 0);
            for (int i = 0; i < old; i++) am.cancel(pi(i, ""));
            JSONArray a = new JSONArray(json);
            int n = Math.min(a.length(), 60);
            for (int i = 0; i < n; i++) {
                JSONObject o = a.getJSONObject(i);
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, o.getLong("at"), pi(i, o.getString("t")));
            }
            sp.edit().putInt("n", n).apply();
        } catch (Exception ignored) { }
    }

    // ---- tamper check ----
    @SuppressWarnings("deprecation")
    private boolean sigOk() {
        if (EXPECTED_SIG.isEmpty()) return true;
        try {
            Signature[] sigs;
            if (Build.VERSION.SDK_INT >= 28) {
                sigs = getPackageManager().getPackageInfo(getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES)
                    .signingInfo.getApkContentsSigners();
            } else {
                sigs = getPackageManager().getPackageInfo(getPackageName(), PackageManager.GET_SIGNATURES).signatures;
            }
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (Signature g : sigs) {
                StringBuilder sb = new StringBuilder();
                for (byte x : md.digest(g.toByteArray())) sb.append(String.format("%02x", x));
                if (sb.toString().equalsIgnoreCase(EXPECTED_SIG)) return true;
            }
        } catch (Exception ignored) { }
        return false;
    }

    // ---- JS bridge ----
    class Bridge {
        // Debug builds are unlocked so you can test; release builds follow Google Play only.
        @JavascriptInterface public boolean isPro() { return BuildConfig.DEBUG || pro; }
        @JavascriptInterface public String price() { return priceText; }
        @JavascriptInterface public void subscribe() { MainActivity.this.subscribe(); }
        @JavascriptInterface public void refresh() { runOnUiThread(() -> { if (bc != null && bc.isReady()) queryPurchases(); }); }
        @JavascriptInterface public void sync(String json) { schedule(json); }
    }
}
