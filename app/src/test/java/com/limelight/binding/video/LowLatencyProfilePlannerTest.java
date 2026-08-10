package com.limelight.binding.video;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.media.MediaCodecInfo;
import android.media.MediaFormat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {33})
public class LowLatencyProfilePlannerTest {
    private static final String MTK_LOW_LATENCY_MODE =
            "vendor.mtk.vdec.low-latency.mode";
    private static final String MTK_DISABLE_IDLE = "vendor.mtk.vdec.disable-idle";
    private static final String MTK_VSYNC_ADJUST =
            "vendor.mtk.vdec.vsync.adjust.enable";
    private static final String MTK_ULTRA_LOW_LATENCY =
            "vendor.mtk.vdec.ultra-low-latency";
    private static final String MTK_PRELOAD_FRAME_COUNT =
            "vendor.mtk.vdec.preload.frame.count";
    private static final String MTK_INPUT_QUEUE_DEPTH =
            "vendor.mtk.vdec.input.max.queue.depth";
    private static final String MTK_OUTPUT_QUEUE_DEPTH =
            "vendor.mtk.vdec.output.max.queue.depth";
    private static final String MTK_FETCH_TIMEOUT =
            "vendor.mtk.vdec.buffer.fetch.timeout.ms";
    private static final String MTK_FETCH_TIMEOUT_VALUE = MTK_FETCH_TIMEOUT + ".value";
    private static final String MTK_GUARD_INTERVAL =
            "vendor.mtk.vdec.bq.guard.interval.time";
    private static final String MTK_GUARD_INTERVAL_VALUE = MTK_GUARD_INTERVAL + ".value";
    private static final String MTK_CPU_BOOST = "vendor.mtk.vdec.cpu.boost.mode";
    private static final String MTK_CPU_BOOST_VALUE = MTK_CPU_BOOST + ".value";
    private static final String MTK_DVFS_MODE = "vendor.mtk.vdec.dvfs.mode";
    private static final String MTK_DVFS_LEVEL = "vendor.mtk.vdec.dvfs.level";

    private static final List<String> FORBIDDEN_MTK_KEYS = Arrays.asList(
            "media.low-latency.enable",
            "vendor.low-latency.enable",
            "vendor.mtk.vdec.nvop.skip",
            "vendor.mtk.vdec.skip.mode",
            "vendor.mtk.vdec.drop.nonref.frame",
            "vendor.mtk.vdec.frame-drop.policy");

    private static final Set<String> S25_VENDOR_PARAMETERS = parameters(
            LowLatencyProfilePlanner.QTI_PICTURE_ORDER,
            LowLatencyProfilePlanner.QTI_OUTPUT_FENCE_ENABLE,
            LowLatencyProfilePlanner.QTI_OUTPUT_FENCE_TYPE);

    @Test
    public void profileOptionsAreOrderedAndImmutable() {
        LinkedHashMap<String, Integer> options = new LinkedHashMap<>();
        options.put("low-latency", 1);
        options.put("vendor.test.option", 2);

        LowLatencyProfilePlanner.Profile profile =
                new LowLatencyProfilePlanner.Profile("test-profile", options);
        options.put("added-after-construction", 3);

        assertEquals(
                Arrays.asList("low-latency", "vendor.test.option"),
                new ArrayList<>(profile.integerOptions.keySet()));

        try {
            profile.integerOptions.put("mutation", 4);
            fail("Profile options must be immutable");
        }
        catch (UnsupportedOperationException expected) {
            // Expected.
        }
    }

    @Test
    public void duplicateOrderedOptionsAreRemoved() {
        LowLatencyProfilePlanner.Profile first = profile(
                "first", "low-latency", 1, "vendor.test.option", 2);
        LowLatencyProfilePlanner.Profile duplicate = profile(
                "duplicate", "low-latency", 1, "vendor.test.option", 2);
        LowLatencyProfilePlanner.Profile reverseOrder = profile(
                "reverse-order", "vendor.test.option", 2, "low-latency", 1);

        List<LowLatencyProfilePlanner.Profile> profiles =
                LowLatencyProfilePlanner.deduplicateProfiles(
                        Arrays.asList(first, duplicate, reverseOrder));

        assertEquals(2, profiles.size());
        assertEquals("first", profiles.get(0).name);
        assertEquals("reverse-order", profiles.get(1).name);
    }

    @Test
    public void standardOffReturnsAndroidStandardOptionProfileOnly() {
        List<LowLatencyProfilePlanner.Profile> profiles = LowLatencyProfilePlanner.plan(
                capabilities("vendor.decoder", 36, true, false, true, Collections.emptySet()),
                false,
                LowLatencyProfilePlanner.PlanPurpose.INITIAL_CONFIGURATION);

        assertEquals(1, profiles.size());
        assertEquals("android-standard", profiles.get(0).name);
        assertEquals(singleOption(MediaFormat.KEY_LOW_LATENCY, 1), profiles.get(0).integerOptions);
    }

    @Test
    public void runtimeRecoveryReturnsOnlyStandardWhenSupported() {
        List<LowLatencyProfilePlanner.Profile> profiles = LowLatencyProfilePlanner.plan(
                capabilities("c2.qti.avc.decoder", 36, true, true, true, S25_VENDOR_PARAMETERS),
                true,
                LowLatencyProfilePlanner.PlanPurpose.RUNTIME_RECOVERY);

        assertEquals(1, profiles.size());
        assertEquals("android-standard", profiles.get(0).name);
        assertEquals(singleOption(MediaFormat.KEY_LOW_LATENCY, 1), profiles.get(0).integerOptions);
    }

    @Test
    public void capabilitiesNormalizeAndDefensivelyCopyVendorParameters() {
        Set<String> parameters = parameters("Vendor.QTI-Ext-Dec-Low-Latency.Enable");
        LowLatencyProfilePlanner.Capabilities capabilities = capabilities(
                "c2.qti.avc.decoder", 36, true, true, true, parameters);
        parameters.add("vendor.added.after.construction");

        assertEquals(parameters(LowLatencyProfilePlanner.QTI_CORE),
                capabilities.supportedVendorParameters);
        try {
            capabilities.supportedVendorParameters.add("mutation");
            fail("Capabilities must be immutable");
        }
        catch (UnsupportedOperationException expected) {
            // Expected.
        }
    }

