package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Signing in to Xbox on the iPhone, finished on the TV. The iPhone signs in (with its saved passwords) and sends
 * the signed-in session here, encrypted end to end: the TV makes a one-time key pair, the phone makes its own,
 * and both derive the same AES key (ECDH P-256 + HKDF-SHA256), so nothing readable crosses the network. The
 * session goes to Cloud Gaming, which only OtvOS (same signing key) may hand it to.
 */
final class XboxHandoff {

    private static final String TAG = "OtvXbox";
    private static final String INFO = "OpenTV Xbox sign-in";
    private static final long KEY_LIFE_MS = 10 * 60 * 1000;

    private static final class Pending {
        KeyPair pair;
        long made;
    }

    private static final Map<String, Pending> keys = new LinkedHashMap<>();

    private XboxHandoff() {
    }

    /** A one-time public key for the phone (DER, base64), and its id. */
    static JSONObject newKey() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        Pending p = new Pending();
        p.pair = g.generateKeyPair();
        p.made = System.currentTimeMillis();
        String id = UUID.randomUUID().toString();
        synchronized (keys) {
            keys.entrySet().removeIf(e -> System.currentTimeMillis() - e.getValue().made > KEY_LIFE_MS);
            keys.put(id, p);
        }
        return new JSONObject().put("id", id)
                .put("key", Base64.encodeToString(p.pair.getPublic().getEncoded(), Base64.NO_WRAP));
    }

    /** Opens the phone's sealed session and hands it to Cloud Gaming. Returns the gamertag, or "" if unknown. */
    static String receive(Context c, JSONObject in) throws Exception {
        Pending p;
        synchronized (keys) {
            p = keys.remove(in.getString("id"));   // each key opens one session, once
        }
        if (p == null || System.currentTimeMillis() - p.made > KEY_LIFE_MS) throw new SecurityException("key expired");
        PublicKey phone = KeyFactory.getInstance("EC").generatePublic(
                new X509EncodedKeySpec(Base64.decode(in.getString("key"), Base64.DEFAULT)));
        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(p.pair.getPrivate());
        ka.doPhase(phone, true);
        byte[] aes = hkdf(ka.generateSecret(), INFO.getBytes("UTF-8"));
        byte[] sealed = Base64.decode(in.getString("sealed"), Base64.DEFAULT);   // nonce (12) | ciphertext | tag (16)
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aes, "AES"), new GCMParameterSpec(128, sealed, 0, 12));
        String session = new String(cipher.doFinal(sealed, 12, sealed.length - 12), "UTF-8");
        JSONObject parsed = new JSONObject(session);
        if (parsed.optBoolean("test")) return "test-ok";   // a check that the encryption works, nothing handed over
        String tag = parsed.optString("gamertag", "");
        c.sendBroadcast(new Intent("local.cloudgaming.SESSION").setPackage("local.cloudgaming").putExtra("session", session),
                "local.cloudgaming.permission.SESSION");
        Log.i(TAG, "Xbox sign-in handed to Cloud Gaming");
        return tag;
    }

    /** HKDF-SHA256 with no salt (RFC 5869: a block of zeros), 32 bytes out: what CryptoKit derives. */
    private static byte[] hkdf(byte[] secret, byte[] info) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(new byte[32], "HmacSHA256"));
        byte[] prk = mac.doFinal(secret);
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        mac.update(info);
        mac.update((byte) 1);
        return mac.doFinal();
    }
}
