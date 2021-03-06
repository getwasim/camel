/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.converter.crypto;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.SecureRandom;
import java.security.Security;
import java.security.SignatureException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

import org.apache.camel.Exchange;
import org.apache.camel.converter.stream.CachedOutputStream;
import org.apache.camel.spi.DataFormat;
import org.apache.camel.support.ServiceSupport;
import org.apache.camel.util.ExchangeHelper;
import org.apache.camel.util.IOHelper;
import org.apache.camel.util.ObjectHelper;
import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.bcpg.CompressionAlgorithmTags;
import org.bouncycastle.bcpg.HashAlgorithmTags;
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openpgp.PGPCompressedData;
import org.bouncycastle.openpgp.PGPCompressedDataGenerator;
import org.bouncycastle.openpgp.PGPEncryptedDataGenerator;
import org.bouncycastle.openpgp.PGPEncryptedDataList;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPLiteralData;
import org.bouncycastle.openpgp.PGPLiteralDataGenerator;
import org.bouncycastle.openpgp.PGPObjectFactory;
import org.bouncycastle.openpgp.PGPOnePassSignature;
import org.bouncycastle.openpgp.PGPOnePassSignatureList;
import org.bouncycastle.openpgp.PGPPrivateKey;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPPublicKeyEncryptedData;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureGenerator;
import org.bouncycastle.openpgp.PGPSignatureList;
import org.bouncycastle.openpgp.PGPSignatureSubpacketGenerator;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentSignerBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentVerifierBuilderProvider;
import org.bouncycastle.openpgp.operator.jcajce.JcePGPDataEncryptorBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcePublicKeyDataDecryptorFactoryBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcePublicKeyKeyEncryptionMethodGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This PGP Data Format uses the interfaces {@link PGPPublicKeyAccessor} and
 * {@link PGPSecretKeyAccessor} to access the keys for encryption/signing and
 * decryption/signature verification. These interfaces allow caching of the keys
 * which can improve the performance.
 * <p>
 * If you want to provide the key access via keyrings in the format of a byte
 * array or file, then you should use the class {@link PGPDataFormat}.
 * 
 */
public class PGPKeyAccessDataFormat extends ServiceSupport implements DataFormat {

    public static final String KEY_USERID = "CamelPGPDataFormatKeyUserid";
    public static final String KEY_USERIDS = "CamelPGPDataFormatKeyUserids";
    public static final String SIGNATURE_KEY_USERID = "CamelPGPDataFormatSignatureKeyUserid";
    public static final String SIGNATURE_KEY_USERIDS = "CamelPGPDataFormatSignatureKeyUserids";
    public static final String ENCRYPTION_ALGORITHM = "CamelPGPDataFormatEncryptionAlgorithm";
    public static final String SIGNATURE_HASH_ALGORITHM = "CamelPGPDataFormatSignatureHashAlgorithm";
    public static final String COMPRESSION_ALGORITHM = "CamelPGPDataFormatCompressionAlgorithm";

    /**
     * During encryption the number of asymmectirc encryption keys is set to
     * this header parameter. The Value is of type Integer.
     */
    public static final String NUMBER_OF_ENCRYPTION_KEYS = "CamelPGPDataFormatNumberOfEncryptionKeys";
    /**
     * During signing the number of signing keys is set to this header
     * parameter. This corresponds to the number of signatures. The Value is of
     * type Integer.
     */
    public static final String NUMBER_OF_SIGNING_KEYS = "CamelPGPDataFormatNumberOfSigningKeys";

    private static final Logger LOG = LoggerFactory.getLogger(PGPKeyAccessDataFormat.class);
    

    private static final String BC = "BC";
    private static final int BUFFER_SIZE = 16 * 1024;
    
    PGPPublicKeyAccessor publicKeyAccessor;

    PGPSecretKeyAccessor secretKeyAccessor;

    // Java Cryptography Extension provider, default is Bouncy Castle
    private String provider = BC;

