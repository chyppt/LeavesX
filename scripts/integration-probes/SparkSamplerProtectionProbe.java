package org.leavesmc.leaves.spark;

/** 无服务器依赖的命令边界回归，失败直接返回非零退出码。 */
public final class SparkSamplerProtectionProbe {
    public static void main(String[] args) {
        int checks = 0;
        for (String alias : new String[]{"profiler", "sampler"}) {
            String[] start = {alias, "start", "--timeout", "30"};
            String[] result = SparkSamplerProtection.javaSamplerArguments(start);
            check(result.length == 5 && result[4].equals("--force-java-sampler"));
            check(start.length == 4);
            checks += 2;
            for (String action : new String[]{"stop", "cancel", "info", "open"}) {
                String[] command = {alias, action};
                check(SparkSamplerProtection.javaSamplerArguments(command) == command);
                checks++;
            }
        }
        String[] forced = {"profiler", "start", "--force-java-sampler"};
        check(SparkSamplerProtection.javaSamplerArguments(forced) == forced);
        check(SparkSamplerProtection.hasAllocationFlag(new String[]{"--alloc"}));
        check(SparkSamplerProtection.hasAllocationFlag(new String[]{"--alloc=true"}));
        check(!SparkSamplerProtection.hasAllocationFlag(new String[]{"--timeout", "30"}));
        check(!SparkSamplerProtection.isProfiler(new String[0]));
        check(!SparkSamplerProtection.isProfiler(new String[]{"healthreport"}));
        System.out.println("PASS " + (checks + 6) + " assertions");
    }

    private static void check(boolean condition) {
        if (!condition) throw new AssertionError("spark 采样参数回归失败");
    }
}
