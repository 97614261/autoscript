package com.autoscript.studio;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Framework-only Java: an optimized target need not retain Kotlin/AndroidX test dependencies. */
public final class StudioReleaseSmokeInstrumentation extends Instrumentation {
    private String expectedDigest = "";
    private int expectedCode;
    private long deadline;
    private String phase = "identity";

    @Override public void onCreate(Bundle arguments) {
        if (arguments != null) {
            expectedDigest = arguments.getString("expectedTargetApkSha256", "");
            try { expectedCode = Integer.parseInt(arguments.getString("expectedVersionCode", "0")); }
            catch (NumberFormatException ignored) { expectedCode = 0; }
        }
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        JSONObject report = put(new JSONObject(), "scope", "optimized installed APK identity and public navigation only; no scripts or system settings");
        JSONArray cases = new JSONArray();
        put(report, "cases", cases);
        deadline = SystemClock.elapsedRealtime() + 90_000;
        try {
            check(expectedDigest.matches("[a-f0-9]{64}") && expectedCode > 0, "RELEASE_SMOKE_EXPECTATION_REQUIRED");
            String actualDigest = digest(getTargetContext().getApplicationInfo().sourceDir);
            put(report, "targetApkSha256", actualDigest);
            put(report, "testApkSha256", digest(getContext().getApplicationInfo().sourceDir));
            check(actualDigest.equals(expectedDigest), "RELEASE_SMOKE_APK_DIFFERS");
            @SuppressWarnings("deprecation")
            PackageInfo info = getTargetContext().getPackageManager().getPackageInfo(getTargetContext().getPackageName(), 0);
            check(info.versionCode == expectedCode, "RELEASE_SMOKE_VERSION_DIFFERS");
            put(report, "versionName", info.versionName);
            put(report, "versionCode", expectedCode);
            passed(cases, "installed-optimized-apk-digest-and-version");
            AccessibilityServiceInfo service = getUiAutomation().getServiceInfo();
            service.flags |= AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS |
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            getUiAutomation().setServiceInfo(service);
            phase = "launch";
            Intent launch = getTargetContext().getPackageManager().getLaunchIntentForPackage(getTargetContext().getPackageName());
            check(launch != null, "RELEASE_SMOKE_LAUNCH_MISSING");
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            getTargetContext().startActivity(launch);
            await(() -> node("首页") != null && node("项目") != null && node("我的") != null);
            passed(cases, "optimized-launch-and-navigation-visible");
            phase = "projects";
            click("项目");
            await(() -> node("新建") != null && node("导入") != null);
            passed(cases, "optimized-project-toolbar-reachable");
            phase = "documents";
            click("我的");
            await(() -> node("函数文档") != null);
            click("函数文档");
            await(() -> node("关闭函数文档") != null);
            click("关闭函数文档");
            await(() -> node("关闭函数文档") == null);
            passed(cases, "optimized-profile-and-generated-documents-open-close");
            phase = "home";
            click("首页");
            await(() -> node("智构互动空间") != null);
            passed(cases, "optimized-return-home");
            put(report, "pass", true);
        } catch (Throwable failure) {
            String code = failure.getMessage();
            if (code == null || !code.matches("[A-Z_]{1,80}")) code = "RELEASE_SMOKE_FAILED";
            put(report, "pass", false);
            put(report, "code", code);
            put(report, "phase", phase);
            AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
            put(report, "rootAvailable", root != null);
            put(report, "rootMatchesTarget", root != null && root.getPackageName() != null &&
                getTargetContext().getPackageName().contentEquals(root.getPackageName()));
            put(report, "homeVisible", node("首页") != null);
            put(report, "projectsVisible", node("项目") != null);
            put(report, "profileVisible", node("我的") != null);
        }
        put(report, "cleanup", true);
        Bundle result = new Bundle();
        result.putString("stream", report.toString() + "\n");
        finish(report.optBoolean("pass") ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }

    private static String digest(String path) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(new File(path))) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) >= 0) hash.update(buffer, 0, count);
        }
        StringBuilder result = new StringBuilder(64);
        for (byte value : hash.digest()) {
            result.append(Character.forDigit((value & 255) >>> 4, 16));
            result.append(Character.forDigit(value & 15, 16));
        }
        return result.toString();
    }

    private static JSONObject put(JSONObject object, String key, Object value) {
        try { object.put(key, value); return object; }
        catch (JSONException failure) { throw new IllegalStateException("RELEASE_SMOKE_JSON_FAILED"); }
    }

    private static void passed(JSONArray cases, String name) {
        cases.put(put(put(new JSONObject(), "case", name), "pass", true));
    }

    private static void check(boolean condition, String code) {
        if (!condition) throw new IllegalStateException(code);
    }

    private AccessibilityNodeInfo node(String label) {
        AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
        if (root == null || root.getPackageName() == null ||
            !getTargetContext().getPackageName().contentEquals(root.getPackageName())) return null;
        return find(root, label);
    }

    private AccessibilityNodeInfo find(AccessibilityNodeInfo candidate, String label) {
        if (candidate == null) return null;
        if (label.contentEquals(candidate.getText() == null ? "" : candidate.getText()) ||
            label.contentEquals(candidate.getContentDescription() == null ? "" : candidate.getContentDescription())) return candidate;
        for (int index = 0; index < candidate.getChildCount(); index++) {
            AccessibilityNodeInfo match = find(candidate.getChild(index), label);
            if (match != null) return match;
        }
        return null;
    }

    private interface Condition { boolean ready(); }

    private void await(Condition condition) {
        long until = Math.min(deadline, SystemClock.elapsedRealtime() + 12_000);
        while (SystemClock.elapsedRealtime() < until) {
            if (condition.ready()) return;
            SystemClock.sleep(80);
        }
        throw new IllegalStateException("RELEASE_SMOKE_UI_WAIT_FAILED");
    }

    private void click(String label) {
        long until = Math.min(deadline, SystemClock.elapsedRealtime() + 12_000);
        while (SystemClock.elapsedRealtime() < until) {
            AccessibilityNodeInfo candidate = node(label);
            for (int depth = 0; depth < 8 && candidate != null; depth++) {
                if (candidate.refresh() && candidate.isClickable() && candidate.isEnabled()) {
                    check(candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK), "RELEASE_SMOKE_CLICK_FAILED");
                    return;
                }
                candidate = candidate.getParent();
            }
            SystemClock.sleep(80);
        }
        throw new IllegalStateException("RELEASE_SMOKE_NOT_CLICKABLE");
    }
}
