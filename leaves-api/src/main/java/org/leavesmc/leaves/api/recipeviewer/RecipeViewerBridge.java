package org.leavesmc.leaves.api.recipeviewer;

import java.util.function.Supplier;
import org.bukkit.plugin.Plugin;

/**
 * 可选的服务端配方查看器桥接服务。
 *
 * <p>快照只在服务端内部交换，不代表客户端物品目录或配方协议一定支持自定义组件。</p>
 */
public interface RecipeViewerBridge {
    int API_VERSION = 1;

    int apiVersion();

    /** 返回当前实现能力和降级边界的 JSON 描述。 */
    String capabilitiesJson();

    /** 注册或替换同一 owner 持有的 provider。调用会切换到服务器线程。 */
    void registerProvider(Plugin owner, String providerId, Supplier<String> snapshotJson);

    /** 请求重新采集 provider 快照。 */
    void refresh(Plugin owner, String providerId);

    /** 释放 owner 持有的 provider。 */
    void unregisterProvider(Plugin owner, String providerId);
}
