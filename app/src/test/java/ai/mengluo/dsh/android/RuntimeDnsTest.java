package ai.mengluo.dsh.android;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import java.io.File;
import java.net.InetAddress;
import java.nio.file.Files;
import java.util.List;

public class RuntimeDnsTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    @Test public void offlinePreservesExistingSystemResolver() throws Exception {
        File resolver = temporary.newFile("resolv.conf");
        IO.text(resolver, "nameserver 192.0.2.53\n");
        RuntimeDns.update(resolver, List.of());
        assertEquals("nameserver 192.0.2.53\n", IO.text(resolver));
    }
    @Test public void offlineFirstStartUsesNoInventedDnsAndOnlineStartRefreshes() throws Exception {
        File resolver = new File(temporary.getRoot(), "resolv.conf");
        RuntimeDns.update(resolver, List.of());
        assertTrue(resolver.isFile()); assertFalse(IO.text(resolver).contains("nameserver"));
        RuntimeDns.update(resolver, List.of(InetAddress.getByAddress(new byte[]{(byte) 192, 0, 2, 54})));
        assertEquals("nameserver 192.0.2.54\n", IO.text(resolver));
        assertEquals(1, temporary.getRoot().list().length);
    }
    @Test public void replacesResolverSymlinkWithoutWritingItsTarget() throws Exception {
        File original = temporary.newFile("outside"); IO.text(original, "keep");
        File resolver = new File(temporary.getRoot(), "resolv.conf");
        try { Files.createSymbolicLink(resolver.toPath(), original.toPath()); }
        catch (java.io.IOException | UnsupportedOperationException unavailable) { Assume.assumeNoException(unavailable); }
        RuntimeDns.update(resolver, List.of());
        assertFalse(Files.isSymbolicLink(resolver.toPath()));
        assertEquals("keep", IO.text(original));
    }
}
