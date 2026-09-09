package com.limelight.binding.video;

import android.media.MediaFormat;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

final class LowLatencyProfilePlanner {
    static final String VDEC_LOW_LATENCY = "vdec-lowlatency";

    static final String QTI_CORE = "vendor.qti-ext-dec-low-latency.enable";
    static final String QTI_PICTURE_ORDER = "vendor.qti-ext-dec-picture-order.enable";
    static final String QTI_SOFTWARE_FENCE =
            "vendor.qti-ext-output-sw-fence-enable.value";
    static final String QTI_OUTPUT_FENCE_ENABLE = "vendor.qti-ext-output-fence.enable";
    static final String QTI_OUTPUT_FENCE_TYPE = "vendor.qti-ext-output-fence.fence_type";

    static final String MTK_LOW_LATENCY_MODE = "vendor.mtk.vdec.low-latency.mode";
    static final String MTK_DISABLE_IDLE = "vendor.mtk.vdec.disable-idle";
    static final String MTK_VSYNC_ADJUST_ENABLE = "vendor.mtk.vdec.vsync.adjust.enable";
    static final String MTK_ULTRA_LOW_LATENCY = "vendor.mtk.vdec.ultra-low-latency";
    static final String MTK_PRELOAD_FRAME_COUNT = "vendor.mtk.vdec.preload.frame.count";
    static final String MTK_INPUT_QUEUE_DEPTH = "vendor.mtk.vdec.input.max.queue.depth";
    static final String MTK_OUTPUT_QUEUE_DEPTH = "vendor.mtk.vdec.output.max.queue.depth";
    static final String MTK_FETCH_TIMEOUT = "vendor.mtk.vdec.buffer.fetch.timeout.ms";
    static final String MTK_FETCH_TIMEOUT_VALUE = MTK_FETCH_TIMEOUT + ".value";
    static final String MTK_GUARD_INTERVAL = "vendor.mtk.vdec.bq.guard.interval.time";
    static final String MTK_GUARD_INTERVAL_VALUE = MTK_GUARD_INTERVAL + ".value";
    static final String MTK_CPU_BOOST = "vendor.mtk.vdec.cpu.boost.mode";
    static final String MTK_CPU_BOOST_VALUE = MTK_CPU_BOOST + ".value";
    static final String MTK_DVFS_MODE = "vendor.mtk.vdec.dvfs.mode";
    static final String MTK_DVFS_LEVEL = "vendor.mtk.vdec.dvfs.level";

    enum DecoderFamily {
        QUALCOMM,
        MEDIATEK,
        OTHER
    }

    enum PlanPurpose {
        INITIAL_CONFIGURATION,
        RUNTIME_RECOVERY
    }

    static final class Capabilities {
        final DecoderFamily family;
        final String decoderName;
        final int sdkInt;
        final boolean androidLowLatencySupported;
        final boolean maxOperatingRateSupported;
        final boolean legacyVdecLowLatencyAllowed;
        final boolean vendorParameterEnumerationAvailable;
        final Set<String> supportedVendorParameters;

        Capabilities(DecoderFamily family,
                     String decoderName,
                     int sdkInt,
                     boolean androidLowLatencySupported,
                     boolean maxOperatingRateSupported,
                     boolean legacyVdecLowLatencyAllowed,
                     boolean vendorParameterEnumerationAvailable,
                     Set<String> supportedVendorParameters) {
            this.family = Objects.requireNonNull(family);
            this.decoderName = Objects.requireNonNull(decoderName);
            this.sdkInt = sdkInt;
            this.androidLowLatencySupported = androidLowLatencySupported;
            this.maxOperatingRateSupported = maxOperatingRateSupported;
            this.legacyVdecLowLatencyAllowed = legacyVdecLowLatencyAllowed;
            this.vendorParameterEnumerationAvailable = vendorParameterEnumerationAvailable;

            TreeSet<String> normalizedParameters = new TreeSet<>();
            for (String parameter : supportedVendorParameters) {
                if (parameter != null) {
                    normalizedParameters.add(parameter.toLowerCase(Locale.US));
                }
            }
            this.supportedVendorParameters = Collections.unmodifiableSet(
                    new LinkedHashSet<>(normalizedParameters));
        }
    }

    static final class Profile {
        final String name;
        final Map<String, Integer> integerOptions;

        private final List<Map.Entry<String, Integer>> orderedOptions;

        Profile(String name, Map<String, Integer> integerOptions) {
            this.name = Objects.requireNonNull(name);

            LinkedHashMap<String, Integer> optionsCopy = new LinkedHashMap<>(integerOptions);
            this.integerOptions = Collections.unmodifiableMap(optionsCopy);

            List<Map.Entry<String, Integer>> entries = new ArrayList<>(optionsCopy.size());
            for (Map.Entry<String, Integer> entry : optionsCopy.entrySet()) {
                entries.add(new AbstractMap.SimpleImmutableEntry<>(entry));
            }
            this.orderedOptions = Collections.unmodifiableList(entries);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Profile)) {
                return false;
            }