    // encryption / decryption key info (required)
    private String keyUserid; // only for encryption
    //in addition you can specify further User IDs, in this case the symmetric key is encrypted by several public keys corresponding to the User Ids
    private List<String> keyUserids; //only for encryption;

    // signature / verification key info (optional)
    private String signatureKeyUserid; // for signing and verification (optional for verification)
    //For verification you can specify further User IDs in addition
    private List<String> signatureKeyUserids; //only for signing with several keys and verifying;

    private boolean armored; // for encryption
    private boolean integrity = true; // for encryption

    private int hashAlgorithm = HashAlgorithmTags.SHA1; // for encryption

    private int algorithm = SymmetricKeyAlgorithmTags.CAST5; // for encryption

    private int compressionAlgorithm = CompressionAlgorithmTags.ZIP; // for encryption

    public PGPKeyAccessDataFormat() {
    }

    protected String findKeyUserid(Exchange exchange) {
        return exchange.getIn().getHeader(KEY_USERID, getKeyUserid(), String.class);
    }

    @SuppressWarnings("unchecked")
    protected List<String> findKeyUserids(Exchange exchange) {
        return exchange.getIn().getHeader(KEY_USERIDS, getKeyUserids(), List.class);
    }

    protected String findSignatureKeyUserid(Exchange exchange) {
        return exchange.getIn().getHeader(SIGNATURE_KEY_USERID, getSignatureKeyUserid(), String.class);
    }

    @SuppressWarnings("unchecked")
    protected List<String> findSignatureKeyUserids(Exchange exchange) {
        return exchange.getIn().getHeader(SIGNATURE_KEY_USERIDS, getSignatureKeyUserids(), List.class);
    }

    protected int findCompressionAlgorithm(Exchange exchange) {
        return exchange.getIn().getHeader(COMPRESSION_ALGORITHM, getCompressionAlgorithm(), Integer.class);
    }

    protected int findAlgorithm(Exchange exchange) {
        return exchange.getIn().getHeader(ENCRYPTION_ALGORITHM, getAlgorithm(), Integer.class);
    }

    protected int findHashAlgorithm(Exchange exchange) {
        return exchange.getIn().getHeader(SIGNATURE_HASH_ALGORITHM, getHashAlgorithm(), Integer.class);
    }

    public void marshal(Exchange exchange, Object graph, OutputStream outputStream) throws Exception {
        List<String> userids = determineEncryptionUserIds(exchange);
        List<PGPPublicKey> keys = publicKeyAccessor.getEncryptionKeys(exchange, userids);
        if (keys.isEmpty()) {
            throw new IllegalArgumentException("Cannot PGP encrypt message. No public encryption key found for the User Ids " + userids
                    + " in the public keyring. Either specify other User IDs or add correct public keys to the keyring.");
        }
        exchange.getOut().setHeader(NUMBER_OF_ENCRYPTION_KEYS, Integer.valueOf(keys.size()));

        InputStream input = ExchangeHelper.convertToMandatoryType(exchange, InputStream.class, graph);

        if (armored) {
            outputStream = new ArmoredOutputStream(outputStream);
        }

        PGPEncryptedDataGenerator encGen = new PGPEncryptedDataGenerator(new JcePGPDataEncryptorBuilder(findAlgorithm(exchange))
                .setWithIntegrityPacket(integrity).setSecureRandom(new SecureRandom()).setProvider(getProvider()));
        // several keys can be added
        for (PGPPublicKey key : keys) {
            encGen.addMethod(new JcePublicKeyKeyEncryptionMethodGenerator(key));
        }
        OutputStream encOut = encGen.open(outputStream, new byte[BUFFER_SIZE]);

        PGPCompressedDataGenerator comData = new PGPCompressedDataGenerator(findCompressionAlgorithm(exchange));
        OutputStream comOut = new BufferedOutputStream(comData.open(encOut));

        List<PGPSignatureGenerator> sigGens = createSignatureGenerator(exchange, comOut);

        PGPLiteralDataGenerator litData = new PGPLiteralDataGenerator();
        String fileName = exchange.getIn().getHeader(Exchange.FILE_NAME, String.class);
        if (ObjectHelper.isEmpty(fileName)) {
            // This marks the file as For Your Eyes Only... may cause problems for the receiver if they use
            // an automated process to decrypt as the filename is appended with _CONSOLE
            fileName = PGPLiteralData.CONSOLE;
        }
        OutputStream litOut = litData.open(comOut, PGPLiteralData.BINARY, fileName, new Date(), new byte[BUFFER_SIZE]);

        try {
            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;
            while ((bytesRead = input.read(buffer)) != -1) {
                litOut.write(buffer, 0, bytesRead);
                if (sigGens != null && !sigGens.isEmpty()) {
                    for (PGPSignatureGenerator sigGen : sigGens) {
                        // not nested therefore it is the same for all
                        // can this be improved that we only do it for one sigGen and set the result on the others?
                        sigGen.update(buffer, 0, bytesRead);
                    }
                }
                litOut.flush();
            }
        } finally {
            IOHelper.close(litOut);
            if (sigGens != null && !sigGens.isEmpty()) {
                // reverse order
                for (int i = sigGens.size() - 1; i > -1; i--) {
                    PGPSignatureGenerator sigGen = sigGens.get(i);
                    sigGen.generate().encode(comOut);
                }
            }
            IOHelper.close(comOut, encOut, outputStream, input);
        }
    }