    @Test
    public void qtiS25UsesOnlyAdvertisedOutputFenceFamily() {
        List<LowLatencyProfilePlanner.Profile> profiles = LowLatencyProfilePlanner.plan(
                capabilities("c2.qti.hevc.decoder.low_latency", 36, true, true, true,
                        S25_VENDOR_PARAMETERS),
                true,
                LowLatencyProfilePlanner.PlanPurpose.INITIAL_CONFIGURATION);

        assertEquals(Arrays.asList(
                        "qti-experimental-full",
                        "qti-experimental-no-scheduler",
                        "qti-no-fence",
                        "android-standard"),
                profileNames(profiles));

        Map<String, Integer> full = profiles.get(0).integerOptions;
        assertFalse(full.containsKey(LowLatencyProfilePlanner.VDEC_LOW_LATENCY));
        assertFalse(full.containsKey(LowLatencyProfilePlanner.QTI_CORE));
        assertFalse(full.containsKey(LowLatencyProfilePlanner.QTI_SOFTWARE_FENCE));
        assertEquals(1, full.get(LowLatencyProfilePlanner.QTI_OUTPUT_FENCE_ENABLE).intValue());
        assertEquals(1, full.get(LowLatencyProfilePlanner.QTI_OUTPUT_FENCE_TYPE).intValue());
        assertEquals(Arrays.asList(
                        MediaFormat.KEY_LOW_LATENCY,
                        LowLatencyProfilePlanner.QTI_PICTURE_ORDER,
                        LowLatencyProfilePlanner.QTI_OUTPUT_FENCE_ENABLE,
                        LowLatencyProfilePlanner.QTI_OUTPUT_FENCE_TYPE,
                        MediaFormat.KEY_OPERATING_RATE),
                new ArrayList<>(full.keySet()));
    }

    @Test
    public void qtiAdvertisedCoreIncludesTrueCoreOnlyFallback() {
        List<LowLatencyProfilePlanner.Profile> profiles = LowLatencyProfilePlanner.plan(
                capabilities("c2.qti.avc.decoder", 36, true, true, true,
                        parameters(LowLatencyProfilePlanner.QTI_CORE,
                                LowLatencyProfilePlanner.QTI_PICTURE_ORDER)),
                false,
                LowLatencyProfilePlanner.PlanPurpose.INITIAL_CONFIGURATION);

        assertEquals(Arrays.asList("android-standard", "qti-core-picture", "qti-core-only"),
                profileNames(profiles));
        assertEquals(singleOption(LowLatencyProfilePlanner.QTI_CORE, 1),
                profiles.get(2).integerOptions);
    }

    @Test
    public void qtiNeverMixesSoftwareAndOutputFenceFamilies() {
        Set<String> advertised = parameters(
                LowLatencyProfilePlanner.QTI_CORE,
                LowLatencyProfilePlanner.QTI_PICTURE_ORDER,
                LowLatencyProfilePlanner.QTI_OUTPUT_FENCE_ENABLE,
                LowLatencyProfilePlanner.QTI_OUTPUT_FENCE_TYPE,
                LowLatencyProfilePlanner.QTI_SOFTWARE_FENCE);

        List<LowLatencyProfilePlanner.Profile> profiles = LowLatencyProfilePlanner.plan(
                capabilities("c2.qti.avc.decoder", 36, true, true, true, advertised),
                true,
                LowLatencyProfilePlanner.PlanPurpose.INITIAL_CONFIGURATION);

        assertEquals("qti-experimental-full", profiles.get(0).name);
        assertEquals("qti-experimental-alt-fence", profiles.get(1).name);
        assertTrue(profiles.get(0).integerOptions.containsKey(
                LowLatencyProfilePlanner.QTI_OUTPUT_FENCE_ENABLE));
        assertFalse(profiles.get(0).integerOptions.containsKey(
                LowLatencyProfilePlanner.QTI_SOFTWARE_FENCE));
        assertTrue(profiles.get(1).integerOptions.containsKey(
                LowLatencyProfilePlanner.QTI_SOFTWARE_FENCE));
        assertFalse(profiles.get(1).integerOptions.containsKey(
                LowLatencyProfilePlanner.QTI_OUTPUT_FENCE_ENABLE));

        for (LowLatencyProfilePlanner.Profile profile : profiles) {
            assertFalse(profile.integerOptions.containsKey(
                            LowLatencyProfilePlanner.QTI_SOFTWARE_FENCE) &&
                    profile.integerOptions.containsKey(
                            LowLatencyProfilePlanner.QTI_OUTPUT_FENCE_ENABLE));
        }
    }

    @Test
    public void qtiPartialOutputFencePairIsNotUsed() {
        for (String partialKey : Arrays.asList(
                LowLatencyProfilePlanner.QTI_OUTPUT_FENCE_ENABLE,
                LowLatencyProfilePlanner.QTI_OUTPUT_FENCE_TYPE)) {
            List<LowLatencyProfilePlanner.Profile> profiles = LowLatencyProfilePlanner.plan(
                    capabilities("c2.qti.avc.decoder", 36, true, true, true,
                            parameters(partialKey)),
                    true,
                    LowLatencyProfilePlanner.PlanPurpose.INITIAL_CONFIGURATION);

            for (LowLatencyProfilePlanner.Profile profile : profiles) {
                assertFalse(profile.integerOptions.containsKey(
                        LowLatencyProfilePlanner.QTI_OUTPUT_FENCE_ENABLE));
                assertFalse(profile.integerOptions.containsKey(
                        LowLatencyProfilePlanner.QTI_OUTPUT_FENCE_TYPE));
            }
        }
    }

    @Test
    public void qtiPartialOutputFenceFallsBackToAdvertisedSoftwareFence() {
        List<LowLatencyProfilePlanner.Profile> profiles = LowLatencyProfilePlanner.plan(
                capabilities("c2.qti.avc.decoder", 36, true, true, true,
                        parameters(LowLatencyProfilePlanner.QTI_OUTPUT_FENCE_ENABLE,
                                LowLatencyProfilePlanner.QTI_SOFTWARE_FENCE)),
                true,
                LowLatencyProfilePlanner.PlanPurpose.INITIAL_CONFIGURATION);

        assertTrue(profiles.get(0).integerOptions.containsKey(
                LowLatencyProfilePlanner.QTI_SOFTWARE_FENCE));
        assertFalse(profiles.get(0).integerOptions.containsKey(
                LowLatencyProfilePlanner.QTI_OUTPUT_FENCE_ENABLE));
        assertFalse(profiles.get(0).integerOptions.containsKey(
                LowLatencyProfilePlanner.QTI_OUTPUT_FENCE_TYPE));
    }