            Profile otherProfile = (Profile) other;
            return orderedOptions.equals(otherProfile.orderedOptions);
        }

        @Override
        public int hashCode() {
            return orderedOptions.hashCode();
        }
    }

    static List<Profile> plan(Capabilities capabilities,
                              boolean ultraLowLatency,
                              PlanPurpose purpose) {
        Objects.requireNonNull(capabilities);
        Objects.requireNonNull(purpose);

        if (purpose == PlanPurpose.RUNTIME_RECOVERY) {
            List<Profile> recoveryProfiles = new ArrayList<>();
            addProfile(recoveryProfiles, "android-standard", standardOptions(capabilities));
            return deduplicateProfiles(recoveryProfiles);
        }

        if (capabilities.family == DecoderFamily.QUALCOMM) {
            return planQualcomm(capabilities, ultraLowLatency);
        }

        if (capabilities.family == DecoderFamily.MEDIATEK) {
            return planMediaTek(capabilities, ultraLowLatency);
        }

        if (capabilities.legacyVdecLowLatencyAllowed) {
            return planGenericLegacy(capabilities);
        }

        List<Profile> profiles = new ArrayList<>();
        addProfile(profiles, "android-standard", standardOptions(capabilities));
        return deduplicateProfiles(profiles);
    }

    static DecoderFamily classifyDecoderFamily(String decoderName) {
        String normalizedName = Objects.requireNonNull(decoderName).toLowerCase(Locale.US);
        if (startsWithAny(normalizedName, "omx.qcom", "omx.qti", "c2.qti", "c2.qcom")) {
            return DecoderFamily.QUALCOMM;
        }
        if (startsWithAny(normalizedName, "omx.mtk", "c2.mtk")) {
            return DecoderFamily.MEDIATEK;
        }
        return DecoderFamily.OTHER;
    }

    static boolean isLegacyVdecLowLatencyAllowed(String decoderName,
                                                  String manufacturer,
                                                  int sdkInt) {
        DecoderFamily family = classifyDecoderFamily(decoderName);
        if (family == DecoderFamily.QUALCOMM) {
            return false;
        }
        if ("xiaomi".equalsIgnoreCase(manufacturer) && sdkInt <= 23) {
            return false;
        }

        String normalizedName = decoderName.toLowerCase(Locale.US);
        return family == DecoderFamily.MEDIATEK ||
                startsWithAny(normalizedName, "omx.amlogic", "c2.amlogic") ||
                "amazon".equalsIgnoreCase(manufacturer);
    }

    static List<Profile> deduplicateProfiles(List<Profile> profiles) {
        LinkedHashSet<Profile> uniqueProfiles = new LinkedHashSet<>(profiles);
        return Collections.unmodifiableList(new ArrayList<>(uniqueProfiles));
    }

    private static List<Profile> planQualcomm(Capabilities capabilities,
                                               boolean ultraLowLatency) {
        boolean legacyQualcomm = capabilities.sdkInt >= 26 && capabilities.sdkInt < 31;
        boolean qtiCore = legacyQualcomm || supportsVendorParameter(capabilities, QTI_CORE);
        boolean pictureOrder = legacyQualcomm ||
                supportsVendorParameter(capabilities, QTI_PICTURE_ORDER);

        List<Profile> profiles = new ArrayList<>();
        if (!ultraLowLatency) {
            addProfile(profiles, "android-standard", standardOptions(capabilities));
            addQtiCorePictureProfile(profiles, capabilities, qtiCore, pictureOrder);
            addQtiCoreOnlyProfile(profiles, qtiCore);
            return deduplicateProfiles(profiles);
        }

        boolean outputFence = supportsVendorParameter(capabilities, QTI_OUTPUT_FENCE_ENABLE) &&
                supportsVendorParameter(capabilities, QTI_OUTPUT_FENCE_TYPE);
        boolean softwareFence = supportsVendorParameter(capabilities, QTI_SOFTWARE_FENCE);

        LinkedHashMap<String, Integer> full = qtiBaseOptions(
                capabilities, qtiCore, pictureOrder);
        addPreferredFence(full, outputFence, softwareFence);
        addScheduler(full, capabilities);
        addProfile(profiles, "qti-experimental-full", full);

        if (outputFence && softwareFence) {
            LinkedHashMap<String, Integer> alternateFence = qtiBaseOptions(
                    capabilities, qtiCore, pictureOrder);
            alternateFence.put(QTI_SOFTWARE_FENCE, 1);
            addScheduler(alternateFence, capabilities);
            addProfile(profiles, "qti-experimental-alt-fence", alternateFence);
        }

        LinkedHashMap<String, Integer> noScheduler = qtiBaseOptions(
                capabilities, qtiCore, pictureOrder);
        addPreferredFence(noScheduler, outputFence, softwareFence);
        addProfile(profiles, "qti-experimental-no-scheduler", noScheduler);

        addProfile(profiles, "qti-no-fence",
                qtiBaseOptions(capabilities, qtiCore, pictureOrder));
        addProfile(profiles, "android-standard", standardOptions(capabilities));
        addQtiCorePictureProfile(profiles, capabilities, qtiCore, pictureOrder);
        addQtiCoreOnlyProfile(profiles, qtiCore);

        return deduplicateProfiles(profiles);
    }

    private static List<Profile> planMediaTek(Capabilities capabilities,
                                               boolean ultraLowLatency) {
        LinkedHashMap<String, Integer> standard = standardOptions(capabilities);
        LinkedHashMap<String, Integer> legacy = legacyOptions(capabilities);
        List<Profile> profiles = new ArrayList<>();

        if (!ultraLowLatency) {
            addProfile(profiles, "android-standard", standard);
            addProfile(profiles, "mtk-legacy", legacy);
            return deduplicateProfiles(profiles);
        }

        LinkedHashMap<String, Integer> scheduler = schedulerOptions(capabilities);
        LinkedHashMap<String, Integer> experimental = combinedOptions(standard, legacy);
        int nonVendorOptionCount = experimental.size();
        addMediaTekVendorOptions(experimental, capabilities);
        if (experimental.size() > nonVendorOptionCount) {
            experimental.putAll(scheduler);
            addProfile(profiles, "mtk-experimental-full", experimental);
        }

        if (!standard.isEmpty() && !legacy.isEmpty() && !scheduler.isEmpty()) {
            addProfile(profiles, "mtk-standard-legacy-performance",
                    combinedOptions(standard, legacy, scheduler));
        }
        if (!standard.isEmpty() && !scheduler.isEmpty()) {
            addProfile(profiles, "mtk-standard-performance",
                    combinedOptions(standard, scheduler));
        }
        addProfile(profiles, "android-standard", standard);
        if (!legacy.isEmpty() && !scheduler.isEmpty()) {
            addProfile(profiles, "mtk-legacy-performance",
                    combinedOptions(legacy, scheduler));
        }
        addProfile(profiles, "mtk-legacy", legacy);
        addProfile(profiles, "performance-only", scheduler);
        return deduplicateProfiles(profiles);
    }

    private static List<Profile> planGenericLegacy(Capabilities capabilities) {
        List<Profile> profiles = new ArrayList<>();
        addProfile(profiles, "android-standard", standardOptions(capabilities));
        addProfile(profiles, "legacy-vdec", legacyOptions(capabilities));
        return deduplicateProfiles(profiles);
    }

    private static LinkedHashMap<String, Integer> qtiBaseOptions(
            Capabilities capabilities,
            boolean qtiCore,
            boolean pictureOrder) {
        LinkedHashMap<String, Integer> options = standardOptions(capabilities);
        if (qtiCore) {
            options.put(QTI_CORE, 1);
        }
        if (pictureOrder) {
            options.put(QTI_PICTURE_ORDER,
                    capabilities.decoderName.toLowerCase(Locale.US).startsWith("omx.qcom") ? 0 : 1);
        }
        return options;
    }

    private static void addQtiCorePictureProfile(List<Profile> profiles,
                                                  Capabilities capabilities,
                                                  boolean qtiCore,
                                                  boolean pictureOrder) {
        if (!qtiCore || !pictureOrder) {
            return;
        }

        LinkedHashMap<String, Integer> options = new LinkedHashMap<>();
        options.put(QTI_CORE, 1);
        options.put(QTI_PICTURE_ORDER,
                capabilities.decoderName.toLowerCase(Locale.US).startsWith("omx.qcom") ? 0 : 1);
        addProfile(profiles, "qti-core-picture", options);
    }

    private static void addQtiCoreOnlyProfile(List<Profile> profiles, boolean qtiCore) {
        if (!qtiCore) {
            return;
        }

        LinkedHashMap<String, Integer> options = new LinkedHashMap<>();
        options.put(QTI_CORE, 1);
        addProfile(profiles, "qti-core-only", options);
    }

    private static LinkedHashMap<String, Integer> standardOptions(Capabilities capabilities) {
        LinkedHashMap<String, Integer> options = new LinkedHashMap<>();
        if (capabilities.androidLowLatencySupported) {
            options.put(MediaFormat.KEY_LOW_LATENCY, 1);
        }
        return options;
    }

    private static LinkedHashMap<String, Integer> legacyOptions(Capabilities capabilities) {
        LinkedHashMap<String, Integer> options = new LinkedHashMap<>();
        if (capabilities.legacyVdecLowLatencyAllowed) {
            options.put(VDEC_LOW_LATENCY, 1);
        }
        return options;
    }

    @SafeVarargs
    private static LinkedHashMap<String, Integer> combinedOptions(
            Map<String, Integer>... optionSets) {
        LinkedHashMap<String, Integer> combined = new LinkedHashMap<>();
        for (Map<String, Integer> optionSet : optionSets) {
            combined.putAll(optionSet);
        }
        return combined;
    }

    private static LinkedHashMap<String, Integer> schedulerOptions(
            Capabilities capabilities) {
        LinkedHashMap<String, Integer> options = new LinkedHashMap<>();
        addScheduler(options, capabilities);
        return options;
    }

    private static void addMediaTekVendorOptions(LinkedHashMap<String, Integer> options,
                                                  Capabilities capabilities) {
        addAdvertisedOption(options, capabilities, MTK_LOW_LATENCY_MODE, 1);
        addAdvertisedOption(options, capabilities, MTK_DISABLE_IDLE, 1);
        addAdvertisedOption(options, capabilities, MTK_VSYNC_ADJUST_ENABLE, 0);
        addAdvertisedOption(options, capabilities, MTK_ULTRA_LOW_LATENCY, 1);
        addAdvertisedOption(options, capabilities, MTK_PRELOAD_FRAME_COUNT, 0);
        addAdvertisedOption(options, capabilities, MTK_INPUT_QUEUE_DEPTH, 2);
        addAdvertisedOption(options, capabilities, MTK_OUTPUT_QUEUE_DEPTH, 2);
        addAdvertisedAlias(options, capabilities,
                MTK_FETCH_TIMEOUT, MTK_FETCH_TIMEOUT_VALUE, 2);
        addAdvertisedAlias(options, capabilities,
                MTK_GUARD_INTERVAL, MTK_GUARD_INTERVAL_VALUE, 2);
        addAdvertisedAlias(options, capabilities,
                MTK_CPU_BOOST, MTK_CPU_BOOST_VALUE, 1);
        addAdvertisedOption(options, capabilities, MTK_DVFS_MODE, 1);
        addAdvertisedOption(options, capabilities, MTK_DVFS_LEVEL, 1);
    }

    private static void addAdvertisedOption(LinkedHashMap<String, Integer> options,
                                             Capabilities capabilities,
                                             String key,
                                             int value) {
        if (supportsVendorParameter(capabilities, key)) {
            options.put(key, value);
        }
    }

    private static void addAdvertisedAlias(LinkedHashMap<String, Integer> options,
                                            Capabilities capabilities,
                                            String primary,
                                            String alternate,
                                            int value) {
        if (supportsVendorParameter(capabilities, alternate)) {
            options.put(alternate, value);
        }
        else if (supportsVendorParameter(capabilities, primary)) {
            options.put(primary, value);
        }
    }

    private static void addPreferredFence(LinkedHashMap<String, Integer> options,
                                          boolean outputFence,
                                          boolean softwareFence) {
        if (outputFence) {
            options.put(QTI_OUTPUT_FENCE_ENABLE, 1);
            options.put(QTI_OUTPUT_FENCE_TYPE, 1);
        }
        else if (softwareFence) {
            options.put(QTI_SOFTWARE_FENCE, 1);
        }
    }

    private static void addScheduler(LinkedHashMap<String, Integer> options,
                                     Capabilities capabilities) {
        if (capabilities.maxOperatingRateSupported) {
            options.put(MediaFormat.KEY_OPERATING_RATE, (int) Short.MAX_VALUE);
        }
        else if (capabilities.sdkInt >= 23) {
            options.put(MediaFormat.KEY_PRIORITY, 0);
        }
    }

    private static boolean supportsVendorParameter(Capabilities capabilities, String parameter) {
        return capabilities.sdkInt >= 31 &&
                capabilities.vendorParameterEnumerationAvailable &&
                capabilities.supportedVendorParameters.contains(parameter.toLowerCase(Locale.US));
    }

    private static boolean startsWithAny(String value, String... prefixes) {
        for (String prefix : prefixes) {
            if (value.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static void addProfile(List<Profile> profiles,
                                   String name,
                                   LinkedHashMap<String, Integer> options) {
        if (!options.isEmpty()) {
            profiles.add(new Profile(name, options));
        }
    }

    private LowLatencyProfilePlanner() {
    }
}
