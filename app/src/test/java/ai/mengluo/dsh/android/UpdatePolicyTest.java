package ai.mengluo.dsh.android;
import org.junit.Test;
import static org.junit.Assert.*;

public class UpdatePolicyTest {
    @Test public void semverUsesNumericPrereleases() {
        assertTrue(UpdatePolicy.compare("0.1.7-rc.10", "0.1.7-rc.2") > 0);
        assertTrue(UpdatePolicy.compare("0.1.7", "0.1.7-rc.10") > 0);
        assertTrue(UpdatePolicy.compare("0.1.7-1", "0.1.7-a") < 0);
        assertEquals(0, UpdatePolicy.compare("0.1.7", "0.1.7"));
    }
    @Test public void unsafeVersionsRejected() {
        for (String value : new String[]{"../1.2.3", "01.2.3", "1.2.3 --help", "1.2", "1.2.3-01", "1.2.3-"}) {
            try { UpdatePolicy.version(value); fail(value); } catch (IllegalArgumentException expected) { }
        }
    }
    @Test public void repositoryCannotBeRedirectedToAnAttacker() {
        String good = "https://github.com/" + UpdatePolicy.REPOSITORY + "/releases/download/v0.1.0/MengLuo-arm64.apk";
        assertTrue(UpdatePolicy.releaseUrl(good)); assertTrue(UpdatePolicy.githubTransport(good));
        for (String bad : new String[]{good.replace("https:", "http:"), good.replace("github.com/", "github.com.attacker/"),
            good.replace("github.com/", "user:pass@github.com/"), good + "?redirect=evil", good.replace("mengluo-dsh-android/", "other/"), good.replace("/download/", "/download/../")}) assertFalse(bad, UpdatePolicy.releaseUrl(bad));
        assertFalse(UpdatePolicy.githubTransport("https://raw.githubusercontent.com/evil/file"));
        assertFalse(UpdatePolicy.githubTransport("https://127.0.0.1/test"));
        assertTrue(UpdatePolicy.githubTransport("https://api.github.com/repos/" + UpdatePolicy.REPOSITORY + "/releases/latest"));
        assertTrue(UpdatePolicy.githubTransport("https://api.github.com/repos/" + UpdatePolicy.REPOSITORY + "/releases/assets/123"));
        assertFalse(UpdatePolicy.githubTransport("https://api.github.com/repos/attacker/app/releases/assets/123"));
        assertFalse(UpdatePolicy.githubTransport("https://api.github.com/user"));
        assertTrue(UpdatePolicy.githubTransport("https://release-assets.githubusercontent.com/asset?signature=123"));
    }
    @Test public void cooldownIsOneFixedIntervalNotAccumulation() {
        assertFalse(UpdatePolicy.due(1000, 30999, 30000)); assertTrue(UpdatePolicy.due(1000, 31000, 30000));
        assertTrue(UpdatePolicy.due(100_000, 1000, 30000)); assertTrue(UpdatePolicy.due(0, 1000, UpdatePolicy.DAY));
    }
}
