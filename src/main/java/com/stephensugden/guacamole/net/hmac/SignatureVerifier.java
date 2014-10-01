package com.stephensugden.guacamole.net.hmac;

import com.sun.org.apache.xml.internal.security.utils.Base64;
//import org.apache.commons.codec.binary.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

public class SignatureVerifier {
    private final SecretKeySpec secretKey;

    private Logger logger = LoggerFactory.getLogger(SignatureVerifier.class);

    public SignatureVerifier(String secretKey) {
        this.secretKey = new SecretKeySpec(secretKey.getBytes(), "HmacSHA512");
    }

    public boolean verifySignature(String signature, String message) {
        try {
            Mac mac = createMac();
            String expected = Base64.encode(mac.doFinal(message.getBytes())).replace("\n","");
            logger.debug("verifySignature sign {}",signature);
            logger.debug("verifySignature expected {}",expected);
            logger.debug("verifySignature mess {}",message);
            return signature.equals(expected);
        } catch (InvalidKeyException e) {
            return false;
        } catch (NoSuchAlgorithmException e) {
            return false;
        }
    }

    Mac createMac() throws NoSuchAlgorithmException, InvalidKeyException {
        Mac mac = Mac.getInstance("HmacSHA512");
        mac.init(secretKey);
        return mac;
    }
}
