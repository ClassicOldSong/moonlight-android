package com.limelight.binding.video;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.media.MediaCodecInfo;
import android.media.MediaFormat;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {33})
public class MediaCodecLowLatencyCapabilityTest {
    private static final String MTK_LOW_LATENCY_MODE =
            "vendor.mtk.vdec.low-latency.mode";
    private static final String MTK_ULTRA_LOW_LATENCY =
            "vendor.mtk.vdec.ultra-low-latency";
    private static final String MTK_FETCH_TIMEOUT =
            "vendor.mtk.vdec.buffer.fetch.timeout.ms";
    private static final String MTK_FETCH_TIMEOUT_VALUE = MTK_FETCH_TIMEOUT + ".value";
    private static final String NVIDIA_MEDIA_LOW_LATENCY = "media.low-latency.enable";
    private static final String NVIDIA_VENDOR_LOW_LATENCY = "vendor.low-latency.enable";
    private static final String NVIDIA_DISABLE_OUTPUT_REORDER = "disable-output-reorder";
    private static final String NVIDIA_VENDOR_DISABLE_OUTPUT_REORDER =
            "vendor.nvidia.disable-output-reorder";
    private static final String KIRIN_LOW_LATENCY_REQUEST =
            "vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-req";
    private static final String KIRIN_LOW_LATENCY_READY =
            "vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-rdy";
    private static final String EXYNOS_LOW_LATENCY =
            "vendor.rtc-ext-dec-low-latency.enable";

    private static final List<String> LOW_LATENCY_OPTION_KEYS = Arrays.asList(
            MediaFormat.KEY_LOW_LATENCY,
            LowLatencyProfilePlanner.VDEC_LOW_LATENCY,
            LowLatencyProfilePlanner.QTI_CORE,
            LowLatencyProfilePlanner.QTI_PICTURE_ORDER,
            LowLatencyProfilePlanner.QTI_SOFTWARE_FENCE,
            LowLatencyProfilePlanner.QTI_OUTPUT_FENCE_ENABLE,
            LowLatencyProfilePlanner.QTI_OUTPUT_FENCE_TYPE,
            MTK_LOW_LATENCY_MODE,
            MTK_ULTRA_LOW_LATENCY,
            MTK_FETCH_TIMEOUT,
            MTK_FETCH_TIMEOUT_VALUE,
            MediaFormat.KEY_OPERATING_RATE,
            MediaFormat.KEY_PRIORITY);

    @Before
    public void initializeCodecHelper() {
        MediaCodecHelper.initialize(RuntimeEnvironment.getApplication(), "Adreno 740");
    }