    @Test
    public void qtiApi31EmptyOrFailedEnumerationUsesNoVendorKeys() {
        for (boolean enumerationAvailable : Arrays.asList(true, false)) {
            List<LowLatencyProfilePlanner.Profile> profiles = LowLatencyProfilePlanner.plan(
                    capabilities("c2.qti.avc.decoder", 31, true, true,
                            enumerationAvailable, Collections.emptySet()),
                    true,
                    LowLatencyProfilePlanner.PlanPurpose.INITIAL_CONFIGURATION);

            for (LowLatencyProfilePlanner.Profile profile : profiles) {
                for (String key : profile.integerOptions.keySet()) {
                    assertFalse(key.startsWith("vendor.qti-"));
                }
            }
        }
    }

    @Test
    public void qtiLegacyApi26To30UsesCoreAndPictureButNeverFences() {
        for (int sdkInt : Arrays.asList(26, 30)) {
            List<LowLatencyProfilePlanner.Profile> profiles = LowLatencyProfilePlanner.plan(
                    capabilities("c2.qti.avc.decoder", sdkInt, false, false,
                            false, Collections.emptySet()),
                    false,
                    LowLatencyProfilePlanner.PlanPurpose.INITIAL_CONFIGURATION);

            assertEquals(Arrays.asList("qti-core-picture", "qti-core-only"),
                    profileNames(profiles));
            assertTrue(profiles.get(0).integerOptions.containsKey(
                    LowLatencyProfilePlanner.QTI_CORE));
            assertTrue(profiles.get(0).integerOptions.containsKey(
                    LowLatencyProfilePlanner.QTI_PICTURE_ORDER));
            assertNoFenceKeys(profiles);
        }
    }

    @Test
    public void qtiPictureOrderMatchesOmxAndCodec2Contracts() {
        assertEquals(0, pictureOrderFor("OMX.qcom.video.decoder.avc"));
        assertEquals(1, pictureOrderFor("c2.qti.avc.decoder"));
        assertEquals(1, pictureOrderFor("c2.qcom.avc.decoder"));
    }

    @Test
    public void qtiNeverUsesVdecLowLatency() {
        List<LowLatencyProfilePlanner.Capabilities> fixtures = Arrays.asList(
                capabilities("OMX.qcom.video.decoder.avc", 26, false, true,
                        false, Collections.emptySet()),
                capabilities("c2.qti.avc.decoder", 36, true, true, true,
                        parameters(LowLatencyProfilePlanner.QTI_CORE,
                                LowLatencyProfilePlanner.QTI_PICTURE_ORDER,
                                LowLatencyProfilePlanner.QTI_SOFTWARE_FENCE)));

        for (LowLatencyProfilePlanner.Capabilities fixture : fixtures) {
            for (boolean ultraLowLatency : Arrays.asList(false, true)) {
                List<LowLatencyProfilePlanner.Profile> profiles = LowLatencyProfilePlanner.plan(
                        fixture,
                        ultraLowLatency,
                        LowLatencyProfilePlanner.PlanPurpose.INITIAL_CONFIGURATION);
                for (LowLatencyProfilePlanner.Profile profile : profiles) {
                    assertFalse(profile.integerOptions.containsKey(
                            LowLatencyProfilePlanner.VDEC_LOW_LATENCY));
                }
            }
        }
    }

    @Test
    public void qtiSchedulerUsesOperatingRateOrPriorityButNeverBoth() {
        List<LowLatencyProfilePlanner.Profile> operatingRateProfiles =
                LowLatencyProfilePlanner.plan(
                        capabilities("c2.qti.avc.decoder", 36, true, true, true,
                                S25_VENDOR_PARAMETERS),
                        true,
                        LowLatencyProfilePlanner.PlanPurpose.INITIAL_CONFIGURATION);
        Map<String, Integer> operatingRate = operatingRateProfiles.get(0).integerOptions;
        assertEquals((int) Short.MAX_VALUE,
                operatingRate.get(MediaFormat.KEY_OPERATING_RATE).intValue());
        assertFalse(operatingRate.containsKey(MediaFormat.KEY_PRIORITY));

        List<LowLatencyProfilePlanner.Profile> priorityProfiles =
                LowLatencyProfilePlanner.plan(
                        capabilities("c2.qti.avc.decoder", 36, true, false, true,
                                S25_VENDOR_PARAMETERS),
                        true,
                        LowLatencyProfilePlanner.PlanPurpose.INITIAL_CONFIGURATION);
        Map<String, Integer> priority = priorityProfiles.get(0).integerOptions;
        assertEquals(0, priority.get(MediaFormat.KEY_PRIORITY).intValue());
        assertFalse(priority.containsKey(MediaFormat.KEY_OPERATING_RATE));
    }

    @Test
    public void plannerNeverReturnsEmptyBaseProfile() {
        List<LowLatencyProfilePlanner.Profile> profiles = LowLatencyProfilePlanner.plan(
                capabilities("c2.qti.avc.decoder", 36, false, false,
                        true, Collections.emptySet()),
                false,
                LowLatencyProfilePlanner.PlanPurpose.INITIAL_CONFIGURATION);

        assertTrue(profiles.isEmpty());
    }

