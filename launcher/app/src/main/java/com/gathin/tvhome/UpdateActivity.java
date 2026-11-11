package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class UpdateActivity extends Activity {
    private static final String RELEASE_API =
            "https://api.github.com/repos/adoftgggg/cleanyourtv/releases/latest";
    private static final String EXPECTED_CERT =
            "87641a43f3840a667fae1f5fa5ff7a45b25a159886cad429b0416085c5630771";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private TextView status;
    private TextView action;
    private ProgressBar progress;
    private String apkUrl;
    private String checksumUrl;
    private long remoteVersionCode;
    private String remoteVersionName;

    @Override protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setPadding(64, 36, 64, 36);
        root.setBackgroundColor(0xFF101419);

        TextView title = label("Mise à jour CleanYourTV", 30, 0xFFFFFFFF);
        root.addView(title);
        status = label("Vérification des mises à jour…", 18, 0xFFACB8C1);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2);
        statusParams.topMargin = 24;
        root.addView(status, statusParams);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(-1, -2);
        progressParams.topMargin = 20;
        root.addView(progress, progressParams);

        action = label("Rechercher à nouveau", 20, 0xFFFFFFFF);
        action.setFocusable(true);
        action.setClickable(true);
        action.setPadding(18, 18, 18, 18);
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(-1, -2);
        actionParams.topMargin = 28;
        root.addView(action, actionParams);
        action.setOnClickListener(v -> checkRelease());
        setContentView(root);
        checkRelease();
    }

    private TextView label(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private void checkRelease() {
        action.setEnabled(false);
        status.setText("Recherche de la dernière version stable…");
        progress.setVisibility(View.GONE);
        executor.execute(() -> {
            try {
                JSONObject release = new JSONObject(request(RELEASE_API));
                if (release.optBoolean("draft", false) || release.optBoolean("prerelease", false))
                    throw new Exception("La dernière Release n'est pas une version stable.");
                JSONArray assets = release.getJSONArray("assets");
                String foundApk = null, foundChecksum = null;
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject asset = assets.getJSONObject(i);
                    String name = asset.getString("name").toLowerCase(Locale.ROOT);
                    if (name.endsWith(".apk")) foundApk = asset.getString("browser_download_url");
                    if (name.endsWith(".apk.sha256") || name.equals("sha256.txt"))
                        foundChecksum = asset.getString("browser_download_url");
                }
                if (foundApk == null || foundChecksum == null)
                    throw new Exception("Release incomplète : APK ou fichier SHA-256 absent.");
                String releaseBody = release.optString("body", "");
                Matcher versionMatcher = Pattern.compile("(?i)\\bversionCode\\s*[:=]\\s*(\\d+)\\b")
                        .matcher(releaseBody);
                if (!versionMatcher.find()) {
                    throw new Exception("Métadonnée versionCode introuvable dans la description GitHub. Description reçue : "
                            + (releaseBody.isEmpty() ? "(vide)" : releaseBody));
                }
                long code = Long.parseLong(versionMatcher.group(1));
                String name = release.optString("name", release.getString("tag_name"));
                apkUrl = foundApk;
                checksumUrl = foundChecksum;
                remoteVersionCode = code;
                remoteVersionName = name;
                runOnUiThread(() -> {
                    action.setEnabled(true);
                    if (remoteVersionCode <= installedVersionCode()) {
                        status.setText("Aucune mise à jour disponible. Version installée : "
                                + installedVersionName() + " (" + installedVersionCode() + ").");
                        action.setText("Rechercher à nouveau");
                    } else {
                        status.setText("Version disponible : " + remoteVersionName
                                + "\nVersion installée : " + installedVersionName()
                                + "\n\nAppuyer sur OK pour télécharger.");
                        action.setText("Télécharger et installer");
                        action.setOnClickListener(v -> downloadAndInstall());
                    }
                });
            } catch (Exception e) {
                showError("Vérification impossible : " + e.getMessage());
            }
        });
    }

    private long installedVersionCode() {
        try {
            PackageInfo p = getPackageManager().getPackageInfo(getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? p.getLongVersionCode() : p.versionCode;
        } catch (Exception e) { return 0; }
    }

    private String installedVersionName() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Exception e) { return "?"; }
    }

    private void downloadAndInstall() {
        action.setEnabled(false);
        action.setText("Téléchargement…");
        progress.setVisibility(View.VISIBLE);
        executor.execute(() -> {
            File apk = null;
            try {
                String checksum = request(checksumUrl).trim().split("\\s+")[0].toLowerCase(Locale.ROOT);
                if (!checksum.matches("[0-9a-f]{64}")) throw new Exception("Format SHA-256 invalide.");
                File dir = new File(getCacheDir(), "ota");
                if (!dir.exists() && !dir.mkdirs()) throw new Exception("Impossible de créer le cache OTA.");
                apk = new File(dir, "cleanyourtv-update.apk");
                HttpURLConnection c = (HttpURLConnection) new URL(apkUrl).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                c.setInstanceFollowRedirects(true);
                c.setRequestProperty("User-Agent", "CleanYourTV-OTA");
                int response = c.getResponseCode();
                if (response != 200) throw new Exception("Téléchargement HTTP " + response);
                int total = c.getContentLength();
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (InputStream in = new BufferedInputStream(c.getInputStream());
                     FileOutputStream out = new FileOutputStream(apk)) {
                    byte[] buffer = new byte[32768];
                    int count, done = 0;
                    while ((count = in.read(buffer)) != -1) {
                        digest.update(buffer, 0, count);
                        out.write(buffer, 0, count);
                        done += count;
                        final int percent = total > 0 ? (int) (done * 100L / total) : 0;
                        runOnUiThread(() -> progress.setProgress(percent));
                    }
                } finally { c.disconnect(); }
                String actual = toHex(digest.digest());
                if (!actual.equals(checksum)) throw new Exception("Échec de vérification SHA-256.");
                verifyApkCertificate(apk);
                File finalApk = apk;
                runOnUiThread(() -> launchInstaller(finalApk));
            } catch (Exception e) {
                if (apk != null) apk.delete();
                showError("Mise à jour interrompue : " + e.getMessage());
            }
        });
    }

    private void verifyApkCertificate(File apk) throws Exception {
        PackageManager pm = getPackageManager();
        PackageInfo installed;
        PackageInfo downloaded;
        if (Build.VERSION.SDK_INT >= 28) {
            installed = pm.getPackageInfo(getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
            downloaded = pm.getPackageArchiveInfo(apk.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
            if (downloaded == null || downloaded.signingInfo == null || installed.signingInfo == null)
                throw new Exception("Signature APK illisible.");
            byte[][] certs = downloaded.signingInfo.getApkContentsSigners()[0].toByteArray().length > 0
                    ? new byte[][]{downloaded.signingInfo.getApkContentsSigners()[0].toByteArray()} : new byte[0][];
            if (certs.length == 0 || !toHex(MessageDigest.getInstance("SHA-256").digest(certs[0])).equals(EXPECTED_CERT))
                throw new Exception("Certificat de signature non reconnu.");
        } else {
            throw new Exception("Vérification de signature non prise en charge sur Android antérieur à 9.");
        }
        if (!getPackageName().equals(downloaded.packageName))
            throw new Exception("Identifiant d'application incorrect.");
        long code = Build.VERSION.SDK_INT >= 28 ? downloaded.getLongVersionCode() : downloaded.versionCode;
        if (code <= installedVersionCode()) throw new Exception("Le numéro de version n'est pas supérieur à la version installée.");
    }

    private void launchInstaller(File apk) {
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".ota.provider", apk);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
            status.setText("APK vérifié. Confirme l'installation dans l'installateur Android.");
            action.setText("Terminé");
        } catch (Exception e) {
            showError("Impossible d'ouvrir l'installateur : " + e.getMessage());
        }
    }

    private String request(String address) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        c.setRequestProperty("User-Agent", "CleanYourTV-OTA");
        try {
            int code = c.getResponseCode();
            InputStream stream = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = stream.read(buffer)) != -1) out.write(buffer, 0, n);
            if (code < 200 || code >= 300) throw new Exception("GitHub HTTP " + code);
            return out.toString("UTF-8");
        } finally { c.disconnect(); }
    }

    private String toHex(byte[] bytes) {
        StringBuilder s = new StringBuilder();
        for (byte b : bytes) s.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return s.toString();
    }

    private void showError(String message) {
        runOnUiThread(() -> {
            status.setText(message);
            action.setEnabled(true);
            action.setText("Réessayer");
            action.setOnClickListener(v -> checkRelease());
            progress.setVisibility(View.GONE);
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        });
    }

    @Override protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
