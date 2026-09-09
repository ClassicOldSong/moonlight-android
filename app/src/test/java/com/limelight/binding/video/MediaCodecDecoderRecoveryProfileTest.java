package com.limelight.binding.video;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.media.MediaFormat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {33})
public class MediaCodecDecoderRecoveryProfileTest {
    @Test
    public void zeroOptionProfilesAttemptsBaseExactlyOnce() {
        List<MediaCodecDecoderRenderer.DecoderConfigurationProfile> attempts =
                MediaCodecDecoderRenderer.selectConfigurationProfiles(0, profileIndex -> null);

        assertEquals(1, attempts.size());
        assertBase(attempts.get(0));
    }

    @Test
    public void earlyOptionExhaustionAttemptsBaseImmediatelyNext() {
        List<Integer> requestedIndices = new ArrayList<>();
        List<MediaCodecDecoderRenderer.DecoderConfigurationProfile> attempts =
                MediaCodecDecoderRenderer.selectConfigurationProfiles(0, profileIndex -> {
                    requestedIndices.add(profileIndex);
                    return profileIndex < 2 ? applied(profileIndex, "option-" + profileIndex) : null;
                });

        assertEquals(Arrays.asList(0, 1, 2), requestedIndices);
        assertEquals(Arrays.asList("option-0", "option-1", "base"), names(attempts));
        assertBase(attempts.get(2));
    }

    @Test
    public void elevenOptionProfilesReserveTwelfthAttemptForBase() {
        AtomicInteger sourceCalls = new AtomicInteger();
        List<MediaCodecDecoderRenderer.DecoderConfigurationProfile> attempts =
                MediaCodecDecoderRenderer.selectConfigurationProfiles(0, profileIndex -> {
                    sourceCalls.incrementAndGet();
                    return profileIndex < 11 ? applied(profileIndex, "option-" + profileIndex) : null;
                });

        assertEquals(11, sourceCalls.get());
        assertEquals(12, attempts.size());
        assertEquals(10, attempts.get(10).profileIndex);
        assertBase(attempts.get(11));
    }

    @Test
    public void brokenEndlessSourceIsTruncatedBeforeForcedBase() {
        AtomicInteger sourceCalls = new AtomicInteger();
        List<MediaCodecDecoderRenderer.DecoderConfigurationProfile> attempts =
                MediaCodecDecoderRenderer.selectConfigurationProfiles(0, profileIndex -> {
                    sourceCalls.incrementAndGet();
                    return applied(profileIndex, "endless-" + profileIndex);
                });

        assertEquals(11, sourceCalls.get());
        assertEquals(12, attempts.size());
        assertBase(attempts.get(11));
    }

    @Test
    public void startTrySkipsProfilesAndOutOfRangeStartsWithBase() {
        List<Integer> requestedIndices = new ArrayList<>();
        List<MediaCodecDecoderRenderer.DecoderConfigurationProfile> attempts =
                MediaCodecDecoderRenderer.selectConfigurationProfiles(3, profileIndex -> {
                    requestedIndices.add(profileIndex);
                    return applied(profileIndex, "option-" + profileIndex);
                });

        assertEquals(Arrays.asList(3, 4, 5, 6, 7, 8, 9, 10), requestedIndices);
        assertEquals(9, attempts.size());
        assertEquals(3, attempts.get(0).profileIndex);
        assertBase(attempts.get(8));

        AtomicInteger outOfRangeCalls = new AtomicInteger();
        List<MediaCodecDecoderRenderer.DecoderConfigurationProfile> baseOnly =
                MediaCodecDecoderRenderer.selectConfigurationProfiles(11, profileIndex -> {
                    outOfRangeCalls.incrementAndGet();
                    return applied(profileIndex, "must-not-be-requested");
                });
        assertEquals(0, outOfRangeCalls.get());
        assertEquals(1, baseOnly.size());
        assertBase(baseOnly.get(0));
    }

