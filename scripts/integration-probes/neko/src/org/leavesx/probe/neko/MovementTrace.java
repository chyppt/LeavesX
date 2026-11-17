package org.leavesx.probe.neko;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** 有界记录；检测线程不写磁盘，不等待队列，也不持有世界或实体对象。 */
final class MovementTrace implements AutoCloseable {
    static final int BEFORE_LINES = 512;
    static final int AFTER_LINES = 512;
    private static final int MAX_LINES = 10_000;
    private final ArrayBlockingQueue<String> pending = new ArrayBlockingQueue<>(2048);
    private final ArrayDeque<String> before = new ArrayDeque<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicInteger dropped = new AtomicInteger();
    private final AtomicReference<String> failure = new AtomicReference<>();
    private final CountDownLatch finished = new CountDownLatch(1);
    private final Thread writer;
    final UUID player;
    final long deadline;
    final Path path;
    private int after;
    private int lines;
    private boolean triggered;
    private long wireOut;
    private long wireIn;

    MovementTrace(UUID player, long durationSeconds, Path path) throws IOException {
        this(player, durationSeconds, path, null);
    }

    MovementTrace(UUID player, long durationSeconds, Path path, String metadata) throws IOException {
        this.player = player;
        this.deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(durationSeconds);
        this.path = path;
        Files.createDirectories(path.getParent());
        // 在命令线程验证路径可写，避免直到误判出现才发现无法记录。
        Files.createFile(path);
        writer = Thread.ofPlatform().daemon().name("LeavesX-Neko-Trace").unstarted(this::writeLoop);
        writer.start();
        offer("# started=" + Instant.now() + " player=" + player + " durationSeconds=" + durationSeconds);
        if (metadata != null) offer("# " + metadata.replace('\r', ' ').replace('\n', ' '));
        offer("# wire: PacketEvents-MONITOR 是插件观察边界，不等同客户端已收到；reencode=true 时原始字节可能不是最终内容");
        offer("# ack: blockSequence 为方块操作确认序号；transactionId 为 ping/pong，不与 txSent/txReceived 计数混用");
    }

    synchronized void append(String text, boolean trigger) {
        if (closed.get() || System.nanoTime() >= deadline) return;
        String line = "time=" + Instant.now() + " nano=" + System.nanoTime()
            + " thread=" + Thread.currentThread().getName().replace('\n', '_') + " "
            + text.replace('\r', ' ').replace('\n', ' ');
        if (trigger && !triggered) {
            triggered = true;
            offer("# first-anomaly: 保留前 " + BEFORE_LINES + " 条记录；事件可能由已有误判循环触发，需结合时间判断");
            before.forEach(this::offer);
            before.clear();
            after = AFTER_LINES;
        }
        if (triggered && after > 0) {
            offer(line);
            after--;
        } else if (!triggered) {
            if (before.size() == BEFORE_LINES) before.removeFirst();
            before.addLast(line);
        }
    }

    synchronized void appendWire(boolean outgoing, String text) {
        if (!accepting()) return;
        if (outgoing) wireOut++; else wireIn++;
        append(text, false);
    }

    synchronized String wireCounts() { return "wire-out=" + wireOut + " wire-in=" + wireIn; }

    private void offer(String line) {
        if (lines++ >= MAX_LINES || !pending.offer(line)) dropped.incrementAndGet();
    }

    int dropped() { return dropped.get(); }
    String failure() { return failure.get(); }
    synchronized boolean triggered() { return triggered; }

    synchronized boolean accepting() {
        return !closed.get() && System.nanoTime() < deadline && (!triggered || after > 0);
    }

    private void writeLoop() {
        try (var output = Files.newBufferedWriter(path, StandardCharsets.UTF_8, StandardOpenOption.APPEND)) {
            while (!closed.get() || !pending.isEmpty()) {
                String line = pending.poll(250, TimeUnit.MILLISECONDS);
                if (line != null) {
                    output.write(line);
                    output.newLine();
                }
            }
            output.write("# closed=" + Instant.now() + " dropped=" + dropped.get() + " " + wireCounts());
            output.newLine();
        } catch (IOException error) {
            failure.compareAndSet(null, error.getClass().getSimpleName());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            failure.compareAndSet(null, "记录线程被中断");
        } finally {
            finished.countDown();
        }
    }

    @Override
    public synchronized void close() {
        if (closed.get()) return;
        if (!triggered) {
            offer("# 没有观察到触发事件，仅保存末尾移动记录");
            before.forEach(this::offer);
            before.clear();
        }
        closed.set(true);
        // 退出命令和到期回调不等待磁盘写入，避免诊断工具自身制造长 Tick。
    }

    boolean awaitClosed(long milliseconds) throws InterruptedException {
        return finished.await(milliseconds, TimeUnit.MILLISECONDS);
    }
}