    protected List<String> determineEncryptionUserIds(Exchange exchange) {
        String userid = findKeyUserid(exchange);
        List<String> userids = findKeyUserids(exchange);
        // merge them together
        List<String> result;
        if (userid != null) {
            if (userids == null || userids.isEmpty()) {
                result = Collections.singletonList(userid);
            } else {
                result = new ArrayList<String>(userids.size() + 1);
                result.add(userid);
                result.addAll(userids);
            }
        } else {
            if (userids == null || userids.isEmpty()) {
                throw new IllegalStateException("Cannot PGP encrypt message. No User ID of the public key specified.");
            }
            result = userids;
        }
        return result;
    }

    protected List<String> determineSignaturenUserIds(Exchange exchange) {
        String userid = findSignatureKeyUserid(exchange);
        List<String> userids = findSignatureKeyUserids(exchange);
        // merge them together
        List<String> result;
        if (userid != null) {
            if (userids == null || userids.isEmpty()) {
                result = Collections.singletonList(userid);
            } else {
                result = new ArrayList<String>(userids.size() + 1);
                result.add(userid);
                result.addAll(userids);
            }
        } else {
            // userids can be empty or null!
            result = userids;
        }
        return result;
    }

    protected List<PGPSignatureGenerator> createSignatureGenerator(Exchange exchange, OutputStream out) throws Exception {

        if (secretKeyAccessor == null) {
            return null;
        }

        List<String> sigKeyUserids = determineSignaturenUserIds(exchange);
        List<PGPSecretKeyAndPrivateKeyAndUserId> sigSecretKeysWithPrivateKeyAndUserId = secretKeyAccessor.getSignerKeys(exchange,
                sigKeyUserids);
        if (sigSecretKeysWithPrivateKeyAndUserId.isEmpty()) {
            return null;
        }

        exchange.getOut().setHeader(NUMBER_OF_SIGNING_KEYS, Integer.valueOf(sigSecretKeysWithPrivateKeyAndUserId.size()));

        List<PGPSignatureGenerator> sigGens = new ArrayList<PGPSignatureGenerator>();
        for (PGPSecretKeyAndPrivateKeyAndUserId sigSecretKeyWithPrivateKeyAndUserId : sigSecretKeysWithPrivateKeyAndUserId) {
            PGPPrivateKey sigPrivateKey = sigSecretKeyWithPrivateKeyAndUserId.getPrivateKey();

            PGPSignatureSubpacketGenerator spGen = new PGPSignatureSubpacketGenerator();
            spGen.setSignerUserID(false, sigSecretKeyWithPrivateKeyAndUserId.getUserId());

            int algorithm = sigSecretKeyWithPrivateKeyAndUserId.getSecretKey().getPublicKey().getAlgorithm();
            PGPSignatureGenerator sigGen = new PGPSignatureGenerator(
                    new JcaPGPContentSignerBuilder(algorithm, findHashAlgorithm(exchange)).setProvider(getProvider()));
            sigGen.init(PGPSignature.BINARY_DOCUMENT, sigPrivateKey);
            sigGen.setHashedSubpackets(spGen.generate());
            sigGen.generateOnePassVersion(false).encode(out);
            sigGens.add(sigGen);
        }
        return sigGens;
    }

