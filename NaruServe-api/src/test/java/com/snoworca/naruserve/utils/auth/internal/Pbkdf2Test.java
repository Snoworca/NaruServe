package com.snoworca.naruserve.utils.auth.internal;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.nio.charset.StandardCharsets;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.spec.AlgorithmParameterSpec;
import java.util.Arrays;
import javax.crypto.Mac;
import javax.crypto.MacSpi;
import org.junit.Test;

/** The hand-written PBKDF2-HMAC-SHA256 (SEC-TOKEN-007). */
public class Pbkdf2Test {

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }

    // SEC-TOKEN-007 AC-1: the PBKDF2-HMAC-SHA256 vectors of RFC 7914 section 11, byte for byte
    @Test
    public void SEC_TOKEN_007_AC1_rfc7914Vectors() {
        assertArrayEquals(hex("55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc"
                + "49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783"),
                Pbkdf2.derive("passwd".toCharArray(), "salt".getBytes(StandardCharsets.US_ASCII), 1, 64));
        assertArrayEquals(hex("4ddcd8f60b98be21830cee5ef22701f9641a4418d04c0414aeff08876b34ab56"
                + "a1d425a1225833549adb841b51c9b3176a272bdebba1d078478f62b397f33c8d"),
                Pbkdf2.derive("Password".toCharArray(), "NaCl".getBytes(StandardCharsets.US_ASCII), 80000, 64));
    }

    /** A real HmacSHA256 that counts how often its key is set. */
    static final class CountingSpi extends MacSpi {
        int inits;
        private final Mac real;

        CountingSpi() throws Exception {
            real = Mac.getInstance("HmacSHA256");
        }

        @Override
        protected int engineGetMacLength() {
            return real.getMacLength();
        }

        @Override
        protected void engineInit(Key key, AlgorithmParameterSpec params)
                throws InvalidKeyException, InvalidAlgorithmParameterException {
            inits++;
            real.init(key, params);
        }

        @Override
        protected void engineUpdate(byte input) {
            real.update(input);
        }

        @Override
        protected void engineUpdate(byte[] input, int offset, int len) {
            real.update(input, offset, len);
        }

        @Override
        protected byte[] engineDoFinal() {
            return real.doFinal();
        }

        @Override
        protected void engineReset() {
            real.reset();
        }
    }

    // SEC-TOKEN-007 AC-5: a 1,000-character password sets the HMAC key once per derivation, not once per iteration
    @Test
    public void SEC_TOKEN_007_AC5_longPasswordInitialisesTheKeyOnce() throws Exception {
        char[] longPassword = new char[1000];
        Arrays.fill(longPassword, 'p');
        CountingSpi spi = new CountingSpi();
        Mac counting = new Mac(spi, null, "HmacSHA256") {
        };
        byte[] salt = new byte[16];
        byte[] counted = Pbkdf2.derive(longPassword, salt, 5000, 32, counting);
        assertEquals(1, spi.inits);
        assertArrayEquals(Pbkdf2.derive(longPassword, salt, 5000, 32), counted);
    }
}
