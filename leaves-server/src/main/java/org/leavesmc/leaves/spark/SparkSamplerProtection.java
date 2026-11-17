package org.leavesmc.leaves.spark;

import java.util.Arrays;

/** 仅改写采样启动参数，不影响停止、报告和其他诊断命令。 */
final class SparkSamplerProtection {
    private SparkSamplerProtection() {}

    static boolean isProfiler(String[] args) {
        return args.length > 0 && (args[0].equalsIgnoreCase("profiler") || args[0].equalsIgnoreCase("sampler"));
    }

    static boolean hasAllocationFlag(String[] args) {
        return Arrays.stream(args).anyMatch(arg -> arg.equalsIgnoreCase("--alloc") || arg.startsWith("--alloc="));
    }

    static boolean startsProfiler(String[] args) {
        if (!isProfiler(args) || args.length < 2) return false;
        for (int i = 1; i < args.length; i++) {
            if (java.util.Set.of("info", "open", "trust-viewer", "cancel", "stop").contains(args[i])
                || java.util.Set.of("--info", "--open", "--trust-viewer", "--cancel", "--stop").contains(args[i])) return false;
        }
        return true;
    }

    static String[] javaSamplerArguments(String[] args) {
        if (!startsProfiler(args)
            || Arrays.stream(args).anyMatch(arg -> arg.equals("--force-java-sampler"))) {
            return args;
        }
        String[] guarded = Arrays.copyOf(args, args.length + 1);
        guarded[args.length] = "--force-java-sampler";
        return guarded;
    }
}