    @Test
    public void decoderFamilyClassificationIsCaseInsensitiveAndIndependentOfInitialization() {
        assertEquals(LowLatencyProfilePlanner.DecoderFamily.MEDIATEK,
                LowLatencyProfilePlanner.classifyDecoderFamily("C2.MTK.AVC.Decoder"));
        assertEquals(LowLatencyProfilePlanner.DecoderFamily.MEDIATEK,
                LowLatencyProfilePlanner.classifyDecoderFamily("omx.mTk.video.decoder.hevc"));
        assertEquals(LowLatencyProfilePlanner.DecoderFamily.QUALCOMM,
                LowLatencyProfilePlanner.classifyDecoderFamily("C2.QTI.AVC.Decoder"));
        assertEquals(LowLatencyProfilePlanner.DecoderFamily.QUALCOMM,
                LowLatencyProfilePlanner.classifyDecoderFamily("omx.QCOM.video.decoder.avc"));
        assertEquals(LowLatencyProfilePlanner.DecoderFamily.QUALCOMM,
                LowLatencyProfilePlanner.classifyDecoderFamily("C2.QCOM.HEVC.Decoder"));
        assertEquals(LowLatencyProfilePlanner.DecoderFamily.QUALCOMM,
                LowLatencyProfilePlanner.classifyDecoderFamily("OMX.QTI.video.decoder.avc"));
        assertEquals(LowLatencyProfilePlanner.DecoderFamily.OTHER,
                LowLatencyProfilePlanner.classifyDecoderFamily("c2.exynos.avc.decoder"));
    }

    @Test
    public void legacyVdecEligibilityIsScopedAndNeverIncludesQualcomm() {
        assertTrue(LowLatencyProfilePlanner.isLegacyVdecLowLatencyAllowed(
                "c2.mtk.avc.decoder", "Samsung", 33));
        assertTrue(LowLatencyProfilePlanner.isLegacyVdecLowLatencyAllowed(
                "OMX.AMLOGIC.video.decoder.avc", "Google", 30));
        assertTrue(LowLatencyProfilePlanner.isLegacyVdecLowLatencyAllowed(
                "c2.exynos.avc.decoder", "aMaZoN", 33));

        assertFalse(LowLatencyProfilePlanner.isLegacyVdecLowLatencyAllowed(
                "c2.qti.avc.decoder", "Amazon", 33));
        assertFalse(LowLatencyProfilePlanner.isLegacyVdecLowLatencyAllowed(
                "OMX.QTI.video.decoder.avc", "Amazon", 33));
        assertFalse(LowLatencyProfilePlanner.isLegacyVdecLowLatencyAllowed(
                "c2.mtk.avc.decoder", "Xiaomi", 23));
        assertTrue(LowLatencyProfilePlanner.isLegacyVdecLowLatencyAllowed(
                "c2.mtk.avc.decoder", "Xiaomi", 24));
        assertFalse(LowLatencyProfilePlanner.isLegacyVdecLowLatencyAllowed(
                "c2.exynos.avc.decoder", "Samsung", 33));
    }

    @Test
    public void mediatekUllOffUsesOnlyStandardThenLegacy() {
        List<LowLatencyProfilePlanner.Profile> profiles = LowLatencyProfilePlanner.plan(
                capabilities(LowLatencyProfilePlanner.DecoderFamily.MEDIATEK,
                        "c2.mtk.avc.decoder", 33, true, true, true, true,
                        parameters(MTK_LOW_LATENCY_MODE, MTK_ULTRA_LOW_LATENCY)),
                false,
                LowLatencyProfilePlanner.PlanPurpose.INITIAL_CONFIGURATION);

        assertEquals(Arrays.asList("android-standard", "mtk-legacy"), profileNames(profiles));
        assertEquals(options(MediaFormat.KEY_LOW_LATENCY, 1),
                profiles.get(0).integerOptions);
        assertEquals(options(LowLatencyProfilePlanner.VDEC_LOW_LATENCY, 1),
                profiles.get(1).integerOptions);
        assertNoMtkVendorOrSchedulerOptions(profiles);
    }

    @Test
    public void mediatekFullAdvertisedPlanHasExactRiskOrderAndKeyMaps() {
        Set<String> advertised = parameters(
                MTK_LOW_LATENCY_MODE,
                MTK_DISABLE_IDLE,
                MTK_VSYNC_ADJUST,
                MTK_ULTRA_LOW_LATENCY,
                MTK_PRELOAD_FRAME_COUNT,
                MTK_INPUT_QUEUE_DEPTH,
                MTK_OUTPUT_QUEUE_DEPTH,
                MTK_FETCH_TIMEOUT,
                MTK_FETCH_TIMEOUT_VALUE,
                MTK_GUARD_INTERVAL,
                MTK_GUARD_INTERVAL_VALUE,
                MTK_CPU_BOOST,
                MTK_CPU_BOOST_VALUE,
                MTK_DVFS_MODE,
                MTK_DVFS_LEVEL);

        List<LowLatencyProfilePlanner.Profile> profiles = LowLatencyProfilePlanner.plan(
                capabilities(LowLatencyProfilePlanner.DecoderFamily.MEDIATEK,
                        "c2.mtk.hevc.decoder", 33, true, true, true, true, advertised),
                true,
                LowLatencyProfilePlanner.PlanPurpose.INITIAL_CONFIGURATION);

        assertEquals(Arrays.asList(
                        "mtk-experimental-full",
                        "mtk-standard-legacy-performance",
                        "mtk-standard-performance",
                        "android-standard",
                        "mtk-legacy-performance",
                        "mtk-legacy",
                        "performance-only"),
                profileNames(profiles));
        assertEquals(options(
                        MediaFormat.KEY_LOW_LATENCY, 1,
                        LowLatencyProfilePlanner.VDEC_LOW_LATENCY, 1,
                        MTK_LOW_LATENCY_MODE, 1,
                        MTK_DISABLE_IDLE, 1,
                        MTK_VSYNC_ADJUST, 0,
                        MTK_ULTRA_LOW_LATENCY, 1,
                        MTK_PRELOAD_FRAME_COUNT, 0,
                        MTK_INPUT_QUEUE_DEPTH, 2,
                        MTK_OUTPUT_QUEUE_DEPTH, 2,
                        MTK_FETCH_TIMEOUT_VALUE, 2,
                        MTK_GUARD_INTERVAL_VALUE, 2,
                        MTK_CPU_BOOST_VALUE, 1,
                        MTK_DVFS_MODE, 1,
                        MTK_DVFS_LEVEL, 1,
                        MediaFormat.KEY_OPERATING_RATE, (int) Short.MAX_VALUE),
                profiles.get(0).integerOptions);
        assertEquals(options(
                        MediaFormat.KEY_LOW_LATENCY, 1,
                        LowLatencyProfilePlanner.VDEC_LOW_LATENCY, 1,
                        MediaFormat.KEY_OPERATING_RATE, (int) Short.MAX_VALUE),
                profiles.get(1).integerOptions);
        assertEquals(options(
                        MediaFormat.KEY_LOW_LATENCY, 1,
                        MediaFormat.KEY_OPERATING_RATE, (int) Short.MAX_VALUE),
                profiles.get(2).integerOptions);
        assertEquals(options(MediaFormat.KEY_LOW_LATENCY, 1),
                profiles.get(3).integerOptions);
        assertEquals(options(
                        LowLatencyProfilePlanner.VDEC_LOW_LATENCY, 1,
                        MediaFormat.KEY_OPERATING_RATE, (int) Short.MAX_VALUE),
                profiles.get(4).integerOptions);
        assertEquals(options(LowLatencyProfilePlanner.VDEC_LOW_LATENCY, 1),
                profiles.get(5).integerOptions);
        assertEquals(options(MediaFormat.KEY_OPERATING_RATE, (int) Short.MAX_VALUE),
                profiles.get(6).integerOptions);
        assertMtkProfilesAreSafeUniqueAndBounded(profiles);
    }

