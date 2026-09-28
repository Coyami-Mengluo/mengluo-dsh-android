package ai.mengluo.dsh.android;

import org.junit.Test;
import static org.junit.Assert.*;

public class DownloadSourceTest {
    @Test public void savedChoiceIsRestoredAndUnknownIdsCannotInjectUrls() {
        assertEquals(DownloadSource.OFFICIAL, DownloadSource.fromId("official"));
        assertEquals(DownloadSource.MIRROR, DownloadSource.fromId("mirror"));
        assertEquals(DownloadSource.MIRROR, DownloadSource.fromId("https://unknown.example/"));
        assertEquals(DownloadSource.MIRROR, DownloadSource.fromId(null));
    }
    @Test public void exactVersionProbeEncodesScopedPackage() {
        assertEquals("https://registry.npmmirror.com/%40deepseek-ai%2Fdsh/0.1.7-rc.2",
            DownloadSource.MIRROR.metadataUrl("@deepseek-ai/dsh", "0.1.7-rc.2"));
        assertThrows(IllegalArgumentException.class, () -> DownloadSource.OFFICIAL.metadataUrl("pnpm", "latest"));
    }
    @Test public void allSourcesUseHttpsAndNoAuthentication() {
        for (DownloadSource source : DownloadSource.values()) {
            var uri = java.net.URI.create(source.registry);
            assertEquals("https", uri.getScheme()); assertNull(uri.getUserInfo()); assertNotNull(uri.getHost());
        }
    }
    @Test public void registryReachesNpmAndPnpm11Independently() {
        for (DownloadSource source : DownloadSource.values()) {
            assertTrue(source.packageEnvironment().contains("npm_config_registry=" + source.registry));
            assertTrue(source.packageEnvironment().contains("pnpm_config_registry=" + source.registry));
        }
    }
}