    @SuppressWarnings("resource")
    public Object unmarshal(Exchange exchange, InputStream encryptedStream) throws Exception {
        if (encryptedStream == null) {
            return null;
        }
        InputStream in = PGPUtil.getDecoderStream(encryptedStream);
        PGPObjectFactory pgpFactory = new PGPObjectFactory(in);
        Object o = pgpFactory.nextObject();
        // the first object might be a PGP marker packet 
        PGPEncryptedDataList enc;
        if (o instanceof PGPEncryptedDataList) {
            enc = (PGPEncryptedDataList) o;
        } else {
            enc = (PGPEncryptedDataList) pgpFactory.nextObject();
        }

        PGPPublicKeyEncryptedData pbe = null;
        PGPPrivateKey key = null;
        // find encrypted data for which a private key exists in the secret key ring
        for (int i = 0; i < enc.size() && key == null; i++) {
            pbe = (PGPPublicKeyEncryptedData) enc.get(i);
            key = secretKeyAccessor.getPrivateKey(exchange, pbe.getKeyID());
            if (key != null) {
                // take the first key
                break;
            }
        }
        if (key == null) {
            throw new PGPException("Provided input is encrypted with unknown pair of keys.");
        }

        InputStream encData = pbe.getDataStream(new JcePublicKeyDataDecryptorFactoryBuilder().setProvider(getProvider()).build(key));
        pgpFactory = new PGPObjectFactory(encData);
        PGPCompressedData comData = (PGPCompressedData) pgpFactory.nextObject();
        pgpFactory = new PGPObjectFactory(comData.getDataStream());
        Object object = pgpFactory.nextObject();

        PGPOnePassSignature signature;
        if (object instanceof PGPOnePassSignatureList) {
            signature = getSignature(exchange, (PGPOnePassSignatureList) object);
            object = pgpFactory.nextObject();
        } else {
            signature = null;
        }

        PGPLiteralData ld = (PGPLiteralData) object;
        InputStream litData = ld.getInputStream();

        // enable streaming via OutputStreamCache
        CachedOutputStream cos;
        ByteArrayOutputStream bos;
        OutputStream os;
        if (exchange.getContext().getStreamCachingStrategy().isEnabled()) {
            cos = new CachedOutputStream(exchange);
            bos = null;
            os = cos;
        } else {
            cos = null;
            bos = new ByteArrayOutputStream();
            os = bos;
        }

        try {
            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;
            while ((bytesRead = litData.read(buffer)) != -1) {
                os.write(buffer, 0, bytesRead);
                if (signature != null) {
                    signature.update(buffer, 0, bytesRead);
                }
                os.flush();
            }
        } finally {
            IOHelper.close(os, litData, encData, in);
        }

        if (signature != null) {
            PGPSignatureList sigList = (PGPSignatureList) pgpFactory.nextObject();
            if (!signature.verify(getSignatureWithKeyId(signature.getKeyID(), sigList))) {
                throw new SignatureException("Cannot verify PGP signature");
            }
        }

        if (cos != null) {
            return cos.newStreamCache();
        } else {
            return bos.toByteArray();
        }
    }

    protected PGPSignature getSignatureWithKeyId(long keyID, PGPSignatureList sigList) {
        for (int i = 0; i < sigList.size(); i++) {
            PGPSignature signature = sigList.get(i);
            if (keyID == signature.getKeyID()) {
                return signature;
            }
        }
        throw new IllegalStateException("PGP signature is inconsistent");
    }

