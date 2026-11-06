import com.android.apksig.ApkSigner;
import com.android.apksig.ApkVerifier;

import java.io.File;
import java.io.FileInputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

/**
 * 极小的 APK 签名/验签工具。
 *
 * Maven 上的 com.android.tools.build:apksig 只有库、没有 apksigner 命令行，
 * 这里直接调它的 API，省得去装 Android SDK 的 build-tools。
 *
 * 用法：
 *   java -cp apksig.jar:. SignApk sign  <keystore> <storePass> <keyPass> <alias> <in.apk> <out.apk>
 *   java -cp apksig.jar:. SignApk verify <apk>
 */
public final class SignApk {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("用法: SignApk sign|verify ...");
            System.exit(2);
        }
        if ("sign".equals(args[0])) {
            sign(args[1], args[2], args[3], args[4], new File(args[5]), new File(args[6]));
        } else if ("verify".equals(args[0])) {
            verify(new File(args[1]));
        } else {
            System.err.println("未知命令: " + args[0]);
            System.exit(2);
        }
    }

    private static void sign(String ksPath, String storePass, String keyPass, String alias,
                             File in, File out) throws Exception {
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        try (FileInputStream fis = new FileInputStream(ksPath)) {
            ks.load(fis, storePass.toCharArray());
        }
        PrivateKey key = (PrivateKey) ks.getKey(alias, keyPass.toCharArray());
        if (key == null) {
            throw new IllegalStateException("keystore 里没有别名 " + alias);
        }
        Certificate[] chain = ks.getCertificateChain(alias);
        List<X509Certificate> certs = new ArrayList<>();
        for (Certificate c : chain) {
            certs.add((X509Certificate) c);
        }

        ApkSigner.SignerConfig config = new ApkSigner.SignerConfig.Builder(alias, key, certs).build();
        new ApkSigner.Builder(java.util.Collections.singletonList(config))
                .setInputApk(in)
                .setOutputApk(out)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .build()
                .sign();
        System.out.println("已签名: " + out.getAbsolutePath() + " (" + (out.length() / 1024) + " KB)");
    }

    private static void verify(File apk) throws Exception {
        ApkVerifier.Result result = new ApkVerifier.Builder(apk).build().verify();
        if (!result.isVerified()) {
            System.err.println("验签失败: " + result.getErrors());
            System.exit(1);
        }
        StringBuilder sb = new StringBuilder("验签通过");
        sb.append(" · v1=").append(result.isVerifiedUsingV1Scheme());
        sb.append(" · v2=").append(result.isVerifiedUsingV2Scheme());
        sb.append(" · v3=").append(result.isVerifiedUsingV3Scheme());
        for (X509Certificate cert : result.getSignerCertificates()) {
            sb.append("\n  证书: ").append(cert.getSubjectDN().getName());
        }
        System.out.println(sb);
    }
}