    @Test
    public void mediatekAliasesSelectAvailableKeyAndPreferValueAlias() {
        List<Set<String>> advertisedFixtures = Arrays.asList(
                parameters(MTK_FETCH_TIMEOUT, MTK_GUARD_INTERVAL, MTK_CPU_BOOST),
                parameters(MTK_FETCH_TIMEOUT_VALUE, MTK_GUARD_INTERVAL_VALUE,
                        MTK_CPU_BOOST_VALUE),
                parameters(MTK_FETCH_TIMEOUT, MTK_FETCH_TIMEOUT_VALUE,
                        MTK_GUARD_INTERVAL, MTK_GUARD_INTERVAL_VALUE,
                        MTK_CPU_BOOST, MTK_CPU_BOOST_VALUE));
        List<List<String>> expectedAliases = Arrays.asList(
                Arrays.asList(MTK_FETCH_TIMEOUT, MTK_GUARD_INTERVAL, MTK_CPU_BOOST),
                Arrays.asList(MTK_FETCH_TIMEOUT_VALUE, MTK_GUARD_INTERVAL_VALUE,
                        MTK_CPU_BOOST_VALUE),
                Arrays.asList(MTK_FETCH_TIMEOUT_VALUE, MTK_GUARD_INTERVAL_VALUE,
                        MTK_CPU_BOOST_VALUE));

        for (int i = 0; i < advertisedFixtures.size(); i++) {
            List<LowLatencyProfilePlanner.Profile> profiles = LowLatencyProfilePlanner.plan(
                    capabilities(LowLatencyProfilePlanner.DecoderFamily.MEDIATEK,
                            "OMX.MTK.video.decoder.avc", 31, true, true, true, true,
                            advertisedFixtures.get(i)),
                    true,
                    LowLatencyProfilePlanner.PlanPurpose.INITIAL_CONFIGURATION);
            Map<String, Integer> full = profiles.get(0).integerOptions;

            assertEquals("mtk-experimental-full", profiles.get(0).name);
            assertEquals(expectedAliases.get(i), advertisedMtkAliases(full));
            assertEquals(2, full.get(expectedAliases.get(i).get(0)).intValue());
            assertEquals(2, full.get(expectedAliases.get(i).get(1)).intValue());
            assertEquals(1, full.get(expectedAliases.get(i).get(2)).intValue());
        }
    }

    @Test
    public void mediatekApi31EmptyOrFailedEnumerationNeverSpeculatesVendorKeys() {
        for (boolean enumerationAvailable : Arrays.asList(true, false)) {
            List<LowLatencyProfilePlanner.Profile> profiles = LowLatencyProfilePlanner.plan(
                    capabilities(LowLatencyProfilePlanner.DecoderFamily.MEDIATEK,
                            "c2.mtk.avc.decoder", 31, true, false, true,
                            enumerationAvailable, Collections.emptySet()),
                    true,
                    LowLatencyProfilePlanner.PlanPurpose.INITIAL_CONFIGURATION);

            assertEquals(Arrays.asList(
                            "mtk-standard-legacy-performance",
                            "mtk-standard-performance",
                            "android-standard",
                            "mtk-legacy-performance",
                            "mtk-legacy",
                            "performance-only"),
                    profileNames(profiles));
            assertNoMtkVendorOptions(profiles);
            assertMtkProfilesAreSafeUniqueAndBounded(profiles);
        }
    }

    @Test
    public void mediatekApi26And30UseOnlyStandardLegacyAndScheduler() {
        for (int sdkInt : Arrays.asList(26, 30)) {
            List<LowLatencyProfilePlanner.Profile> profiles = LowLatencyProfilePlanner.plan(
                    capabilities(LowLatencyProfilePlanner.DecoderFamily.MEDIATEK,
                            "OMX.MTK.video.decoder.avc", sdkInt, true, true, true, true,
                            parameters(MTK_LOW_LATENCY_MODE, MTK_ULTRA_LOW_LATENCY)),
                    true,
                    LowLatencyProfilePlanner.PlanPurpose.INITIAL_CONFIGURATION);

            assertEquals(Arrays.asList(
                            "mtk-standard-legacy-performance",
                            "mtk-standard-performance",
                            "android-standard",
                            "mtk-legacy-performance",
                            "mtk-legacy",
                            "performance-only"),
                    profileNames(profiles));
            assertNoMtkVendorOptions(profiles);
            assertMtkProfilesAreSafeUniqueAndBounded(profiles);
        }
    }

    @Test
    public void mediatekUllKeyIsOneWhenAdvertisedAndNeverWrittenAsZero() {
        List<LowLatencyProfilePlanner.Profile> profiles = LowLatencyProfilePlanner.plan(
                capabilities(LowLatencyProfilePlanner.DecoderFamily.MEDIATEK,
                        "c2.mtk.avc.decoder", 33, true, true, true, true,
                        parameters(MTK_ULTRA_LOW_LATENCY)),
                true,
                LowLatencyProfilePlanner.PlanPurpose.INITIAL_CONFIGURATION);

        assertEquals(1, profiles.get(0).integerOptions.get(MTK_ULTRA_LOW_LATENCY).intValue());
        for (LowLatencyProfilePlanner.Profile profile : profiles) {
            Integer ullValue = profile.integerOptions.get(MTK_ULTRA_LOW_LATENCY);
            assertTrue(ullValue == null || ullValue == 1);
        }
    }

