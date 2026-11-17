package org.leavesmc.leaves.spark;

import me.lucko.spark.paper.PaperSparkPlugin;
import me.lucko.spark.paper.api.Compatibility;
import me.lucko.spark.paper.api.PaperClassLookup;
import me.lucko.spark.paper.api.PaperScheduler;
import me.lucko.spark.paper.common.platform.serverconfig.ServerConfigProvider;
import org.bukkit.Server;
import org.jetbrains.annotations.NotNull;

import java.util.logging.Logger;

public class LeavesSparkPlugin extends PaperSparkPlugin {

    private String previousBackgroundEngine;
    private boolean javaSamplerProtection;

    @Override
    public void enable() {
        // 在后台采样启动之前选择 Java 引擎；无法通过 Java 异常捕获原生 SIGSEGV。
        this.javaSamplerProtection = org.leavesx.leavesx.config.LeavesXRuntime.configuration().extensions().sparkJavaSampler();
        if (this.javaSamplerProtection) {
            this.previousBackgroundEngine = System.getProperty("spark.backgroundProfilerEngine");
            System.setProperty("spark.backgroundProfilerEngine", "java");
        }
        super.enable();
    }

    @Override
    public void disable() {
        try {
            super.disable();
        } finally {
            if (this.javaSamplerProtection) {
                if (this.previousBackgroundEngine == null) {
                    System.clearProperty("spark.backgroundProfilerEngine");
                } else {
                    System.setProperty("spark.backgroundProfilerEngine", this.previousBackgroundEngine);
                }
            }
        }
    }

    @Override
    public void executeCommand(org.bukkit.command.CommandSender sender, String[] args) {
        if (this.javaSamplerProtection && SparkSamplerProtection.isProfiler(args)) {
            if (SparkSamplerProtection.startsProfiler(args) && SparkSamplerProtection.hasAllocationFlag(args)) {
                sender.sendMessage("spark Java 采样保护已开启，不能使用需要原生采样器的 --alloc。");
                return;
            }
            args = SparkSamplerProtection.javaSamplerArguments(args);
        }
        super.executeCommand(sender, args);
    }

    @NotNull
    public static LeavesSparkPlugin create(Compatibility ignoredCompatibility, Server server, Logger logger, PaperScheduler scheduler, PaperClassLookup classLookup) {
        return new LeavesSparkPlugin(server, logger, scheduler, classLookup);
    }

    public LeavesSparkPlugin(Server server, Logger logger, PaperScheduler scheduler, PaperClassLookup classLookup) {
        super(server, logger, scheduler, classLookup);
    }

    @Override
    public ServerConfigProvider createServerConfigProvider() {
        return new LeavesServerConfigProvider();
    }
}
