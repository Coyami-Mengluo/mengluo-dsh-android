package ai.mengluo.dsh.android;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;

final class RuntimeDns {
    static void update(File resolver, List<InetAddress> servers) throws IOException {
        Path path = resolver.toPath();
        // DNS is not a prerequisite for local commands/the loopback web server.
        // Keep the last system-selected addresses while offline; never invent a public resolver.
        if (servers.isEmpty() && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return;
        StringBuilder value = new StringBuilder();
        for (InetAddress server : servers) value.append("nameserver ").append(server.getHostAddress()).append('\n');
        if (value.length() == 0) value.append("# No active network DNS; refreshed on the next start or command.\n");
        Path temporary = Files.createTempFile(path.getParent(), ".resolv-", ".tmp");
        try {
            Files.write(temporary, value.toString().getBytes(StandardCharsets.UTF_8));
            // Replaces an Ubuntu resolver symlink itself, never its host-side target.
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
}