    protected PGPOnePassSignature getSignature(Exchange exchange, PGPOnePassSignatureList signatureList) throws Exception {

        List<String> allowedUserIds = determineSignaturenUserIds(exchange);
        for (int i = 0; i < signatureList.size(); i++) {
            PGPOnePassSignature signature = signatureList.get(i);
            // Determine public key from signature keyId
            PGPPublicKey sigPublicKey = publicKeyAccessor.getPublicKey(exchange, signature.getKeyID());
            if (sigPublicKey == null) {
                continue;
            }
            if (isAllowedVerifyingKey(allowedUserIds, sigPublicKey)) {
                // choose that signature for which a public key exists!
                signature.init(new JcaPGPContentVerifierBuilderProvider().setProvider(getProvider()), sigPublicKey);
                return signature;
            }
        }
        if (signatureList.isEmpty()) {
            return null;
        } else {
            throw new IllegalArgumentException("No public key found fitting to the signature key Id; cannot verify the signature");
        }

    }

    public boolean isAllowedVerifyingKey(List<String> allowedUserIds, PGPPublicKey verifyingPublicKey) {

        if (allowedUserIds == null || allowedUserIds.isEmpty()) {
            // no restrictions specified
            return true;
        }
        String keyUserId = null;
        for (@SuppressWarnings("unchecked")
        Iterator<String> iterator = verifyingPublicKey.getUserIDs(); iterator.hasNext();) {
            keyUserId = iterator.next();
            for (String userid : allowedUserIds) {
                if (keyUserId != null && keyUserId.contains(userid)) {
                    LOG.debug(
                            "Public key with  user ID {} fulfills the User ID restriction {}. Therefore this key will be used for the signature verification. ",
                            keyUserId, allowedUserIds);
                    return true;
                }
            }
        }
        LOG.warn(
                "Public key with User ID {} does not fulfill the User ID restriction {}. Therefore this key will not be used for the signature verification.",
                keyUserId, allowedUserIds);
        return false;
    }

    /**
     * Sets if the encrypted file should be written in ascii visible text (for
     * marshaling).
     */
    public void setArmored(boolean armored) {
        this.armored = armored;
    }

    public boolean getArmored() {
        return this.armored;
    }

    /**
     * Whether or not to add an integrity check/sign to the encrypted file for
     * marshaling.
     */
    public void setIntegrity(boolean integrity) {
        this.integrity = integrity;
    }

    public boolean getIntegrity() {
        return this.integrity;
    }

    /**
     * User ID, or more precisely user ID part, of the key used for encryption.
     * See also {@link #setKeyUserids(List<String>)}.
     */
    public void setKeyUserid(String keyUserid) {
        this.keyUserid = keyUserid;
    }

    public String getKeyUserid() {
        return keyUserid;
    }

    public List<String> getKeyUserids() {
        return keyUserids;
    }

    /**
     * Keys User IDs, or more precisely user ID parts, used for determining the
     * public keys for encryption. If you just have one User ID, then you can
     * also use the method {@link #setKeyUserid(String)}. The User ID specified
     * in {@link #setKeyUserid(String)} and in this method will be merged
     * together and the public keys which have a User ID which contain a value
     * of the specified User IDs the will be used for the encryption. Be aware
     * that you may get several public keys even if you specify only one User
     * Id, because there can be several public keys which have a User ID which
     * contains the specified User ID.
     */
    public void setKeyUserids(List<String> keyUserids) {
        this.keyUserids = keyUserids;
    }

    /**
     * Userid, or more precisely user ID part, of the signature key used for
     * signing (marshal) and verifying (unmarshal). See also
     * {@link #setSignatureKeyUserids(List)}.
     */
    public void setSignatureKeyUserid(String signatureKeyUserid) {
        this.signatureKeyUserid = signatureKeyUserid;
    }

    public String getSignatureKeyUserid() {
        return signatureKeyUserid;
    }

    public List<String> getSignatureKeyUserids() {
        return signatureKeyUserids;
    }

