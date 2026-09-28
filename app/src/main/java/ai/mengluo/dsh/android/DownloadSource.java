package ai.mengluo.dsh.android;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Fixed HTTPS registries; choosing a mirror never changes package names or versions. */
enum DownloadSource {
    MIRROR("mirror", "国内镜像 · npmmirror", "https://registry.npmmirror.com/"),
    OFFICIAL("official", "官方源 · npm", "https://registry.npmjs.org/");

    final String id, title, registry;
    DownloadSource(String id, String title, String registry) {
        this.id = id; this.title = title; this.registry = registry;
    }
    static DownloadSource fromId(String value) {
        for (DownloadSource source : values()) if (source.id.equals(value)) return source;
        return MIRROR;
    }
    String metadataUrl(String name, String version) {
        RuntimePolicy.exactVersion(version);
        try { return registry + URLEncoder.encode(name, StandardCharsets.UTF_8.name()) + "/" + version; }
        catch (java.io.UnsupportedEncodingException impossible) { throw new AssertionError(impossible); }
    }
    java.util.List<String> packageEnvironment() {
        // pnpm 11 no longer reads npm_config_*. Keep both package managers aligned.
        return java.util.List.of("npm_config_registry=" + registry, "pnpm_config_registry=" + registry);
    }
}
