package com.limelight.profiles;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import com.limelight.R;
import com.limelight.TestLogSuppressor;
import com.limelight.nvstream.StreamConfiguration;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.*;

@Config(sdk = {33}, shadows = {com.limelight.shadows.ShadowMoonBridge.class, com.limelight.shadows.ShadowGameManager.class})
@RunWith(RobolectricTestRunner.class)
public class ProfilesOverlayTest {
    private static final String ULTRA_LOW_LATENCY_KEY = "checkbox_ultra_low_latency";

    private Context context;

    @BeforeClass
    public static void suppressLogs() {
        TestLogSuppressor.install();
    }

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit();
        // Reset singleton and clean files to ensure clean slate
        ProfilesManager.instance = null;
        File profilesDir = new File(context.getFilesDir(), "profiles");
        deleteRecursively(profilesDir);
    }

    @Test
    public void ultraLowLatencyPreference_keepsCompatibleKeyAndDefaultsOff() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);

        PreferenceManager.setDefaultValues(context, R.xml.preferences, true);

        assertTrue(preferences.contains(ULTRA_LOW_LATENCY_KEY));
        assertFalse(preferences.getBoolean(ULTRA_LOW_LATENCY_KEY, true));
    }

    @Test
    public void ultraLowLatencyPreference_explainsAutomaticStandardAndOptionalVendorPaths() {
        assertUltraLowLatencyCopy(
                Locale.ENGLISH,
                "Vendor ultra-low-latency optimizations (experimental)",
                "Android standard low latency is enabled automatically when supported. " +
                        "This option adds capability-checked vendor decoder optimizations.");
        assertUltraLowLatencyCopy(
                Locale.FRENCH,
                "Optimisations de latence ultra-faible du fournisseur (expérimental)",
                "La faible latence standard d’Android est activée automatiquement lorsqu’elle est prise en charge. " +
                        "Cette option ajoute des optimisations de décodeur propres au fournisseur, vérifiées selon les capacités.");
        assertUltraLowLatencyCopy(
                new Locale("ru"),
                "Оптимизации сверхнизкой задержки от производителя (экспериментально)",
                "Стандартный режим низкой задержки Android включается автоматически, если поддерживается. " +
                        "Этот параметр добавляет оптимизации декодера от производителя, проверяемые по возможностям.");
        assertUltraLowLatencyCopy(
                Locale.SIMPLIFIED_CHINESE,
                "厂商超低延迟优化（实验性）",
                "支持时会自动启用 Android 标准低延迟。此选项会添加经过能力检查的厂商解码器优化。");
        assertUltraLowLatencyCopy(
                Locale.TRADITIONAL_CHINESE,
                "廠商超低延遲最佳化（實驗性）",
                "支援時會自動啟用 Android 標準低延遲。此選項會加入經能力檢查的廠商解碼器最佳化。");
    }

    @Test
    public void ultraLowLatencyPreference_isNotDuplicatedInStreamTransportConfiguration() {
        assertNoDeclaredField(StreamConfiguration.class, "enableUltraLowLatency");
        assertNoDeclaredMethod(StreamConfiguration.class, "getEnableUltraLowLatency");
        assertNoDeclaredMethod(StreamConfiguration.Builder.class, "setEnableUltraLowLatency", boolean.class);
    }

    @Test
    public void overlaySharedPreferences_returnsPatchedValues() {
        SharedPreferences base = PreferenceManager.getDefaultSharedPreferences(context);
        base.edit()
            .putBoolean(ULTRA_LOW_LATENCY_KEY, false)
            .putInt("seekbar_bitrate_kbps", 15000)
            .apply();

        Map<String, Object> patch = new HashMap<>();
        patch.put(ULTRA_LOW_LATENCY_KEY, true);
        patch.put("seekbar_bitrate_kbps", 30000);

        SettingsProfile profile = new SettingsProfile(UUID.randomUUID(), "Test", System.currentTimeMillis(), System.currentTimeMillis(), patch);
        ProfilesManager pm = ProfilesManager.getInstance();
        pm.add(profile);
        pm.setActive(profile.getUuid());

        SharedPreferences overlay = pm.getOverlayingSharedPreferences(context);
        assertTrue(overlay.getBoolean(ULTRA_LOW_LATENCY_KEY, false));
        assertEquals(30000, overlay.getInt("seekbar_bitrate_kbps", 0));
    }

    @Test
    public void overlayPersistsAcrossSessions() {
        Map<String, Object> patch = new HashMap<>();
        patch.put(ULTRA_LOW_LATENCY_KEY, true);

        SettingsProfile profile = new SettingsProfile(UUID.randomUUID(), "Persist", System.currentTimeMillis(), System.currentTimeMillis(), patch);
        ProfilesManager pm = ProfilesManager.getInstance();
        pm.add(profile);
        pm.setActive(profile.getUuid());
        pm.save(context);

        // Simulate app restart by resetting singleton
        ProfilesManager.instance = null;
        ProfilesManager fresh = ProfilesManager.getInstance();
        fresh.load(context);

        SharedPreferences overlay = fresh.getOverlayingSharedPreferences(context);
        assertTrue(overlay.getBoolean(ULTRA_LOW_LATENCY_KEY, false));
    }

    private void assertUltraLowLatencyCopy(Locale locale, String expectedTitle, String expectedSummary) {
        Configuration configuration = new Configuration(context.getResources().getConfiguration());
        configuration.setLocale(locale);
        Context localizedContext = context.createConfigurationContext(configuration);

        assertEquals(expectedTitle, localizedContext.getString(R.string.title_checkbox_ultra_low_latency));
        assertEquals(expectedSummary, localizedContext.getString(R.string.summary_checkbox_ultra_low_latency));
    }

    private void assertNoDeclaredField(Class<?> type, String fieldName) {
        try {
            type.getDeclaredField(fieldName);
            fail(type.getSimpleName() + " must not transport the ultra-low-latency preference");
        } catch (NoSuchFieldException expected) {
            // Expected: PreferenceConfiguration is the renderer's sole runtime source.
        }
    }

    private void assertNoDeclaredMethod(Class<?> type, String methodName, Class<?>... parameterTypes) {
        try {
            type.getDeclaredMethod(methodName, parameterTypes);
            fail(type.getSimpleName() + "." + methodName + " must not transport the ultra-low-latency preference");
        } catch (NoSuchMethodException expected) {
            // Expected: PreferenceConfiguration is the renderer's sole runtime source.
        }
    }

    private void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) {
                    deleteRecursively(c);
                }
            }
        }
        f.delete();
    }
}