    @Test
    public void appliedOptionsAreNamedIndexedOrderedImmutableAndDefensive() {
        LinkedHashMap<String, Integer> source = new LinkedHashMap<>();
        source.put("first", 1);
        source.put("second", 2);

        MediaCodecHelper.AppliedLowLatencyOptions applied =
                new MediaCodecHelper.AppliedLowLatencyOptions(3, "named-profile", source);
        source.put("third", 3);

        assertEquals(3, applied.profileIndex);
        assertEquals("named-profile", applied.profileName);
        assertEquals(Arrays.asList("first", "second"),
                Arrays.asList(applied.integerOptions.keySet().toArray(new String[0])));
        assertEquals(options("first", 1, "second", 2), applied.integerOptions);
        try {
            applied.integerOptions.put("mutation", 4);
            fail("Applied options must be immutable");
        }
        catch (UnsupportedOperationException expected) {
            // Expected.
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void appliedOptionsRejectEmptyMaps() {
        new MediaCodecHelper.AppliedLowLatencyOptions(
                0, "invalid-empty", Collections.emptyMap());
    }

    @Test
    public void advertisedParametersAreNormalizedImmutableCachedAndReleased() {
        AtomicInteger openCount = new AtomicInteger();
        AtomicInteger releaseCount = new AtomicInteger();
        MediaCodecHelper.VendorParameterCapabilityStore store =
                new MediaCodecHelper.VendorParameterCapabilityStore(decoderName -> {
                    openCount.incrementAndGet();
                    return session(Arrays.asList(
                            "Vendor.QTI-Ext-Output-Fence.Enable",
                            "vendor.qti-ext-dec-picture-order.enable"), releaseCount);
                });

        MediaCodecHelper.VendorParameterSnapshot first =
                store.getSnapshot("C2.QTI.AVC.Decoder", 31);
        MediaCodecHelper.VendorParameterSnapshot cached =
                store.getSnapshot("c2.qti.avc.decoder", 36);

        assertTrue(first.enumerationAvailable);
        assertEquals(Arrays.asList(
                        "vendor.qti-ext-dec-picture-order.enable",
                        "vendor.qti-ext-output-fence.enable"),
                Arrays.asList(first.supportedParameters.toArray(new String[0])));
        assertEquals(first.supportedParameters, cached.supportedParameters);
        assertEquals(1, openCount.get());
        assertEquals(1, releaseCount.get());

        try {
            first.supportedParameters.add("mutation");
            fail("Vendor parameter snapshot must be immutable");
        }
        catch (UnsupportedOperationException expected) {
            // Expected.
        }
    }

    @Test
    public void successfulEmptyEnumerationIsAvailableAndCached() {
        AtomicInteger openCount = new AtomicInteger();
        AtomicInteger releaseCount = new AtomicInteger();
        MediaCodecHelper.VendorParameterCapabilityStore store =
                new MediaCodecHelper.VendorParameterCapabilityStore(decoderName -> {
                    openCount.incrementAndGet();
                    return session(Collections.emptyList(), releaseCount);
                });

        MediaCodecHelper.VendorParameterSnapshot first =
                store.getSnapshot("c2.qti.hevc.decoder", 31);
        MediaCodecHelper.VendorParameterSnapshot second =
                store.getSnapshot("C2.QTI.HEVC.DECODER", 31);

        assertTrue(first.enumerationAvailable);
        assertTrue(first.supportedParameters.isEmpty());
        assertTrue(second.enumerationAvailable);
        assertEquals(1, openCount.get());
        assertEquals(1, releaseCount.get());
    }

    @Test
    public void failedEnumerationIsUnavailableReleasedAndNotCached() {
        AtomicInteger openCount = new AtomicInteger();
        AtomicInteger releaseCount = new AtomicInteger();
        MediaCodecHelper.VendorParameterCapabilityStore store =
                new MediaCodecHelper.VendorParameterCapabilityStore(decoderName -> {
                    openCount.incrementAndGet();
                    return new MediaCodecHelper.VendorParameterSession() {
                        @Override
                        public List<String> getSupportedVendorParameters() throws Exception {
                            throw new Exception("codec query failed");
                        }

                        @Override
                        public void release() {
                            releaseCount.incrementAndGet();
                        }
                    };
                });

        MediaCodecHelper.VendorParameterSnapshot first =
                store.getSnapshot("c2.qti.av1.decoder", 31);
        MediaCodecHelper.VendorParameterSnapshot retried =
                store.getSnapshot("C2.QTI.AV1.DECODER", 31);

        assertFalse(first.enumerationAvailable);
        assertTrue(first.supportedParameters.isEmpty());
        assertFalse(retried.enumerationAvailable);
        assertEquals(2, openCount.get());
        assertEquals(2, releaseCount.get());
    }

    @Test
    public void api26And30NeverOpenProvider() {
        AtomicInteger openCount = new AtomicInteger();
        MediaCodecHelper.VendorParameterCapabilityStore store =
                new MediaCodecHelper.VendorParameterCapabilityStore(decoderName -> {
                    openCount.incrementAndGet();
                    throw new AssertionError("Provider must not be opened before API 31");
                });

        for (int sdkInt : Arrays.asList(26, 30)) {
            MediaCodecHelper.VendorParameterSnapshot snapshot =
                    store.getSnapshot("c2.qti.avc.decoder", sdkInt);
            assertFalse(snapshot.enumerationAvailable);
            assertTrue(snapshot.supportedParameters.isEmpty());
        }
        assertEquals(0, openCount.get());
    }

    @Test
    public void concurrentCaseVariantLookupsShareOneCompleteSnapshot() throws Exception {
        AtomicInteger openCount = new AtomicInteger();
        AtomicInteger enumerationCount = new AtomicInteger();
        AtomicInteger releaseCount = new AtomicInteger();
        CyclicBarrier callersReady = new CyclicBarrier(2);
        CountDownLatch enumerationEntered = new CountDownLatch(1);
        CountDownLatch allowEnumerationToComplete = new CountDownLatch(1);
        MediaCodecHelper.VendorParameterCapabilityStore store =
                new MediaCodecHelper.VendorParameterCapabilityStore(decoderName -> {
                    openCount.incrementAndGet();
                    return new MediaCodecHelper.VendorParameterSession() {
                        @Override
                        public List<String> getSupportedVendorParameters() throws Exception {
                            enumerationCount.incrementAndGet();
                            enumerationEntered.countDown();
                            if (!allowEnumerationToComplete.await(5, TimeUnit.SECONDS)) {
                                throw new AssertionError("Timed out waiting to complete enumeration");
                            }
                            return Arrays.asList(
                                    "Vendor.QTI-Ext-Dec-Picture-Order.Enable",
                                    "vendor.qti-ext-output-fence.enable");
                        }

                        @Override
                        public void release() {
                            releaseCount.incrementAndGet();
                        }
                    };
                });
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<MediaCodecHelper.VendorParameterSnapshot> firstFuture = executor.submit(() -> {
                callersReady.await(5, TimeUnit.SECONDS);
                return store.getSnapshot("C2.QTI.AVC.Decoder", 33);
            });
            Future<MediaCodecHelper.VendorParameterSnapshot> secondFuture = executor.submit(() -> {
                callersReady.await(5, TimeUnit.SECONDS);
                return store.getSnapshot("c2.qti.avc.decoder", 33);
            });

            assertTrue(enumerationEntered.await(5, TimeUnit.SECONDS));
            allowEnumerationToComplete.countDown();

            MediaCodecHelper.VendorParameterSnapshot first =
                    firstFuture.get(5, TimeUnit.SECONDS);
            MediaCodecHelper.VendorParameterSnapshot second =
                    secondFuture.get(5, TimeUnit.SECONDS);

            assertSame(first, second);
            assertTrue(first.enumerationAvailable);
            assertEquals(Arrays.asList(
                            "vendor.qti-ext-dec-picture-order.enable",
                            "vendor.qti-ext-output-fence.enable"),
                    Arrays.asList(first.supportedParameters.toArray(new String[0])));
            assertEquals(1, openCount.get());
            assertEquals(1, enumerationCount.get());
            assertEquals(1, releaseCount.get());
            try {
                first.supportedParameters.add("mutation");
                fail("Concurrent snapshot must be immutable");
            }
            catch (UnsupportedOperationException expected) {
                // Expected.
            }
        }
        finally {
            allowEnumerationToComplete.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void qualcommHelperAppliesOneS25ProfilePerAttemptAndTerminates() {
        AtomicInteger openCount = new AtomicInteger();
        AtomicInteger releaseCount = new AtomicInteger();
        MediaCodecHelper.VendorParameterCapabilityStore store = s25Store(
                openCount, releaseCount);
        MediaCodecInfo decoderInfo = mock(MediaCodecInfo.class);
        when(decoderInfo.getName()).thenReturn("c2.qti.avc.decoder.low_latency");

        List<Map<String, Integer>> expectedProfiles = Arrays.asList(
                options(
                        MediaFormat.KEY_LOW_LATENCY, 1,
                        LowLatencyProfilePlanner.QTI_PICTURE_ORDER, 1,
                        LowLatencyProfilePlanner.QTI_OUTPUT_FENCE_ENABLE, 1,
                        LowLatencyProfilePlanner.QTI_OUTPUT_FENCE_TYPE, 1,
                        MediaFormat.KEY_OPERATING_RATE, (int) Short.MAX_VALUE),
                options(
                        MediaFormat.KEY_LOW_LATENCY, 1,
                        LowLatencyProfilePlanner.QTI_PICTURE_ORDER, 1,
                        LowLatencyProfilePlanner.QTI_OUTPUT_FENCE_ENABLE, 1,
                        LowLatencyProfilePlanner.QTI_OUTPUT_FENCE_TYPE, 1),
                options(
                        MediaFormat.KEY_LOW_LATENCY, 1,
                        LowLatencyProfilePlanner.QTI_PICTURE_ORDER, 1),
                options(MediaFormat.KEY_LOW_LATENCY, 1));

        for (int tryNumber = 0; tryNumber < expectedProfiles.size(); tryNumber++) {
            MediaFormat format = videoFormat();
            MediaCodecHelper.AppliedLowLatencyOptions applied =
                    MediaCodecHelper.setDecoderLowLatencyOptions(
                    format,
                    decoderInfo,
                    true,
                    tryNumber,
                    store,
                    33,
                    true,
                    true);
            assertNotNull(applied);
            assertEquals(tryNumber, applied.profileIndex);
            assertEquals(Arrays.asList(
                    "qti-experimental-full",
                    "qti-experimental-no-scheduler",
                    "qti-no-fence",
                    "android-standard").get(tryNumber), applied.profileName);
            assertEquals(expectedProfiles.get(tryNumber), applied.integerOptions);
            assertEquals(expectedProfiles.get(tryNumber).keySet(), optionKeys(format));
            assertEquals(expectedProfiles.get(tryNumber), lowLatencyOptions(format));
        }

        MediaFormat exhausted = videoFormat();
        String exhaustedBefore = exhausted.toString();
        assertNull(MediaCodecHelper.setDecoderLowLatencyOptions(
                exhausted,
                decoderInfo,
                true,
                expectedProfiles.size(),
                store,
                33,
                true,
                true));
        assertEquals(exhaustedBefore, exhausted.toString());
        assertTrue(lowLatencyOptions(exhausted).isEmpty());
        assertEquals(1, openCount.get());
        assertEquals(1, releaseCount.get());
    }

    @Test
    public void qualcommHelperUllOffStartsWithStandardAndRejectsInvalidAttempts() {
        MediaCodecHelper.VendorParameterCapabilityStore store =
                s25Store(new AtomicInteger(), new AtomicInteger());
        MediaCodecInfo decoderInfo = mock(MediaCodecInfo.class);
        when(decoderInfo.getName()).thenReturn("c2.qti.hevc.decoder.low_latency");

        MediaFormat standard = videoFormat();
        MediaCodecHelper.AppliedLowLatencyOptions applied =
                MediaCodecHelper.setDecoderLowLatencyOptions(
                standard,
                decoderInfo,
                false,
                0,
                store,
                33,
                true,
                true);
        assertNotNull(applied);
        assertEquals(0, applied.profileIndex);
        assertEquals("android-standard", applied.profileName);
        assertEquals(options(MediaFormat.KEY_LOW_LATENCY, 1),
                lowLatencyOptions(standard));

        for (int invalidAttempt : Arrays.asList(-1, 1, 100)) {
            MediaFormat untouched = videoFormat();
            String untouchedBefore = untouched.toString();
            assertNull(MediaCodecHelper.setDecoderLowLatencyOptions(
                    untouched,
                    decoderInfo,
                    false,
                    invalidAttempt,
                    store,
                    33,
                    true,
                    true));
            assertEquals(untouchedBefore, untouched.toString());
            assertTrue(lowLatencyOptions(untouched).isEmpty());
        }
    }

    @Test
    public void injectableHelperUsesStandardForNonQualcommWithoutOpeningProvider() {
        AtomicInteger openCount = new AtomicInteger();
        MediaCodecHelper.VendorParameterCapabilityStore store =
                new MediaCodecHelper.VendorParameterCapabilityStore(decoderName -> {
                    openCount.incrementAndGet();
                    throw new AssertionError("Non-Qualcomm decoder must not probe QTI parameters");
                });
        MediaCodecInfo decoderInfo = mock(MediaCodecInfo.class);
        when(decoderInfo.getName()).thenReturn("c2.vendor.avc.decoder");
        MediaFormat format = videoFormat();
        String before = format.toString();

        MediaCodecHelper.AppliedLowLatencyOptions applied =
                MediaCodecHelper.setDecoderLowLatencyOptions(
                format,
                decoderInfo,
                true,
                0,
                store,
                33,
                true,
                true);
        assertNotNull(applied);
        assertEquals("android-standard", applied.profileName);
        assertEquals(options(MediaFormat.KEY_LOW_LATENCY, 1), lowLatencyOptions(format));
        assertFalse(before.equals(format.toString()));
        assertEquals(0, openCount.get());
    }

    @Test
    public void mediatekHelperAppliesFreshFiniteProfilesAndTerminates() {
        AtomicInteger openCount = new AtomicInteger();
        AtomicInteger releaseCount = new AtomicInteger();
        MediaCodecHelper.VendorParameterCapabilityStore store =
                new MediaCodecHelper.VendorParameterCapabilityStore(decoderName -> {
                    openCount.incrementAndGet();
                    return session(Arrays.asList(
                            MTK_LOW_LATENCY_MODE,
                            MTK_ULTRA_LOW_LATENCY,
                            MTK_FETCH_TIMEOUT,
                            MTK_FETCH_TIMEOUT_VALUE), releaseCount);
                });
        MediaCodecInfo decoderInfo = mock(MediaCodecInfo.class);
        when(decoderInfo.getName()).thenReturn("C2.MTK.AVC.Decoder");
        List<Map<String, Integer>> expectedProfiles = Arrays.asList(
                options(
                        MediaFormat.KEY_LOW_LATENCY, 1,
                        LowLatencyProfilePlanner.VDEC_LOW_LATENCY, 1,
                        MTK_LOW_LATENCY_MODE, 1,
                        MTK_ULTRA_LOW_LATENCY, 1,
                        MTK_FETCH_TIMEOUT_VALUE, 2,
                        MediaFormat.KEY_OPERATING_RATE, (int) Short.MAX_VALUE),
                options(
                        MediaFormat.KEY_LOW_LATENCY, 1,
                        LowLatencyProfilePlanner.VDEC_LOW_LATENCY, 1,
                        MediaFormat.KEY_OPERATING_RATE, (int) Short.MAX_VALUE),
                options(
                        MediaFormat.KEY_LOW_LATENCY, 1,
                        MediaFormat.KEY_OPERATING_RATE, (int) Short.MAX_VALUE),
                options(MediaFormat.KEY_LOW_LATENCY, 1),
                options(
                        LowLatencyProfilePlanner.VDEC_LOW_LATENCY, 1,
                        MediaFormat.KEY_OPERATING_RATE, (int) Short.MAX_VALUE),
                options(LowLatencyProfilePlanner.VDEC_LOW_LATENCY, 1),
                options(MediaFormat.KEY_OPERATING_RATE, (int) Short.MAX_VALUE));

        for (int tryNumber = 0; tryNumber < expectedProfiles.size(); tryNumber++) {
            MediaFormat format = videoFormat();
            MediaCodecHelper.AppliedLowLatencyOptions applied =
                    MediaCodecHelper.setDecoderLowLatencyOptions(
                    format,
                    decoderInfo,
                    true,
                    tryNumber,
                    store,
                    33,
                    true,
                    true,
                    "Samsung");
            assertNotNull(applied);
            assertEquals(tryNumber, applied.profileIndex);
            assertEquals(expectedProfiles.get(tryNumber), applied.integerOptions);
            assertEquals(expectedProfiles.get(tryNumber).keySet(), optionKeys(format));
            assertEquals(expectedProfiles.get(tryNumber), lowLatencyOptions(format));
        }

        MediaFormat exhausted = videoFormat();
        String exhaustedBefore = exhausted.toString();
        assertNull(MediaCodecHelper.setDecoderLowLatencyOptions(
                exhausted,
                decoderInfo,
                true,
                expectedProfiles.size(),
                store,
                33,
                true,
                true,
                "Samsung"));
        assertEquals(exhaustedBefore, exhausted.toString());
        assertTrue(lowLatencyOptions(exhausted).isEmpty());
        assertEquals(1, openCount.get());
        assertEquals(1, releaseCount.get());
    }

    @Test
    public void genericAmlogicLegacyHelperDoesNotProbeVendorParameters() {
        AtomicInteger openCount = new AtomicInteger();
        MediaCodecHelper.VendorParameterCapabilityStore store =
                new MediaCodecHelper.VendorParameterCapabilityStore(decoderName -> {
                    openCount.incrementAndGet();
                    throw new AssertionError("Generic legacy path must not enumerate vendor parameters");
                });
        MediaCodecInfo decoderInfo = mock(MediaCodecInfo.class);
        when(decoderInfo.getName()).thenReturn("OMX.AMLOGIC.video.decoder.avc");

        MediaFormat standard = videoFormat();
        assertNotNull(MediaCodecHelper.setDecoderLowLatencyOptions(
                standard, decoderInfo, true, 0, store, 33, true, true, "Google"));
        assertEquals(options(MediaFormat.KEY_LOW_LATENCY, 1), lowLatencyOptions(standard));

        MediaFormat legacy = videoFormat();
        assertNotNull(MediaCodecHelper.setDecoderLowLatencyOptions(
                legacy, decoderInfo, true, 1, store, 33, true, true, "Google"));
        assertEquals(options(LowLatencyProfilePlanner.VDEC_LOW_LATENCY, 1),
                lowLatencyOptions(legacy));

        MediaFormat exhausted = videoFormat();
        assertNull(MediaCodecHelper.setDecoderLowLatencyOptions(
                exhausted, decoderInfo, true, 2, store, 33, true, true, "Google"));
        assertTrue(lowLatencyOptions(exhausted).isEmpty());
        assertEquals(0, openCount.get());
    }

    @Test
    public void legacyProfilesPreserveFiniteRiskToSafeCombinations() {
        MediaCodecHelper.VendorParameterCapabilityStore unusedStore =
                new MediaCodecHelper.VendorParameterCapabilityStore(decoderName -> {
                    throw new AssertionError("Legacy paths must not enumerate vendor parameters");
                });
        List<LegacyFixture> fixtures = Arrays.asList(
                new LegacyFixture(
                        "c2.nvidia.avc.decoder",
                        "NVIDIA",
                        true,
                        true,
                        Arrays.asList(
                                "nvidia-legacy-full",
                                "nvidia-legacy-standard",
                                "android-standard",
                                "nvidia-legacy-performance",
                                "nvidia-legacy",
                                "performance-only"),
                        Arrays.asList(
                                options(
                                        MediaFormat.KEY_LOW_LATENCY, 1,
                                        NVIDIA_MEDIA_LOW_LATENCY, 1,
                                        NVIDIA_VENDOR_LOW_LATENCY, 1,
                                        NVIDIA_DISABLE_OUTPUT_REORDER, 1,
                                        NVIDIA_VENDOR_DISABLE_OUTPUT_REORDER, 1,
                                        MediaFormat.KEY_OPERATING_RATE, (int) Short.MAX_VALUE),
                                options(
                                        MediaFormat.KEY_LOW_LATENCY, 1,
                                        NVIDIA_MEDIA_LOW_LATENCY, 1,
                                        NVIDIA_VENDOR_LOW_LATENCY, 1,
                                        NVIDIA_DISABLE_OUTPUT_REORDER, 1,
                                        NVIDIA_VENDOR_DISABLE_OUTPUT_REORDER, 1),
                                options(MediaFormat.KEY_LOW_LATENCY, 1),
                                options(
                                        NVIDIA_MEDIA_LOW_LATENCY, 1,
                                        NVIDIA_VENDOR_LOW_LATENCY, 1,
                                        NVIDIA_DISABLE_OUTPUT_REORDER, 1,
                                        NVIDIA_VENDOR_DISABLE_OUTPUT_REORDER, 1,
                                        MediaFormat.KEY_OPERATING_RATE, (int) Short.MAX_VALUE),
                                options(
                                        NVIDIA_MEDIA_LOW_LATENCY, 1,
                                        NVIDIA_VENDOR_LOW_LATENCY, 1,
                                        NVIDIA_DISABLE_OUTPUT_REORDER, 1,
                                        NVIDIA_VENDOR_DISABLE_OUTPUT_REORDER, 1),
                                options(MediaFormat.KEY_OPERATING_RATE, (int) Short.MAX_VALUE))),
                new LegacyFixture(
                        "OMX.hisi.video.decoder.avc",
                        "Huawei",
                        false,
                        false,
                        Arrays.asList(
                                "kirin-legacy-performance",
                                "kirin-legacy",
                                "performance-only"),
                        Arrays.asList(
                                options(
                                        KIRIN_LOW_LATENCY_REQUEST, 1,
                                        KIRIN_LOW_LATENCY_READY, -1,
                                        MediaFormat.KEY_PRIORITY, 0),
                                options(
                                        KIRIN_LOW_LATENCY_REQUEST, 1,
                                        KIRIN_LOW_LATENCY_READY, -1),
                                options(MediaFormat.KEY_PRIORITY, 0))),
                new LegacyFixture(
                        "c2.exynos.avc.decoder",
                        "Samsung",
                        true,
                        false,
                        Arrays.asList(
                                "exynos-legacy-full",
                                "exynos-legacy-standard",
                                "android-standard",
                                "exynos-legacy-performance",
                                "exynos-legacy",
                                "performance-only"),
                        Arrays.asList(
                                options(
                                        MediaFormat.KEY_LOW_LATENCY, 1,
                                        EXYNOS_LOW_LATENCY, 1,
                                        MediaFormat.KEY_PRIORITY, 0),
                                options(
                                        MediaFormat.KEY_LOW_LATENCY, 1,
                                        EXYNOS_LOW_LATENCY, 1),
                                options(MediaFormat.KEY_LOW_LATENCY, 1),
                                options(
                                        EXYNOS_LOW_LATENCY, 1,
                                        MediaFormat.KEY_PRIORITY, 0),
                                options(EXYNOS_LOW_LATENCY, 1),
                                options(MediaFormat.KEY_PRIORITY, 0))));

        for (LegacyFixture fixture : fixtures) {
            MediaCodecInfo decoderInfo = mock(MediaCodecInfo.class);
            when(decoderInfo.getName()).thenReturn(fixture.decoderName);
            List<MediaCodecHelper.AppliedLowLatencyOptions> profiles = new ArrayList<>();
            for (int profileIndex = 0; profileIndex < fixture.expectedNames.size(); profileIndex++) {
                MediaFormat format = videoFormat();
                MediaCodecHelper.AppliedLowLatencyOptions applied =
                        MediaCodecHelper.setDecoderLowLatencyOptions(
                                format,
                                decoderInfo,
                                true,
                                profileIndex,
                                unusedStore,
                                33,
                                fixture.androidStandardSupported,
                                fixture.maxOperatingRateSupported,
                                fixture.manufacturer);
                assertNotNull(fixture.decoderName, applied);
                profiles.add(applied);
                assertEquals(fixture.expectedOptions.get(profileIndex), applied.integerOptions);
                assertEquals(fixture.expectedOptions.get(profileIndex).keySet(), optionKeys(format));
            }

            assertEquals(fixture.expectedNames, appliedNames(profiles));
            assertNull(MediaCodecHelper.setDecoderLowLatencyOptions(
                    videoFormat(),
                    decoderInfo,
                    true,
                    fixture.expectedNames.size(),
                    unusedStore,
                    33,
                    fixture.androidStandardSupported,
                    fixture.maxOperatingRateSupported,
                    fixture.manufacturer));
        }
    }

    @Test
    public void legacyUllOffOmitsVendorAndKeepsStandardOrSchedulerFallbacks() {
        MediaCodecHelper.VendorParameterCapabilityStore unusedStore =
                new MediaCodecHelper.VendorParameterCapabilityStore(decoderName -> {
                    throw new AssertionError("Legacy paths must not enumerate vendor parameters");
                });

        MediaCodecInfo nvidiaInfo = mock(MediaCodecInfo.class);
        when(nvidiaInfo.getName()).thenReturn("c2.nvidia.avc.decoder");
        assertLegacyProfiles(
                nvidiaInfo,
                false,
                unusedStore,
                true,
                false,
                "NVIDIA",
                Arrays.asList("android-standard", "performance-only"),
                Arrays.asList(
                        options(MediaFormat.KEY_LOW_LATENCY, 1),
                        options(MediaFormat.KEY_PRIORITY, 0)));

        MediaCodecInfo genericInfo = mock(MediaCodecInfo.class);
        when(genericInfo.getName()).thenReturn("c2.vendor.avc.decoder");
        assertLegacyProfiles(
                genericInfo,
                false,
                unusedStore,
                false,
                false,
                "Generic",
                Collections.singletonList("performance-only"),
                Collections.singletonList(options(MediaFormat.KEY_PRIORITY, 0)));
    }

    private static void assertLegacyProfiles(
            MediaCodecInfo decoderInfo,
            boolean ultraLowLatency,
            MediaCodecHelper.VendorParameterCapabilityStore capabilityStore,
            boolean androidLowLatencySupported,
            boolean maxOperatingRateSupported,
            String manufacturer,
            List<String> expectedNames,
            List<Map<String, Integer>> expectedOptions) {
        List<MediaCodecHelper.AppliedLowLatencyOptions> profiles = new ArrayList<>();
        for (int profileIndex = 0; profileIndex < expectedNames.size(); profileIndex++) {
            MediaFormat format = videoFormat();
            MediaCodecHelper.AppliedLowLatencyOptions applied =
                    MediaCodecHelper.setDecoderLowLatencyOptions(
                            format,
                            decoderInfo,
                            ultraLowLatency,
                            profileIndex,
                            capabilityStore,
                            33,
                            androidLowLatencySupported,
                            maxOperatingRateSupported,
                            manufacturer);
            assertNotNull(applied);
            assertEquals(expectedOptions.get(profileIndex), applied.integerOptions);
            assertEquals(expectedOptions.get(profileIndex).keySet(), optionKeys(format));
            profiles.add(applied);
        }
        assertEquals(expectedNames, appliedNames(profiles));
        assertNull(MediaCodecHelper.setDecoderLowLatencyOptions(
                videoFormat(),
                decoderInfo,
                ultraLowLatency,
                expectedNames.size(),
                capabilityStore,
                33,
                androidLowLatencySupported,
                maxOperatingRateSupported,
                manufacturer));
    }

    private static List<String> appliedNames(
            List<MediaCodecHelper.AppliedLowLatencyOptions> profiles) {
        List<String> names = new ArrayList<>();
        for (MediaCodecHelper.AppliedLowLatencyOptions profile : profiles) {
            names.add(profile.profileName);
        }
        return names;
    }

    private static final class LegacyFixture {
        final String decoderName;
        final String manufacturer;
        final boolean androidStandardSupported;
        final boolean maxOperatingRateSupported;
        final List<String> expectedNames;
        final List<Map<String, Integer>> expectedOptions;

        LegacyFixture(String decoderName,
                      String manufacturer,
                      boolean androidStandardSupported,
                      boolean maxOperatingRateSupported,
                      List<String> expectedNames,
                      List<Map<String, Integer>> expectedOptions) {
            this.decoderName = decoderName;
            this.manufacturer = manufacturer;
            this.androidStandardSupported = androidStandardSupported;
            this.maxOperatingRateSupported = maxOperatingRateSupported;
            this.expectedNames = expectedNames;
            this.expectedOptions = expectedOptions;
        }
    }

    private static MediaCodecHelper.VendorParameterCapabilityStore s25Store(
            AtomicInteger openCount,
            AtomicInteger releaseCount) {
        return new MediaCodecHelper.VendorParameterCapabilityStore(decoderName -> {
            openCount.incrementAndGet();
            return session(Arrays.asList(
                    LowLatencyProfilePlanner.QTI_PICTURE_ORDER,
                    LowLatencyProfilePlanner.QTI_OUTPUT_FENCE_ENABLE,
                    LowLatencyProfilePlanner.QTI_OUTPUT_FENCE_TYPE), releaseCount);
        });
    }

    private static MediaFormat videoFormat() {
        return MediaFormat.createVideoFormat("video/avc", 1920, 1080);
    }

    private static Map<String, Integer> lowLatencyOptions(MediaFormat format) {
        LinkedHashMap<String, Integer> options = new LinkedHashMap<>();
        for (String key : LOW_LATENCY_OPTION_KEYS) {
            if (format.containsKey(key)) {
                options.put(key, format.getInteger(key));
            }
        }
        return options;
    }

    private static Set<String> optionKeys(MediaFormat format) {
        Set<String> keys = new HashSet<>(format.getKeys());
        keys.remove(MediaFormat.KEY_MIME);
        keys.remove(MediaFormat.KEY_WIDTH);
        keys.remove(MediaFormat.KEY_HEIGHT);
        return keys;
    }

    private static Map<String, Integer> options(Object... keyValues) {
        LinkedHashMap<String, Integer> options = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            options.put((String) keyValues[i], (Integer) keyValues[i + 1]);
        }
        return options;
    }

    private static MediaCodecHelper.VendorParameterSession session(
            List<String> parameters,
            AtomicInteger releaseCount) {
        return new MediaCodecHelper.VendorParameterSession() {
            @Override
            public List<String> getSupportedVendorParameters() {
                return parameters;
            }

            @Override
            public void release() {
                releaseCount.incrementAndGet();
            }
        };
    }
}
