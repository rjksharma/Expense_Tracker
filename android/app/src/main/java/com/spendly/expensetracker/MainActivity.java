package com.spendly.expensetracker;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.NonNull;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.tasks.Task;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GoogleAuthProvider;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;

import org.json.JSONObject;
import org.json.JSONArray;

import java.util.concurrent.Executor;

public class MainActivity extends FragmentActivity {
    private WebView webView;
    private ValueCallback<Uri[]> fileCallback;
    private SharedPreferences appPreferences;
    private static final int FILE_CHOOSER_REQUEST = 1001;
    private static final int GOOGLE_SIGN_IN_REQUEST = 1002;
    private static final String FINGERPRINT_KEY = "fingerprint_unlock";
    private static final String ADMIN_EMAIL = "rjksharma23@gmail.com";
    private FirebaseAuth firebaseAuth;
    private FirebaseFirestore firestore;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        appPreferences = getSharedPreferences("spendly", MODE_PRIVATE);
        firebaseAuth = FirebaseAuth.getInstance();
        firestore = FirebaseFirestore.getInstance();
        webView = new WebView(this);
        setContentView(webView);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        webView.addJavascriptInterface(new FingerprintBridge(), "Android");
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (appPreferences.getBoolean(FINGERPRINT_KEY, false)) {
                    webView.setVisibility(View.INVISIBLE);
                    requestFingerprint(false);
                }
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try { startActivityForResult(params.createIntent(), FILE_CHOOSER_REQUEST); }
                catch (Exception error) { fileCallback = null; return false; }
                return true;
            }
        });
        webView.loadUrl("file:///android_asset/index.html");
    }

    private void requestFingerprint(boolean settingUp) {
        int status = BiometricManager.from(this).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG);
        if (status != BiometricManager.BIOMETRIC_SUCCESS) {
            callbackFingerprint(false, "Set up a fingerprint in your phone settings first.");
            webView.setVisibility(View.VISIBLE);
            return;
        }
        Executor executor = ContextCompat.getMainExecutor(this);
        BiometricPrompt prompt = new BiometricPrompt(this, executor, new BiometricPrompt.AuthenticationCallback() {
            @Override public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                super.onAuthenticationSucceeded(result);
                if (settingUp) appPreferences.edit().putBoolean(FINGERPRINT_KEY, true).apply();
                webView.setVisibility(View.VISIBLE);
                if (settingUp) callbackFingerprint(true, "Fingerprint unlock enabled.");
                else webView.evaluateJavascript("window.onNativeFingerprintUnlock && window.onNativeFingerprintUnlock()", null);
            }
            @Override public void onAuthenticationError(int code, @NonNull CharSequence error) {
                super.onAuthenticationError(code, error);
                webView.setVisibility(View.VISIBLE);
                if (settingUp) callbackFingerprint(false, error.toString());
                else webView.evaluateJavascript("window.onNativeFingerprintFallback && window.onNativeFingerprintFallback()", null);
            }
        });
        BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
                .setTitle(settingUp ? "Enable fingerprint unlock" : "Unlock Spendly")
                .setSubtitle("Use your fingerprint to continue")
                .setNegativeButtonText("Cancel")
                .build();
        prompt.authenticate(info);
    }

    private void callbackFingerprint(boolean enabled, String message) {
        String safe = message.replace("'", "\\'");
        webView.evaluateJavascript("window.onFingerprintResult && window.onFingerprintResult(" + enabled + ", '" + safe + "')", null);
    }

    private class FingerprintBridge {
        @JavascriptInterface public boolean isFingerprintEnabled() { return appPreferences.getBoolean(FINGERPRINT_KEY, false); }
        @JavascriptInterface public void enableFingerprint() { runOnUiThread(() -> requestFingerprint(true)); }
        @JavascriptInterface public void disableFingerprint() { appPreferences.edit().putBoolean(FINGERPRINT_KEY, false).apply(); }

        @JavascriptInterface public String cloudUser() {
            FirebaseUser user = firebaseAuth.getCurrentUser();
            return user == null ? "" : user.getEmail();
        }
        @JavascriptInterface public String cloudProfile() {
            FirebaseUser user = firebaseAuth.getCurrentUser();
            JSONObject profile = new JSONObject();
            try {
                if (user != null) { profile.put("name", user.getDisplayName()); profile.put("email", user.getEmail()); profile.put("photo", user.getPhotoUrl() == null ? "" : user.getPhotoUrl().toString()); }
            } catch (Exception ignored) { }
            return profile.toString();
        }
        @JavascriptInterface public boolean isAdmin() {
            FirebaseUser user = firebaseAuth.getCurrentUser();
            return user != null && user.getEmail() != null && ADMIN_EMAIL.equalsIgnoreCase(user.getEmail());
        }
        @JavascriptInterface public void getAdminData() { runOnUiThread(() -> loadAdminData()); }
        @JavascriptInterface public void googleSignIn() { runOnUiThread(() -> startGoogleSignIn()); }
        @JavascriptInterface public void signOutCloud() {
            firebaseAuth.signOut();
            GoogleSignIn.getClient(MainActivity.this, googleOptions()).signOut().addOnCompleteListener(task -> cloudCallback("status", true, "Signed out", ""));
        }
        @JavascriptInterface public void backupCloud(String payload) { runOnUiThread(() -> backupCloudData(payload)); }
        @JavascriptInterface public void restoreCloud() { runOnUiThread(() -> restoreCloudData()); }
    }

    private void startGoogleSignIn() {
        int resourceId = getResources().getIdentifier("default_web_client_id", "string", getPackageName());
        if (resourceId == 0) {
            cloudCallback("status", false, "Download a fresh google-services.json after enabling Google sign-in in Firebase, then rebuild the app.", "");
            return;
        }
        GoogleSignInClient client = GoogleSignIn.getClient(this, googleOptions());
        startActivityForResult(client.getSignInIntent(), GOOGLE_SIGN_IN_REQUEST);
    }

    private GoogleSignInOptions googleOptions() {
        int resourceId = getResources().getIdentifier("default_web_client_id", "string", getPackageName());
        return new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(resourceId == 0 ? "" : getString(resourceId)).requestEmail().build();
    }

    private void finishGoogleSignIn(Intent data) {
        Task<GoogleSignInAccount> task = GoogleSignIn.getSignedInAccountFromIntent(data);
        try {
            GoogleSignInAccount account = task.getResult(ApiException.class);
            AuthCredential credential = GoogleAuthProvider.getCredential(account.getIdToken(), null);
            firebaseAuth.signInWithCredential(credential).addOnCompleteListener(this, result -> {
                FirebaseUser user = firebaseAuth.getCurrentUser();
                if (result.isSuccessful() && user != null) cloudCallback("status", true, "Signed in", user.getEmail());
                else cloudCallback("status", false, "Google sign-in failed. Please try again.", "");
            });
        } catch (ApiException error) { cloudCallback("status", false, "Google sign-in was cancelled or unavailable.", ""); }
    }

    private void backupCloudData(String payload) {
        FirebaseUser user = firebaseAuth.getCurrentUser();
        if (user == null) { cloudCallback("backup", false, "Sign in with Google first.", ""); return; }
        java.util.Map<String, Object> data = new java.util.HashMap<>();
        data.put("payload", payload);
        data.put("updatedAt", FieldValue.serverTimestamp());
        data.put("version", 1);
        firestore.collection("users").document(user.getUid()).collection("appData").document("spendly")
                .set(data).addOnSuccessListener(v -> cloudCallback("backup", true, "Cloud backup complete.", ""))
                .addOnFailureListener(e -> cloudCallback("backup", false, "Backup failed. Check Firestore rules and your connection.", ""));
    }

    private void loadAdminData() {
        if (!new FingerprintBridge().isAdmin()) { cloudCallback("admin", false, "Admin access is required.", ""); return; }
        firestore.collectionGroup("appData").get().addOnSuccessListener(snapshot -> {
            JSONArray users = new JSONArray();
            for (com.google.firebase.firestore.DocumentSnapshot document : snapshot.getDocuments()) {
                if (!"spendly".equals(document.getId()) || document.getString("payload") == null) continue;
                JSONObject item = new JSONObject();
                try {
                    item.put("uid", document.getReference().getParent().getParent().getId());
                    item.put("payload", document.getString("payload"));
                    users.put(item);
                } catch (Exception ignored) { }
            }
            cloudCallback("admin", true, "User data loaded.", users.toString());
        }).addOnFailureListener(e -> cloudCallback("admin", false, "Admin dashboard error: " + (e.getMessage() == null ? "unknown Firebase error" : e.getMessage()), ""));
    }

    private void restoreCloudData() {
        FirebaseUser user = firebaseAuth.getCurrentUser();
        if (user == null) { cloudCallback("restore", false, "Sign in with Google first.", ""); return; }
        firestore.collection("users").document(user.getUid()).collection("appData").document("spendly").get()
                .addOnSuccessListener(doc -> {
                    if (!doc.exists() || doc.getString("payload") == null) cloudCallback("restore", false, "No cloud backup found for this account.", "");
                    else cloudCallback("restore", true, "Cloud data restored.", doc.getString("payload"));
                }).addOnFailureListener(e -> cloudCallback("restore", false, "Restore failed. Check Firestore rules and your connection.", ""));
    }

    private void cloudCallback(String action, boolean success, String message, String payload) {
        JSONObject result = new JSONObject();
        try { result.put("action", action); result.put("success", success); result.put("message", message); result.put("payload", payload); }
        catch (Exception ignored) { }
        webView.evaluateJavascript("window.onCloudResult && window.onCloudResult(" + result.toString() + ")", null);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_REQUEST && fileCallback != null) {
            fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            fileCallback = null;
        }
        if (requestCode == GOOGLE_SIGN_IN_REQUEST && resultCode == RESULT_OK && data != null) finishGoogleSignIn(data);
    }

    @Override public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack(); else super.onBackPressed();
    }
}
