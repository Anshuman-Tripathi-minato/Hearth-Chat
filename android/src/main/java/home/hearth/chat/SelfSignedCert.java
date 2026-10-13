package home.hearth.chat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/**
 * Creates (once per install) an RSA key and a self-signed certificate so the room can be served over HTTPS.
 * Browsers only allow microphone and camera access on secure pages, which voice messages and calls need.
 */
final class SelfSignedCert {
    private static final char[] PASSWORD = "hearth-local".toCharArray();
    private static final String ALIAS = "hearth";

    private SelfSignedCert() {
    }

    static SSLContext sslContext(File directory) throws Exception {
        File file = new File(directory, "tls.p12");
        KeyStore store = KeyStore.getInstance("PKCS12");
        if (file.isFile()) {
            try (FileInputStream in = new FileInputStream(file)) {
                store.load(in, PASSWORD);
            } catch (Exception e) {
                store = null;
            }
        } else {
            store = null;
        }
        if (store == null || !store.containsAlias(ALIAS)) {
            store = create();
            File pending = new File(directory, "tls.p12.tmp");
            try (FileOutputStream out = new FileOutputStream(pending)) {
                store.store(out, PASSWORD);
            }
            if (!pending.renameTo(file)) pending.delete();
        }
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, PASSWORD);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keys.getKeyManagers(), null, new SecureRandom());
        return context;
    }

    static KeyStore create() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048, new SecureRandom());
        KeyPair pair = generator.generateKeyPair();

        byte[] algorithm = seq(oid(new int[]{1, 2, 840, 113549, 1, 1, 11}), new byte[]{0x05, 0x00});
        byte[] name = seq(set(seq(oid(new int[]{2, 5, 4, 3}), tlv(0x0c, "Hearth Chat home room".getBytes(StandardCharsets.UTF_8)))));
        long now = System.currentTimeMillis();
        byte[] validity = seq(utcTime(new Date(now - 24L * 3600 * 1000)), utcTime(new Date(now + 3650L * 24 * 3600 * 1000)));
        byte[] serial = tlv(0x02, new BigInteger(64, new SecureRandom()).abs().add(BigInteger.ONE).toByteArray());
        byte[] version = tlv(0xa0, tlv(0x02, new byte[]{2}));
        byte[] basicConstraints = seq(oid(new int[]{2, 5, 29, 19}), tlv(0x04, seq()));
        byte[] keyUsage = seq(oid(new int[]{2, 5, 29, 15}), tlv(0x01, new byte[]{(byte) 0xff}),
                tlv(0x04, tlv(0x03, new byte[]{5, (byte) 0xa0})));
        byte[] extendedKeyUsage = seq(oid(new int[]{2, 5, 29, 37}), tlv(0x04, seq(oid(new int[]{1, 3, 6, 1, 5, 5, 7, 3, 1}))));
        byte[] extensions = tlv(0xa3, seq(basicConstraints, keyUsage, extendedKeyUsage));
        byte[] tbs = seq(version, serial, algorithm, name, validity, name, pair.getPublic().getEncoded(), extensions);

        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(pair.getPrivate());
        signer.update(tbs);
        byte[] signature = signer.sign();
        byte[] bits = new byte[signature.length + 1];
        System.arraycopy(signature, 0, bits, 1, signature.length);
        byte[] der = seq(tbs, algorithm, tlv(0x03, bits));

        Certificate certificate = CertificateFactory.getInstance("X.509")
                .generateCertificate(new java.io.ByteArrayInputStream(der));
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        store.setKeyEntry(ALIAS, pair.getPrivate(), PASSWORD, new Certificate[]{certificate});
        return store;
    }

    private static byte[] utcTime(Date date) {
        SimpleDateFormat format = new SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return tlv(0x17, format.format(date).getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] oid(int[] parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(parts[0] * 40 + parts[1]);
        for (int i = 2; i < parts.length; i++) {
            int value = parts[i];
            byte[] chunk = new byte[5];
            int count = 0;
            do {
                chunk[count++] = (byte) (value & 0x7f);
                value >>>= 7;
            } while (value != 0);
            for (int j = count - 1; j >= 0; j--) out.write(chunk[j] | (j > 0 ? 0x80 : 0));
        }
        return tlv(0x06, out.toByteArray());
    }

    private static byte[] seq(byte[]... items) {
        return tlv(0x30, concat(items));
    }

    private static byte[] set(byte[]... items) {
        return tlv(0x31, concat(items));
    }

    private static byte[] concat(byte[]... items) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] item : items) out.write(item, 0, item.length);
        return out.toByteArray();
    }

    private static byte[] tlv(int tag, byte[] value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        int length = value.length;
        if (length < 0x80) {
            out.write(length);
        } else {
            int bytes = length > 0xffff ? 3 : length > 0xff ? 2 : 1;
            out.write(0x80 | bytes);
            for (int i = bytes - 1; i >= 0; i--) out.write((length >> (8 * i)) & 0xff);
        }
        out.write(value, 0, value.length);
        return out.toByteArray();
    }
}
