package ai.mengluo.dsh.android;

import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Installer-only Ubuntu sources. Never rewrites the user's sources.list or apt.conf. */
final class AptSetup {
    static final String DIRECTORY = "etc/apt/mengluo-installer";
    static final String CA_FILE = "/" + DIRECTORY + "/android-ca.pem";
    private static final String KEYRING = "/usr/share/keyrings/ubuntu-archive-keyring.gpg";
    enum Stage { INDEX, DOWNLOAD, INSTALL }
    interface Runner { void run(List<String> command, int seconds) throws Exception; }
    interface Check { void run() throws Exception; }

    static String repository(DownloadSource source, String abi, boolean security) {
        if (!abi.equals("arm64-v8a") && !abi.equals("x86_64")) throw new IllegalArgumentException("Unsupported ABI");
        if (source == DownloadSource.MIRROR)
            return "https://mirrors.ustc.edu.cn/" + (abi.equals("arm64-v8a") ? "ubuntu-ports/" : "ubuntu/");
        if (abi.equals("arm64-v8a")) return "https://ports.ubuntu.com/ubuntu-ports/";
        return security ? "https://security.ubuntu.com/ubuntu/" : "https://archive.ubuntu.com/ubuntu/";
    }
    static String sources(DownloadSource source, String abi) {
        String arch = abi.equals("arm64-v8a") ? "arm64" : "amd64";
        // Git, Python and CA certificates do not need restricted/multiverse or source indexes.
        return "Types: deb\nURIs: " + repository(source, abi, false)
            + "\nSuites: noble noble-updates\nComponents: main universe\nArchitectures: " + arch
            + "\nSigned-By: " + KEYRING + "\n\nTypes: deb\nURIs: " + repository(source, abi, true)
            + "\nSuites: noble-security\nComponents: main universe\nArchitectures: " + arch
            + "\nSigned-By: " + KEYRING + "\n";
    }
    static String sourceFile(DownloadSource source, String abi) {
        repository(source, abi, false); // Reject unexpected ABI/path input.
        return "/" + DIRECTORY + "/" + source.id + "-" + abi + ".sources";
    }
    static File privateFile(File root, String path) throws IOException {
        File lexical = new File(root.getCanonicalFile(), path);
        File checked = RuntimePolicy.inside(root, path);
        if (!checked.equals(lexical)) throw new IOException("安装器配置路径不能经过符号链接");
        return checked;
    }
    static void prepare(File root, String abi, String certificates) throws IOException {
        String os = IO.text(privateFile(root, "usr/lib/os-release"));
        if (!os.matches("(?s).*\\nVERSION_CODENAME=noble(?:\\r?\\n|$).*")
            || !os.matches("(?s).*(?:^|\\n)ID=ubuntu(?:\\r?\\n|$).*"))
            throw new IOException("Ubuntu 基础环境版本不匹配，未修改软件源");
        if (!privateFile(root, KEYRING.substring(1)).isFile()) throw new IOException("缺少 Ubuntu 官方签名密钥环");
        if (certificates == null || !certificates.contains("-----BEGIN CERTIFICATE-----"))
            throw new IOException("没有可用于 HTTPS 校验的系统证书");
        File directory = privateFile(root, DIRECTORY);
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法准备安装器软件源目录");
        IO.text(privateFile(root, CA_FILE.substring(1)), certificates);
        for (DownloadSource source : DownloadSource.values())
            IO.text(privateFile(root, sourceFile(source, abi).substring(1)), sources(source, abi));
    }
    static List<String> command(DownloadSource source, String abi, Stage stage) {
        ArrayList<String> args = new ArrayList<>(List.of("/usr/bin/apt-get",
            "-o", "APT::Sandbox::User=root", "-o", "Dir::Etc::sourcelist=" + sourceFile(source, abi),
            "-o", "Dir::Etc::sourceparts=-", "-o", "Acquire::Languages=none",
            "-o", "Acquire::Retries=1", "-o", "Acquire::http::Timeout=25", "-o", "Acquire::https::Timeout=25",
            "-o", "Acquire::https::CAInfo=" + CA_FILE, "-o", "Acquire::https::Verify-Peer=true",
            "-o", "Acquire::https::Verify-Host=true", "-o", "Acquire::AllowInsecureRepositories=false",
            "-o", "Acquire::AllowDowngradeToInsecureRepositories=false", "-o", "APT::Get::AllowUnauthenticated=false"));
        if (stage == Stage.INDEX) args.addAll(List.of("-o", "APT::Update::Error-Mode=any", "update"));
        else args.addAll(List.of("install", "-y", "--no-install-recommends", "--no-remove",
            stage == Stage.DOWNLOAD ? "--download-only" : "--no-download", "git", "python3", "ca-certificates"));
        return args;
    }
    static void install(DownloadSource source, String abi, Runner runner, Consumer<String> progress,
                        Check checkCancelled) throws Exception {
        List<DownloadSource> candidates = source == DownloadSource.MIRROR
            ? List.of(DownloadSource.MIRROR, DownloadSource.OFFICIAL) : List.of(DownloadSource.OFFICIAL);
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(15);
        boolean recoveryAttempted = false;
        for (int index = 0; index < candidates.size(); index++) {
            DownloadSource candidate = candidates.get(index);
            String title = candidate == DownloadSource.MIRROR ? "Ubuntu 国内镜像 · USTC" : "Ubuntu 官方源";
            try {
                checkCancelled.run();
                progress.accept("下载 Ubuntu 软件索引 · " + title + " · 跳过翻译索引");
                runner.run(command(candidate, abi, Stage.INDEX), remaining(deadline, 240));
                checkCancelled.run();
                progress.accept("下载 Git、Python 和证书 · " + title + " · 保留官方签名校验");
                try { runner.run(command(candidate, abi, Stage.DOWNLOAD), remaining(deadline, 600)); }
                catch (CommandFailure error) {
                    if (!error.dpkgInterrupted || recoveryAttempted) throw error;
                    recoveryAttempted = true; checkCancelled.run();
                    progress.accept("检测到上次 Ubuntu 安装中断，正在恢复未完成的配置 · 不更换下载源 · 上限 10 分钟");
                    long recoveryStarted = System.nanoTime();
                    try { runner.run(List.of("/usr/bin/dpkg", "--configure", "-a"), 600); }
                    catch (CommandFailure recoveryError) {
                        checkCancelled.run();
                        throw new IOException("恢复上次 Ubuntu 安装失败（不是网络错误，未更换下载源）：" + recoveryError.getMessage(), recoveryError);
                    }
                    deadline += System.nanoTime() - recoveryStarted;
                    checkCancelled.run(); progress.accept("未完成配置已恢复，继续从原下载源安装依赖 · " + title);
                    runner.run(command(candidate, abi, Stage.DOWNLOAD), remaining(deadline, 600));
                }
            } catch (CommandFailure error) {
                checkCancelled.run();
                if (error.localPackageState) throw new IOException("Ubuntu 本地安装状态异常（不是下载源问题，未更换源）：" + error.getMessage(), error);
                if (index + 1 == candidates.size()) throw new IOException(title + "索引或依赖下载失败：" + error.getMessage(), error);
                progress.accept("Ubuntu 镜像下载未完成，改用官方源重试一次：" + error.getMessage());
                continue;
            }
            checkCancelled.run();
            progress.accept("本地安装 Git、Python 和证书 · 下载已完成");
            try { runner.run(command(candidate, abi, Stage.INSTALL), 600); }
            catch (CommandFailure error) {
                // Never mask dpkg/filesystem failures by retrying package installation on another mirror.
                throw new IOException("Ubuntu 依赖本地安装失败（不是下载阶段）：" + error.getMessage(), error);
            }
            return;
        }
    }
    private static int remaining(long deadline, int limit) throws IOException {
        long seconds = TimeUnit.NANOSECONDS.toSeconds(deadline - System.nanoTime());
        if (seconds <= 0) throw new IOException("Ubuntu 索引与依赖下载已超过 15 分钟，请检查网络后重试");
        return (int) Math.min(limit, seconds);
    }
}
