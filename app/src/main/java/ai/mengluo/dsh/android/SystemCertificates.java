package ai.mengluo.dsh.android;

import java.io.IOException;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.net.ssl.*;

/** Bootstrap apt HTTPS with roots already trusted by Android, never with TLS checks disabled. */
final class SystemCertificates {
    static String pem() throws Exception {
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init((KeyStore) null);
        StringBuilder pem = new StringBuilder();
        for (TrustManager manager : factory.getTrustManagers()) if (manager instanceof X509TrustManager) {
            for (X509Certificate certificate : ((X509TrustManager) manager).getAcceptedIssuers()) {
                pem.append("-----BEGIN CERTIFICATE-----\n")
                    .append(Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(certificate.getEncoded()))
                    .append("\n-----END CERTIFICATE-----\n");
            }
        }
        if (pem.length() == 0 || pem.length() > 2 * 1024 * 1024) throw new IOException("无法读取 Android 受信任证书");
        return pem.toString();
    }
}