    @Test
    public void mediatekProfilesNeverUseGenericOrSkipDropKeysAndSchedulerIsExclusive() {
        List<LowLatencyProfilePlanner.Profile> profiles = LowLatencyProfilePlanner.plan(
                capabilities(LowLatencyProfilePlanner.DecoderFamily.MEDIATEK,
                        "c2.mtk.avc.decoder", 33, true, false, true, true,
                        parameters(MTK_LOW_LATENCY_MODE, MTK_ULTRA_LOW_LATENCY)),
                true,
                LowLatencyProfilePlanner.PlanPurpose.INITIAL_CONFIGURATION);

        for (LowLatencyProfilePlanner.Profile profile : profiles) {
            for (String forbiddenKey : FORBIDDEN_MTK_KEYS) {
                assertFalse(profile.integerOptions.containsKey(forbiddenKey));
            }
            assertFalse(profile.integerOptions.containsKey(MediaFormat.KEY_OPERATING_RATE) &&
                    profile.integerOptions.containsKey(MediaFormat.KEY_PRIORITY));
        }
        assertEquals(0, profiles.get(0).integerOptions.get(MediaFormat.KEY_PRIORITY).intValue());
    }

    @Test
    public void mediatekProfilesStayUniqueNonEmptyAndBoundedAcrossCapabilityCombinations() {
        Set<String> allVendorOptions = parameters(
                MTK_LOW_LATENCY_MODE,
                MTK_DISABLE_IDLE,
                MTK_VSYNC_ADJUST,
                MTK_ULTRA_LOW_LATENCY,
                MTK_PRELOAD_FRAME_COUNT,
                MTK_INPUT_QUEUE_DEPTH,
                MTK_OUTPUT_QUEUE_DEPTH,
                MTK_FETCH_TIMEOUT,
                MTK_FETCH_TIMEOUT_VALUE,
                MTK_GUARD_INTERVAL,
                MTK_GUARD_INTERVAL_VALUE,
                MTK_CPU_BOOST,
                MTK_CPU_BOOST_VALUE,
                MTK_DVFS_MODE,
                MTK_DVFS_LEVEL);

        for (int sdkInt : Arrays.asList(22, 23, 26, 30, 31, 36)) {
            for (boolean standard : Arrays.asList(false, true)) {
                for (boolean maxOperatingRate : Arrays.asList(false, true)) {
                    for (boolean legacy : Arrays.asList(false, true)) {
                        for (boolean enumerationAvailable : Arrays.asList(false, true)) {
                            for (Set<String> advertised : Arrays.asList(
                                    Collections.<String>emptySet(), allVendorOptions)) {
                                for (boolean ultraLowLatency : Arrays.asList(false, true)) {
                                    List<LowLatencyProfilePlanner.Profile> profiles =
                                            LowLatencyProfilePlanner.plan(
                                                    capabilities(
                                                            LowLatencyProfilePlanner.DecoderFamily.MEDIATEK,
                                                            "c2.mtk.avc.decoder",
                                                            sdkInt,
                                                            standard,
                                                            maxOperatingRate,
                                                            legacy,
                                                            enumerationAvailable,
                                                            advertised),
                                                    ultraLowLatency,
                                                    LowLatencyProfilePlanner.PlanPurpose.INITIAL_CONFIGURATION);

                                    assertMtkProfilesAreSafeUniqueAndBounded(profiles);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    public void genericLegacyOtherUsesStandardThenVdecRegardlessOfUll() {
        for (boolean ultraLowLatency : Arrays.asList(false, true)) {
            List<LowLatencyProfilePlanner.Profile> profiles = LowLatencyProfilePlanner.plan(
                    capabilities(LowLatencyProfilePlanner.DecoderFamily.OTHER,
                            "OMX.amlogic.avc.decoder", 33, true, true, true, false,
                            Collections.emptySet()),
                    ultraLowLatency,
                    LowLatencyProfilePlanner.PlanPurpose.INITIAL_CONFIGURATION);

            assertEquals(Arrays.asList("android-standard", "legacy-vdec"),
                    profileNames(profiles));
            assertEquals(options(MediaFormat.KEY_LOW_LATENCY, 1),
                    profiles.get(0).integerOptions);
            assertEquals(options(LowLatencyProfilePlanner.VDEC_LOW_LATENCY, 1),
                    profiles.get(1).integerOptions);
        }

        List<LowLatencyProfilePlanner.Profile> legacyOnly = LowLatencyProfilePlanner.plan(
                capabilities(LowLatencyProfilePlanner.DecoderFamily.OTHER,
                        "c2.vendor.decoder", 30, false, true, true, false,
                        Collections.emptySet()),
                true,
                LowLatencyProfilePlanner.PlanPurpose.INITIAL_CONFIGURATION);
        assertEquals(Collections.singletonList("legacy-vdec"), profileNames(legacyOnly));
        assertEquals(options(LowLatencyProfilePlanner.VDEC_LOW_LATENCY, 1),
                legacyOnly.get(0).integerOptions);
    }

    @Test
    public void capabilityLogFiltersSortsAndFormatsSuccessfulEnumerationExactly() {
        AtomicInteger opens = new AtomicInteger();
        MediaCodecHelper.VendorParameterCapabilityStore store =
                new MediaCodecHelper.VendorParameterCapabilityStore(decoderName -> {
                    opens.incrementAndGet();
                    return session(Arrays.asList(
                            "vendor.unknown.parameter",
                            LowLatencyProfilePlanner.QTI_PICTURE_ORDER,
                            "Vendor.QTI-Ext-Dec-Low-Latency.Enable"));
                });
        MediaCodecInfo decoderInfo = mock(MediaCodecInfo.class);
        when(decoderInfo.getName()).thenReturn("c2.qti.avc.decoder");

        MediaCodecHelper.LowLatencyConfigurationPlan plan =
                MediaCodecHelper.createDecoderLowLatencyPlan(
                        decoderInfo,
                        "video/avc",
                        true,
                        LowLatencyProfilePlanner.PlanPurpose.INITIAL_CONFIGURATION,
                        store,
                        33,
                        true,
                        true,
                        "Samsung");

        assertEquals(1, opens.get());
        assertEquals(MediaCodecHelper.VendorEnumerationStatus.SUCCESS,
                plan.vendorEnumerationStatus);
        assertEquals(Arrays.asList(
                        LowLatencyProfilePlanner.QTI_CORE,
                        LowLatencyProfilePlanner.QTI_PICTURE_ORDER),
                plan.advertisedLowLatencyParameters);
        assertEquals(
                "CodecLLCapability decoder=c2.qti.avc.decoder mime=video/avc " +
                        "featureLowLatency=true vendorEnumeration=success " +
                        "advertisedLowLatencyParameters=[vendor.qti-ext-dec-low-latency.enable, " +
                        "vendor.qti-ext-dec-picture-order.enable] ullPreference=true",
                MediaCodecHelper.formatCapabilityLog(plan));

        assertNotNull(plan.getProfile(0));
        assertNotNull(plan.getProfile(1));
        assertEquals(1, opens.get());
    }

    @Test
    public void capabilityEnumerationIsTriStateAndFailureRetriesOnlyOnLaterPlan() {
        AtomicInteger opens = new AtomicInteger();
        MediaCodecHelper.VendorParameterCapabilityStore failingStore =
                new MediaCodecHelper.VendorParameterCapabilityStore(decoderName -> {
                    opens.incrementAndGet();
                    throw new Exception("enumeration failed");
                });
        MediaCodecInfo decoderInfo = mock(MediaCodecInfo.class);
        when(decoderInfo.getName()).thenReturn("c2.qti.avc.decoder");

        MediaCodecHelper.LowLatencyConfigurationPlan first = createPlan(
                decoderInfo, failingStore, 33);
        assertEquals(MediaCodecHelper.VendorEnumerationStatus.FAILURE,
                first.vendorEnumerationStatus);
        assertEquals(Collections.emptyList(), first.advertisedLowLatencyParameters);
        assertNotNull(first.getProfile(0));
        assertNull(first.getProfile(100));
        assertEquals(1, opens.get());

        MediaCodecHelper.LowLatencyConfigurationPlan second = createPlan(
                decoderInfo, failingStore, 33);
        assertEquals(MediaCodecHelper.VendorEnumerationStatus.FAILURE,
                second.vendorEnumerationStatus);
        assertEquals(2, opens.get());

        AtomicInteger unsupportedOpens = new AtomicInteger();
        MediaCodecHelper.VendorParameterCapabilityStore unsupportedStore =
                new MediaCodecHelper.VendorParameterCapabilityStore(decoderName -> {
                    unsupportedOpens.incrementAndGet();
                    throw new AssertionError("API 30 must not enumerate vendor parameters");
                });
        MediaCodecHelper.LowLatencyConfigurationPlan unsupported = createPlan(
                decoderInfo, unsupportedStore, 30);
        assertEquals(MediaCodecHelper.VendorEnumerationStatus.NOT_SUPPORTED,
                unsupported.vendorEnumerationStatus);
        assertEquals(0, unsupportedOpens.get());
        assertTrue(MediaCodecHelper.formatCapabilityLog(unsupported).contains(
                "vendorEnumeration=not_supported"));
    }

    @Test
    public void configurationPlanEnumeratesKnownParametersForOtherDecoderFamilies() {
        AtomicInteger opens = new AtomicInteger();
        MediaCodecHelper.VendorParameterCapabilityStore store =
                new MediaCodecHelper.VendorParameterCapabilityStore(decoderName -> {
                    opens.incrementAndGet();
                    return session(Arrays.asList(
                            "vendor.rtc-ext-dec-low-latency.enable",
                            "vendor.nvidia.disable-output-reorder",
                            "vendor.unrelated.option"));
                });
        MediaCodecInfo decoderInfo = mock(MediaCodecInfo.class);
        when(decoderInfo.getName()).thenReturn("c2.vendor.avc.decoder");

        MediaCodecHelper.LowLatencyConfigurationPlan plan =
                MediaCodecHelper.createDecoderLowLatencyPlan(
                        decoderInfo,
                        "video/avc",
                        true,
                        LowLatencyProfilePlanner.PlanPurpose.INITIAL_CONFIGURATION,
                        store,
                        33,
                        true,
                        true,
                        "Amazon");

        assertEquals(1, opens.get());
        assertEquals(MediaCodecHelper.VendorEnumerationStatus.SUCCESS,
                plan.vendorEnumerationStatus);
        assertEquals(Arrays.asList(
                        "vendor.nvidia.disable-output-reorder",
                        "vendor.rtc-ext-dec-low-latency.enable"),
                plan.advertisedLowLatencyParameters);
    }

    @Test
    public void diagnosticAttemptFallbackAndSelectionFormattingIsExactAndOrdered() {
        MediaCodecDecoderRenderer.DecoderConfigurationProfile profile =
                MediaCodecDecoderRenderer.DecoderConfigurationProfile.from(
                        new MediaCodecHelper.AppliedLowLatencyOptions(
                                3,
                                "qti-core-picture",
                                options(
                                        LowLatencyProfilePlanner.QTI_CORE, 1,
                                        LowLatencyProfilePlanner.QTI_PICTURE_ORDER, 1)));

        String attempt = MediaCodecDecoderRenderer.formatLowLatencyAttempt(2, profile);
        assertEquals(
                "CodecLLAttempt ordinal=2 profileIndex=3 profileName=qti-core-picture " +
                        "requestedOptions={vendor.qti-ext-dec-low-latency.enable=1, " +
                        "vendor.qti-ext-dec-picture-order.enable=1}",
                attempt);
        assertFalse(attempt.contains("supported"));
        assertEquals(
                "CodecLLFallback profileIndex=3 profileName=qti-core-picture " +
                        "reason=IllegalStateException",
                MediaCodecDecoderRenderer.formatLowLatencyFallback(
                        profile, new IllegalStateException("codec rejected options")));
        assertEquals(
                "CodecLLSelected profileIndex=3 profileName=qti-core-picture",
                MediaCodecDecoderRenderer.formatLowLatencySelected(profile));
    }

    private static MediaCodecHelper.LowLatencyConfigurationPlan createPlan(
            MediaCodecInfo decoderInfo,
            MediaCodecHelper.VendorParameterCapabilityStore store,
            int sdkInt) {
        return MediaCodecHelper.createDecoderLowLatencyPlan(
                decoderInfo,
                "video/avc",
                true,
                LowLatencyProfilePlanner.PlanPurpose.INITIAL_CONFIGURATION,
                store,
                sdkInt,
                true,
                true,
                "Samsung");
    }

    private static MediaCodecHelper.VendorParameterSession session(List<String> parameters) {
        return new MediaCodecHelper.VendorParameterSession() {
            @Override
            public List<String> getSupportedVendorParameters() {
                return parameters;
            }

            @Override
            public void release() {
            }
        };
    }

    private static LowLatencyProfilePlanner.Capabilities capabilities(
            String decoderName,
            int sdkInt,
            boolean androidLowLatencySupported,
            boolean maxOperatingRateSupported,
            boolean vendorParameterEnumerationAvailable,
            Set<String> supportedVendorParameters) {
        return new LowLatencyProfilePlanner.Capabilities(
                LowLatencyProfilePlanner.DecoderFamily.QUALCOMM,
                decoderName,
                sdkInt,
                androidLowLatencySupported,
                maxOperatingRateSupported,
                false,
                vendorParameterEnumerationAvailable,
                supportedVendorParameters);
    }

    private static LowLatencyProfilePlanner.Capabilities capabilities(
            LowLatencyProfilePlanner.DecoderFamily family,
            String decoderName,
            int sdkInt,
            boolean androidLowLatencySupported,
            boolean maxOperatingRateSupported,
            boolean legacyVdecLowLatencyAllowed,
            boolean vendorParameterEnumerationAvailable,
            Set<String> supportedVendorParameters) {
        return new LowLatencyProfilePlanner.Capabilities(
                family,
                decoderName,
                sdkInt,
                androidLowLatencySupported,
                maxOperatingRateSupported,
                legacyVdecLowLatencyAllowed,
                vendorParameterEnumerationAvailable,
                supportedVendorParameters);
    }

    private static void assertNoMtkVendorOrSchedulerOptions(
            List<LowLatencyProfilePlanner.Profile> profiles) {
        assertNoMtkVendorOptions(profiles);
        for (LowLatencyProfilePlanner.Profile profile : profiles) {
            assertFalse(profile.integerOptions.containsKey(MediaFormat.KEY_OPERATING_RATE));
            assertFalse(profile.integerOptions.containsKey(MediaFormat.KEY_PRIORITY));
        }
    }

    private static void assertNoMtkVendorOptions(
            List<LowLatencyProfilePlanner.Profile> profiles) {
        for (LowLatencyProfilePlanner.Profile profile : profiles) {
            for (String key : profile.integerOptions.keySet()) {
                assertFalse(key.startsWith("vendor.mtk."));
            }
        }
    }

    private static void assertMtkProfilesAreSafeUniqueAndBounded(
            List<LowLatencyProfilePlanner.Profile> profiles) {
        assertTrue(profiles.size() <= 11);
        assertEquals(profiles.size(), new LinkedHashSet<>(profiles).size());
        for (LowLatencyProfilePlanner.Profile profile : profiles) {
            assertFalse(profile.integerOptions.isEmpty());
            for (String forbiddenKey : FORBIDDEN_MTK_KEYS) {
                assertFalse(profile.integerOptions.containsKey(forbiddenKey));
            }
            assertFalse(profile.integerOptions.containsKey(MediaFormat.KEY_OPERATING_RATE) &&
                    profile.integerOptions.containsKey(MediaFormat.KEY_PRIORITY));
        }
    }

    private static List<String> advertisedMtkAliases(Map<String, Integer> options) {
        List<String> aliases = new ArrayList<>();
        for (String key : Arrays.asList(
                MTK_FETCH_TIMEOUT,
                MTK_FETCH_TIMEOUT_VALUE,
                MTK_GUARD_INTERVAL,
                MTK_GUARD_INTERVAL_VALUE,
                MTK_CPU_BOOST,
                MTK_CPU_BOOST_VALUE)) {
            if (options.containsKey(key)) {
                aliases.add(key);
            }
        }
        return aliases;
    }

    private static int pictureOrderFor(String decoderName) {
        List<LowLatencyProfilePlanner.Profile> profiles = LowLatencyProfilePlanner.plan(
                capabilities(decoderName, 30, false, false,
                        false, Collections.emptySet()),
                false,
                LowLatencyProfilePlanner.PlanPurpose.INITIAL_CONFIGURATION);
        return profiles.get(0).integerOptions.get(
                LowLatencyProfilePlanner.QTI_PICTURE_ORDER);
    }

    private static void assertNoFenceKeys(List<LowLatencyProfilePlanner.Profile> profiles) {
        for (LowLatencyProfilePlanner.Profile profile : profiles) {
            assertFalse(profile.integerOptions.containsKey(
                    LowLatencyProfilePlanner.QTI_SOFTWARE_FENCE));
            assertFalse(profile.integerOptions.containsKey(
                    LowLatencyProfilePlanner.QTI_OUTPUT_FENCE_ENABLE));
            assertFalse(profile.integerOptions.containsKey(
                    LowLatencyProfilePlanner.QTI_OUTPUT_FENCE_TYPE));
        }
    }

    private static List<String> profileNames(List<LowLatencyProfilePlanner.Profile> profiles) {
        List<String> names = new ArrayList<>();
        for (LowLatencyProfilePlanner.Profile profile : profiles) {
            names.add(profile.name);
        }
        return names;
    }

    private static Set<String> parameters(String... values) {
        return new LinkedHashSet<>(Arrays.asList(values));
    }

    private static Map<String, Integer> singleOption(String key, int value) {
        LinkedHashMap<String, Integer> options = new LinkedHashMap<>();
        options.put(key, value);
        return options;
    }

    private static Map<String, Integer> options(Object... keyValues) {
        LinkedHashMap<String, Integer> options = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            options.put((String) keyValues[i], (Integer) keyValues[i + 1]);
        }
        return options;
    }

    private static LowLatencyProfilePlanner.Profile profile(
            String name,
            String firstKey,
            int firstValue,
            String secondKey,
            int secondValue) {
        LinkedHashMap<String, Integer> options = new LinkedHashMap<>();
        options.put(firstKey, firstValue);
        options.put(secondKey, secondValue);
        return new LowLatencyProfilePlanner.Profile(name, options);
    }
}