    @Test
    public void negativeStartTryIsRejected() {
        try {
            MediaCodecDecoderRenderer.selectConfigurationProfiles(-1, profileIndex -> null);
            fail("Negative startTry must be rejected");
        }
        catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("startTry"));
        }
    }

    @Test
    public void runtimeRecoveryFromNonStandardUsesStandardThenBase() {
        List<MediaCodecDecoderRenderer.DecoderConfigurationProfile> attempts =
                MediaCodecDecoderRenderer.selectRuntimeRecoveryProfiles(
                        MediaCodecDecoderRenderer.ActiveLowLatencyProfileKind.NON_STANDARD,
                        profileIndex -> profileIndex == 0 ?
                                applied(profileIndex, "android-standard",
                                        MediaFormat.KEY_LOW_LATENCY, 1) : null);

        assertEquals(Arrays.asList("android-standard", "base"), names(attempts));
        assertEquals(MediaCodecDecoderRenderer.ActiveLowLatencyProfileKind.ANDROID_STANDARD,
                attempts.get(0).kind);
        assertEquals(Collections.singletonMap(MediaFormat.KEY_LOW_LATENCY, 1),
                attempts.get(0).integerOptions);
        assertBase(attempts.get(1));
    }

    @Test
    public void runtimeRecoveryFromStandardOrBaseIsBaseOnly() {
        for (MediaCodecDecoderRenderer.ActiveLowLatencyProfileKind activeKind : Arrays.asList(
                MediaCodecDecoderRenderer.ActiveLowLatencyProfileKind.ANDROID_STANDARD,
                MediaCodecDecoderRenderer.ActiveLowLatencyProfileKind.BASE)) {
            AtomicInteger sourceCalls = new AtomicInteger();
            List<MediaCodecDecoderRenderer.DecoderConfigurationProfile> attempts =
                    MediaCodecDecoderRenderer.selectRuntimeRecoveryProfiles(
                            activeKind,
                            profileIndex -> {
                                sourceCalls.incrementAndGet();
                                return applied(profileIndex, "unsafe-vendor");
                            });

            assertEquals(0, sourceCalls.get());
            assertEquals(1, attempts.size());
            assertBase(attempts.get(0));
        }
    }

    @Test
    public void runtimeRecoveryPlannerNeverIntroducesVendorOrPerformanceOptions() {
        LowLatencyProfilePlanner.Capabilities capabilities =
                new LowLatencyProfilePlanner.Capabilities(
                        LowLatencyProfilePlanner.DecoderFamily.MEDIATEK,
                        "c2.mtk.avc.decoder",
                        33,
                        true,
                        true,
                        true,
                        true,
                        Collections.singleton(LowLatencyProfilePlanner.MTK_ULTRA_LOW_LATENCY));
        List<LowLatencyProfilePlanner.Profile> recovery = LowLatencyProfilePlanner.plan(
                capabilities,
                true,
                LowLatencyProfilePlanner.PlanPurpose.RUNTIME_RECOVERY);

        List<MediaCodecDecoderRenderer.DecoderConfigurationProfile> attempts =
                MediaCodecDecoderRenderer.selectRuntimeRecoveryProfiles(
                        MediaCodecDecoderRenderer.ActiveLowLatencyProfileKind.NON_STANDARD,
                        profileIndex -> profileIndex < recovery.size() ?
                                new MediaCodecHelper.AppliedLowLatencyOptions(
                                        profileIndex,
                                        recovery.get(profileIndex).name,
                                        recovery.get(profileIndex).integerOptions) : null);

        assertEquals(Arrays.asList("android-standard", "base"), names(attempts));
        assertEquals(Collections.singletonMap(MediaFormat.KEY_LOW_LATENCY, 1),
                attempts.get(0).integerOptions);
    }

    @Test
    public void transientRestartAndResetKeepTheActiveProfile() {
        for (MediaCodecDecoderRenderer.RecoveryStage stage : Arrays.asList(
                MediaCodecDecoderRenderer.RecoveryStage.TRANSIENT,
                MediaCodecDecoderRenderer.RecoveryStage.RESTART,
                MediaCodecDecoderRenderer.RecoveryStage.RESET)) {
            assertEquals(MediaCodecDecoderRenderer.RecoveryAction.REUSE_ACTIVE_CONFIGURATION,
                    MediaCodecDecoderRenderer.recoveryActionFor(stage));
        }
        assertEquals(MediaCodecDecoderRenderer.RecoveryAction.RUNTIME_DOWNGRADE,
                MediaCodecDecoderRenderer.recoveryActionFor(
                        MediaCodecDecoderRenderer.RecoveryStage.FULL_RECREATION));
    }

    @Test
    public void failedCandidateDoesNotCommitStateAndSuccessCommitsAllMetadataTogether() {
        MediaFormat oldFormat = MediaFormat.createVideoFormat("video/avc", 1280, 720);
        MediaCodecDecoderRenderer.DecoderConfigurationProfile oldProfile =
                MediaCodecDecoderRenderer.DecoderConfigurationProfile.base();
        MediaCodecDecoderRenderer.ConfiguredDecoderState original =
                MediaCodecDecoderRenderer.commitConfiguredState(
                        null, oldFormat, oldProfile, true);

        MediaFormat newFormat = MediaFormat.createVideoFormat("video/avc", 1920, 1080);
        MediaCodecDecoderRenderer.DecoderConfigurationProfile newProfile =
                MediaCodecDecoderRenderer.DecoderConfigurationProfile.from(
                        applied(4, "qti-core-only", LowLatencyProfilePlanner.QTI_CORE, 1));

        assertSame(original, MediaCodecDecoderRenderer.commitConfiguredState(
                original, newFormat, newProfile, false));

        MediaCodecDecoderRenderer.ConfiguredDecoderState committed =
                MediaCodecDecoderRenderer.commitConfiguredState(
                        original, newFormat, newProfile, true);
        assertSame(newFormat, committed.format);
        assertEquals(4, committed.profileIndex);
        assertEquals("qti-core-only", committed.profileName);
        assertEquals(MediaCodecDecoderRenderer.ActiveLowLatencyProfileKind.NON_STANDARD,
                committed.kind);
    }

    @Test
    public void initialBaseFailureReturnsMinusFiveAndRecoveryBaseFailureRethrows() {
        MediaCodecDecoderRenderer.DecoderConfigurationProfile base =
                MediaCodecDecoderRenderer.DecoderConfigurationProfile.base();
        assertEquals(MediaCodecDecoderRenderer.ConfigurationFailureDisposition.RETURN_MINUS_FIVE,
                MediaCodecDecoderRenderer.configurationFailureDisposition(
                        MediaCodecDecoderRenderer.ConfigurationMode.INITIAL, base));
        assertEquals(MediaCodecDecoderRenderer.ConfigurationFailureDisposition.RETHROW,
                MediaCodecDecoderRenderer.configurationFailureDisposition(
                        MediaCodecDecoderRenderer.ConfigurationMode.RUNTIME_RECOVERY, base));

        MediaCodecDecoderRenderer.DecoderConfigurationProfile option =
                MediaCodecDecoderRenderer.DecoderConfigurationProfile.from(
                        applied(0, "android-standard", MediaFormat.KEY_LOW_LATENCY, 1));
        assertEquals(MediaCodecDecoderRenderer.ConfigurationFailureDisposition.TRY_NEXT,
                MediaCodecDecoderRenderer.configurationFailureDisposition(
                        MediaCodecDecoderRenderer.ConfigurationMode.INITIAL, option));
        assertEquals(MediaCodecDecoderRenderer.ConfigurationFailureDisposition.TRY_NEXT,
                MediaCodecDecoderRenderer.configurationFailureDisposition(
                        MediaCodecDecoderRenderer.ConfigurationMode.RUNTIME_RECOVERY, option));
    }

    @Test
    public void initializeDecoderBooleanSelectsTheEntryPointBaseFailurePolicy() {
        MediaCodecDecoderRenderer.DecoderConfigurationProfile base =
                MediaCodecDecoderRenderer.DecoderConfigurationProfile.base();

        MediaCodecDecoderRenderer.ConfigurationMode setupMode =
                MediaCodecDecoderRenderer.configurationModeForInitializeDecoder(false);
        MediaCodecDecoderRenderer.ConfigurationMode recreationMode =
                MediaCodecDecoderRenderer.configurationModeForInitializeDecoder(true);

        assertEquals(MediaCodecDecoderRenderer.ConfigurationMode.INITIAL, setupMode);
        assertEquals(MediaCodecDecoderRenderer.ConfigurationFailureDisposition.RETURN_MINUS_FIVE,
                MediaCodecDecoderRenderer.configurationFailureDisposition(setupMode, base));
        assertEquals(MediaCodecDecoderRenderer.ConfigurationMode.RUNTIME_RECOVERY, recreationMode);
        assertEquals(MediaCodecDecoderRenderer.ConfigurationFailureDisposition.RETHROW,
                MediaCodecDecoderRenderer.configurationFailureDisposition(recreationMode, base));
    }

    @Test
    public void finalFailureLogIdentifiesDecoderProfileCapabilityAndRequestedOptions() {
        String message = MediaCodecDecoderRenderer.finalConfigurationFailureMessage(
                "c2.qti.avc.decoder",
                MediaCodecDecoderRenderer.DecoderConfigurationProfile.base(),
                true);

        assertTrue(message.contains("decoder=c2.qti.avc.decoder"));
        assertTrue(message.contains("profileIndex=-1"));
        assertTrue(message.contains("profileName=base"));
        assertTrue(message.contains("requestedOptions={}"));
        assertTrue(message.contains("FEATURE_LowLatency=true"));
    }

    private static MediaCodecHelper.AppliedLowLatencyOptions applied(
            int profileIndex,
            String profileName,
            Object... keyValues) {
        LinkedHashMap<String, Integer> options = new LinkedHashMap<>();
        if (keyValues.length == 0) {
            options.put("test-option-" + profileIndex, 1);
        }
        else {
            for (int i = 0; i < keyValues.length; i += 2) {
                options.put((String) keyValues[i], (Integer) keyValues[i + 1]);
            }
        }
        return new MediaCodecHelper.AppliedLowLatencyOptions(
                profileIndex, profileName, options);
    }

    private static List<String> names(
            List<MediaCodecDecoderRenderer.DecoderConfigurationProfile> attempts) {
        List<String> names = new ArrayList<>();
        for (MediaCodecDecoderRenderer.DecoderConfigurationProfile attempt : attempts) {
            names.add(attempt.profileName);
        }
        return names;
    }

    private static void assertBase(
            MediaCodecDecoderRenderer.DecoderConfigurationProfile profile) {
        assertEquals(-1, profile.profileIndex);
        assertEquals("base", profile.profileName);
        assertTrue(profile.integerOptions.isEmpty());
        assertEquals(MediaCodecDecoderRenderer.ActiveLowLatencyProfileKind.BASE, profile.kind);
    }
}