    /**
     * User IDs, or more precisely user ID parts, used for signing and
     * verification.
     * <p>
     * In the signing case, the User IDs specify the private keys which are used
     * for signing. If the result are several private keys then several
     * signatures will be created. If you just have one signature User ID, then
     * you can also use the method {@link #setSignatureKeyUserid(String)} or
     * this method. The User ID specified in
     * {@link #setSignatureKeyUserid(String)} and in this method will be merged
     * together and the private keys which have a User Id which contain one
     * value out of the specified UserIds will be used for the signature
     * creation. Be aware that you may get several private keys even if you
     * specify only one User Id, because there can be several private keys which
     * have a User ID which contains the specified User ID.
     * <p>
     * In the verification case the User IDs restrict the set of public keys
     * which can be used for verification. The public keys used for verification
     * must contain a User ID which contain one value of the User ID list. If
     * you neither specify in this method and nor specify in the method
     * {@link #setSignatureKeyUserid(String)} any value then any public key in
     * the public key ring will be taken into consideration for the
     * verification.
     * <p>
     * If you just have one User ID, then you can also use the method
     * {@link #setSignatureKeyUserid(String)}. The User ID specified in
     * {@link #setSignatureKeyUserid(String)} and in this method will be merged
     * together and the corresponding public keys represent the potential keys
     * for the verification of the message.
     */
    public void setSignatureKeyUserids(List<String> signatureKeyUserids) {
        this.signatureKeyUserids = signatureKeyUserids;
    }

    public String getProvider() {
        return provider;
    }

    /**
     * Java Cryptography Extension (JCE) provider, default is Bouncy Castle
     * ("BC"). Alternatively you can use, for example, the IAIK JCE provider; in
     * this case the provider must be registered beforehand and the Bouncy
     * Castle provider must not be registered beforehand. The Sun JCE provider
     * does not work.
     */
    public void setProvider(String provider) {
        this.provider = provider;
    }

    public int getCompressionAlgorithm() {
        return compressionAlgorithm;
    }

    /**
     * Compression algorithm used during marshaling. Possible values are defined
     * in {@link CompressionAlgorithmTags}. Default value is ZIP.
     */
    public void setCompressionAlgorithm(int compressionAlgorithm) {
        this.compressionAlgorithm = compressionAlgorithm;
    }

    public int getHashAlgorithm() {
        return hashAlgorithm;
    }

    /**
     * Digest algorithm for signing (marshaling). Possible values are defined in
     * {@link HashAlgorithmTags}. Default value is SHA1.
     */
    public void setHashAlgorithm(int hashAlgorithm) {
        this.hashAlgorithm = hashAlgorithm;
    }

    public int getAlgorithm() {
        return algorithm;
    }

    /**
     * Symmetric key algorithm for encryption (marshaling). Possible values are
     * defined in {@link SymmetricKeyAlgorithmTags}. Default value is CAST5.
     */
    public void setAlgorithm(int algorithm) {
        this.algorithm = algorithm;
    }

    public PGPPublicKeyAccessor getPublicKeyAccessor() {
        return publicKeyAccessor;
    }

    public void setPublicKeyAccessor(PGPPublicKeyAccessor publicKeyAccessor) {
        this.publicKeyAccessor = publicKeyAccessor;
    }

    public PGPSecretKeyAccessor getSecretKeyAccessor() {
        return secretKeyAccessor;
    }

    public void setSecretKeyAccessor(PGPSecretKeyAccessor secretKeyAccessor) {
        this.secretKeyAccessor = secretKeyAccessor;
    }

    @Override
    protected void doStart() throws Exception {
        if (Security.getProvider(BC) == null && BC.equals(getProvider())) {
            LOG.debug("Adding BouncyCastleProvider as security provider");
            Security.addProvider(new BouncyCastleProvider());
        } else {
            LOG.debug("Using custom provider {} which is expected to be enlisted manually.", getProvider());
        }
    }

    @Override
    protected void doStop() throws Exception {
        // noop
    }
}
