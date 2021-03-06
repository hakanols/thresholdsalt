// Copyright (c) 2018 ASSA ABLOY AB

package thresholdsalt;

import java.util.Arrays;

/**
 * Crypto library that enable dual signing and decryption (2 of 2) without the
 * secret keys never being in the same device. It also has signatures that is
 * compatible with TweetNaCl (EdDSA). The idea is that the end device that
 * validates a signature or encrypt a message dose not have to know that the the
 * public key it works on really is an addition of two public keys and that it
 * in fact are two devices that represent that public key.
 */
public class DualSalt {

    static final int scalarLength = TweetNaclFast.ScalarMult.scalarLength;
    static final int groupElementLength = TweetNaclFast.ScalarMult.groupElementLength;
    static final int signatureLength = TweetNaclFast.Signature.signatureLength;
    private static final int hashLength = TweetNaclFast.Hash.hashLength;
    public static final int seedLength = TweetNaclFast.Signature.seedLength;

    private static final int secretRandomLength = hashLength - scalarLength;
    public static final int publicKeyLength = groupElementLength;
    public static final int dualPublicKeyLength = signatureLength + publicKeyLength;
    public static final int secretKeyLength = scalarLength + publicKeyLength;
    public static final int dualSecretKeyLength = scalarLength + secretRandomLength + publicKeyLength;
    public static final int dualSignNonceLength = 32;
    private static final int m1HeaderLength = groupElementLength + publicKeyLength;
    private static final int m2Length = signatureLength;
    private static final int d1Length = groupElementLength;
    
    private static final byte[] encryptionNonce =
            TweetNaclFast.hexDecode("000000000000000000000000000000000000000000000000");
    private final static byte[] infinityElement =
            TweetNaclFast.hexDecode("0100000000000000000000000000000000000000000000000000000000000000");
    private final static byte[] maxElement =
            TweetNaclFast.hexDecode("edffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f");
    private final static byte[] scalarOrder =
            TweetNaclFast.hexDecode("edd3f55c1a631258d69cf7a2def9de1400000000000000000000000000000010");


    /**
     * Create key pair for single decryption and single sign. The secret key is compatible with TweetNaCl.
     * PublicKey is can be used in the same way as the public key created from the function addPublicKeyParts.
     * The publicKey can not be used in addPublicKeyParts and the secretKey is not compatible wit the dual signing and
     * dual decryption functions.
     *
     * @param publicKey
     *            (out) The created key pairs public key
     * @param secretKey
     *            (out) The created key pairs secret key
     * @param seed
     *            Random data used to create the key pair
     */
    public static void createSingleKeyPair(byte[] publicKey, byte[] secretKey, byte[] seed) {
        if (publicKey.length != publicKeyLength)
            throw new IllegalArgumentException("Public key has the wrong length");
        if (secretKey.length != secretKeyLength)
            throw new IllegalArgumentException("Secret key has the wrong length");
        if (seed.length != seedLength)
            throw new IllegalArgumentException("Random source has the wrong length");

        byte[] hash = new byte[hashLength];
        TweetNaclFast.crypto_hash(hash, seed, 0, seedLength);
        hash[0] &= 248;
        hash[31] &= 127;
        hash[31] |= 64;
        byte[] tempPublicKey = baseScalarMult(hash);

        System.arraycopy(tempPublicKey, 0, publicKey, 0, publicKeyLength);

        System.arraycopy(seed, 0, secretKey, 0, seedLength);
        System.arraycopy(tempPublicKey, 0, secretKey, seedLength, publicKeyLength);
    }

    /**
     * Create key pair for dual decryption and dual sign. The secret key is not compatible with single
     * sign and single decrypt. The public key part has to be combined with another public key part
     * in the function addPublicKeyParts
     *
     * @param publicKeyPart
     *            (out) The created key pairs public key
     * @param secretKey
     *            (out) The created key pairs secret key
     * @param seed
     *            Random data used to create the key pair
     */
    public static void createDualKeyPair(byte[] publicKeyPart, byte[] secretKey, byte[] seed) {
        if (publicKeyPart.length != dualPublicKeyLength)
            throw new IllegalArgumentException("Public key has the wrong length");
        if (secretKey.length != dualSecretKeyLength)
            throw new IllegalArgumentException("Secret key has the wrong length");
        if (seed.length != seedLength)
            throw new IllegalArgumentException("Random source has the wrong length");

        byte[] hash = new byte[hashLength];
        TweetNaclFast.crypto_hash(hash, seed, 0, seedLength);
        hash[31] &= 127;
        System.arraycopy(hash, 0, secretKey, 0, hashLength);

        byte[] tempPublicKey = baseScalarMult(Arrays.copyOfRange(secretKey, 0, scalarLength ));
        System.arraycopy(tempPublicKey, 0, secretKey, hashLength, publicKeyLength);

        byte[] random = Arrays.copyOfRange(hash, scalarLength, scalarLength+secretRandomLength);
        byte[] signature = signCreate(tempPublicKey, secretKey, random);

        System.arraycopy(signature, 0, publicKeyPart, 0, signatureLength);
        System.arraycopy(tempPublicKey, 0, publicKeyPart, signatureLength, publicKeyLength);
    }

    /**
     * Calculate group element the base point times the scalar.
     * 
     * @param scalar
     *            The scalar to calculate the group element from
     * @return Returns the group element
     */
    static byte[] baseScalarMult(byte[] scalar) {
        byte[] groupElement = new byte[groupElementLength];
        long[][] p = createUnpackedGroupElement();
        TweetNaclFast.scalarbase(p, scalar, 0);
        TweetNaclFast.pack(groupElement, p);
        return groupElement;
    }

    /*-
     * This function is used to "rotate" the two secret keys used to build up a
     * dual key (virtual key pair). The two key pairs kan be changed in such a
     * way that the addition of there two public keys still adds up to the same
     * value. Run rotateKey() on the first key pair with the parameter first sat
     * to true and then run rotateKey() on the second key pair with the param
     * first set to false. Reuse the same data for parameter random both time.
     * Parameter random has to be sent between devices in a encrypted
     * channel with forward secrecy such as saltChannel
     *
     * *****************************************
     * createDualKeyPair(A, a1, random())
     * createDualKeyPair(B, b1, random())
     * C = addPublicKeyParts(A, B)
     * rand = random()
     * a2 = rotateKey(a1, rand, true)
     * b2 = rotateKey(b1, rand, false)
     * // a2 and b2 can sign and decrypt for the same public key (C) as a1 and b1
     * *****************************************
     *
     * @param secretKey
     *            The earlier secret key
     * @param random
     *            Random for the key rotation. Reuse for both
     *            parts in a virtual key pair
     * @param first
     *            Shall be different between the to parts of the virtual key
     *            pair
     * @return Returns the resulting secret
     */
    public static byte[] rotateKey(byte[] secretKey, byte[] random, boolean first) {
        if (secretKey.length != dualSecretKeyLength)
            throw new IllegalArgumentException("Secret key has the wrong length");
        if (random.length != seedLength)
            throw new IllegalArgumentException("Random source has the wrong length");

        byte[] newSecretKey = new byte[dualSecretKeyLength];

        byte[] hash = new byte[hashLength];

        TweetNaclFast.crypto_hash(hash, random, 0, seedLength);
        byte[] scalarDiff = Arrays.copyOfRange(hash, 0,
                scalarLength);
        scalarDiff[31] &= 127;

        byte[] oldScalar = Arrays.copyOfRange(secretKey, 0,
                scalarLength);
        byte[] newScalar;
        if (first) {
            newScalar = addScalars(oldScalar, scalarDiff);
        } else {
            newScalar = subtractScalars(oldScalar, scalarDiff);
        }

        byte[] randSeed = new byte[secretRandomLength + secretRandomLength];
        System.arraycopy(secretKey, scalarLength, randSeed, 0, secretRandomLength);
        System.arraycopy(hash, secretRandomLength, randSeed, secretRandomLength, secretRandomLength);
        TweetNaclFast.crypto_hash(hash, randSeed, 0, randSeed.length);

        byte[] tempPublicKey = baseScalarMult(newScalar);

        System.arraycopy(newScalar, 0, newSecretKey, 0, scalarLength);
        System.arraycopy(hash, 0, newSecretKey, scalarLength, secretRandomLength);
        System.arraycopy(tempPublicKey, 0, newSecretKey, scalarLength + secretRandomLength, publicKeyLength);

        return newSecretKey;
    }

    /**
     * Add two scalar to each others
     * 
     * @param scalarA
     *            The first scalar
     * @param scalarB
     *            The second scalar
     * @return The result as a scalar
     */
    static byte[] addScalars(byte[] scalarA, byte[] scalarB) {
        int i;
        byte[] scalar = new byte[scalarLength];
        long[] temp = new long[64];
        for (i = 0; i < 64; i++)
            temp[i] = 0;
        for (i = 0; i < 32; i++)
            temp[i] = (long) (scalarA[i] & 0xff);
        for (i = 0; i < 32; i++)
            temp[i] += (long) (scalarB[i] & 0xff);

        TweetNaclFast.modL(scalar, 0, temp);

        return scalar;
    }

    /**
     * Subtract one scalar from another
     * 
     * @param scalarA
     *            A scalar
     * @param scalarB
     *            The scalar that is subtracted from the other
     * @return The result as a scalar
     */
    static byte[] subtractScalars(byte[] scalarA, byte[] scalarB) {
        int i;
        byte[] scalar = new byte[scalarLength];
        long[] temp = new long[64];
        for (i = 0; i < 64; i++)
            temp[i] = 0;
        for (i = 0; i < 32; i++)
            temp[i] = (long) (scalarA[i] & 0xff);
        for (i = 0; i < 32; i++)
            temp[i] -= (long) (scalarB[i] & 0xff);

        TweetNaclFast.modL(scalar, 0, temp);

        return scalar;
    }

    /**
     * Add two public key parts to each others.
     * 
     * @param publicKeyPartA
     *            The first public key part
     * @param publicKeyPartB
     *            The second public key part
     * @return The resulting public key
     */
    public static byte[] addPublicKeyParts(byte[] publicKeyPartA, byte[] publicKeyPartB){
        if (publicKeyPartA.length != dualPublicKeyLength)
            throw new IllegalArgumentException("One public key has the wrong length");
        if (publicKeyPartB.length != dualPublicKeyLength)
            throw new IllegalArgumentException("One public key has the wrong length");

        byte[] publicKeyA = Arrays.copyOfRange(publicKeyPartA, signatureLength, dualPublicKeyLength);
        byte[] publicKeyB = Arrays.copyOfRange(publicKeyPartB, signatureLength, dualPublicKeyLength);

        if (!signVerify(publicKeyPartA, publicKeyA))
            throw new IllegalArgumentException("publicKeyPartA do not validate correctly");
        if (!signVerify(publicKeyPartB, publicKeyB))
            throw new IllegalArgumentException("publicKeyPartB do not validate correctly");

        return addGroupElements(publicKeyA, publicKeyB);
    }

    static byte[] addGroupElements(byte[] elementA, byte[] elementB) {
        long[][] a = unpack(elementA);
        long[][] b = unpack(elementB);

        TweetNaclFast.add(a, b);

        byte[] elementAB = new byte[groupElementLength];
        TweetNaclFast.pack(elementAB, a);

        return elementAB;
    }

    /**
     * Subtract one group element from another.
     * 
     * @param elementA
     *            A public key
     * @param elementB
     *            The public key that is subtracted from the other
     * @return The result as a public key
     */
    private static byte[] subtractGroupElements(byte[] elementA, byte[] elementB) {
        byte[] temp = new byte[groupElementLength];
        System.arraycopy(elementB, 0, temp, 0, groupElementLength);
        temp[31] = (byte) (temp[31] ^ 0x80);
        return addGroupElements(elementA, temp);
    }

    /**
     * Creates an empty unpacked group element. Just for convenience
     * 
     * @return Empty unpacked group element
     */
    static long[][] createUnpackedGroupElement() {
        long[][] unpackedGroupElement = new long[4][];
        unpackedGroupElement[0] = new long[16];
        unpackedGroupElement[1] = new long[16];
        unpackedGroupElement[2] = new long[16];
        unpackedGroupElement[3] = new long[16];
        return unpackedGroupElement;
    }

    /**
     * Unpack group element. Uses unpackneg() from TweetNaclFast and changes the
     * sign
     * 
     * @param packedGroupElement
     *            The group element that is to be unpacked
     * @return The resulting unpacked group element
     */
    static long[][] unpack(byte[] packedGroupElement) {
        long[][] unpackedGroupElement = createUnpackedGroupElement();

        int result = TweetNaclFast.unpackneg(unpackedGroupElement, packedGroupElement);
        if (result != 0)
            throw new IllegalArgumentException("Group element can not be unpacked");

        // Change sign from neg to pos
        TweetNaclFast.Z(unpackedGroupElement[0], TweetNaclFast.gf0, unpackedGroupElement[0]);
        TweetNaclFast.M(unpackedGroupElement[3], unpackedGroupElement[0], unpackedGroupElement[1]);

        return unpackedGroupElement;
    }

    /**
     * Create a EdDSA signature.
     *
     * @param message
     *            The message to be signed.
     * @param secretKey
     *            The secret key of the signer
     * @return The signature
     */
    public static byte[] signCreate(byte[] message, byte[] secretKey) {
        if (message == null)
            throw new IllegalArgumentException("Message is null");
        if (secretKey.length != secretKeyLength)
            throw new IllegalArgumentException("Secret key has the wrong length");

        byte[] secretSeed = Arrays.copyOfRange(secretKey, 0, seedLength);
        byte[] hash = new byte[hashLength];
        TweetNaclFast.crypto_hash(hash, secretSeed, 0, seedLength);
        byte[] pseudoRandom = calculateRand(message, hash);
        byte[] singleSecretKey = new byte[dualSecretKeyLength];
        System.arraycopy(hash, 0, singleSecretKey, 0, hashLength);
        singleSecretKey[0] &= 248;
        singleSecretKey[31] &= 127;
        singleSecretKey[31] |= 64;
        System.arraycopy(secretKey, scalarLength, singleSecretKey, scalarLength + secretRandomLength, publicKeyLength);
        return signCreate(message, singleSecretKey, pseudoRandom);
    }

    /**
     * Inner function to create a EdDSA signature. Random as a param
     *
     * @param message
     *            The message to be signed.
     * @param secretKey
     *            The secret key of the signer in dualSecretKey format
     * @param random
     *            Random to be used in the signature
     * @return The signature
     */
    private static byte[] signCreate(byte[] message, byte[] secretKey, byte[] random) {

        byte[] publicKey = Arrays.copyOfRange(secretKey, scalarLength + secretRandomLength, dualSecretKeyLength);
        byte[] sign = new byte[m2Length + message.length];

        byte[] randomGroupElement = baseScalarMult(random);

        byte[] hash = calculateHash(randomGroupElement, publicKey, message);
        byte[] signature = calculateSignature(random, hash, secretKey);

        System.arraycopy(randomGroupElement, 0, sign, 0, groupElementLength);
        System.arraycopy(signature, 0, sign, groupElementLength,
                scalarLength);
        System.arraycopy(message, 0, sign, signatureLength, message.length);

        return sign;
    }

    /**
     * Verify a EdDSA signature.
     * 
     * @param signature
     *            The signature to be verified
     * @param publicKey
     *            The public key to verify the signature against
     * @return True if the signature is valid
     */
    public static boolean signVerify(byte[] signature, byte[] publicKey) {
        if (signature == null)
            throw new IllegalArgumentException("Signature is null");
        if (signature.length < signatureLength)
            throw new IllegalArgumentException("Signature is to short");
        if (publicKey.length != publicKeyLength)
            throw new IllegalArgumentException("Public key has the wrong length");

        byte[] tmp = new byte[signature.length];
        return TweetNaclFast.crypto_sign_open(tmp, 0, signature, 0, signature.length, publicKey) == 0;
    }

    /*-
     * The first of 3 functions that together creates one valid EdDSA signature
     * from two separate key pairs. Done is such a way that that two devices
     * with separate key pairs can sign without there key pairs ever existing in
     * the same device. Before this functions is executed the key pairs public
     * keys has to be added with addGroupElements() to get the virtualPublicKey. m1
     * and m2 is recommended to be sent in a encrypted channel with forward
     * secrecy such as saltChannel.
     *
     * *****************************************
     *     Device 1                Device 2
     *  signCreateDual1()             |
     *        |-----------m1--------> |
     *        |                 signCreateDual2()
     *        | <---------m2----------|
     *  signCreateDual3()             |
     * *****************************************
     *
     * @param message
     *            The message to be signed
     * @param virtualPublicKey
     *            The addition of the two key pairs public keys that shall sign
     *            the message.
     * @param secretKeyA
     *            The first secret key of the ones that shall sign
     * @param nonceA
     *            Have to be unique for each signing but is not secret. Shall be reused in signCreateDual3
     * @return m1 message to be used in signCreateDual2() and signCreateDual3()
     */
    public static byte[] signCreateDual1(byte[] message, byte[] secretKeyA, byte[] virtualPublicKey, byte[] nonceA) {
        if (message == null)
            throw new IllegalArgumentException("Message is null");
        if (virtualPublicKey.length != publicKeyLength)
            throw new IllegalArgumentException("Public key has the wrong length");
        if (nonceA.length != dualSignNonceLength)
            throw new IllegalArgumentException("Nonce has the wrong length");

        byte[] m1 = new byte[m1HeaderLength + message.length];

        byte[] randomA = calculateRand(message, secretKeyA, nonceA);
        byte[] randomGroupElA = baseScalarMult(randomA);

        System.arraycopy(virtualPublicKey, 0, m1, 0, publicKeyLength);
        System.arraycopy(randomGroupElA, 0, m1, publicKeyLength,
                groupElementLength);
        System.arraycopy(message, 0, m1, m1HeaderLength, message.length);
        return m1;
    }

    /**
     * See description in signCreateDual1()
     *
     * @param m1
     *            The m1 message from signCreateDual1
     * @param secretKeyB
     *            The second secret key of the ones that shall sign
     * @param nonceB
     *            Have to be unique for each signing but is not secret
     * @return m2 message to be used in signCreateDual3()
     */
    public static byte[] signCreateDual2(byte[] m1, byte[] secretKeyB, byte[] nonceB) {
        if (m1.length < m1HeaderLength)
            throw new IllegalArgumentException("M1 message is to short");
        if (secretKeyB.length != dualSecretKeyLength)
            throw new IllegalArgumentException("Secret key has the wrong length");
        if (nonceB.length != dualSignNonceLength)
            throw new IllegalArgumentException("Nonce has the wrong length");

        byte[] m2 = new byte[m2Length];
        byte[] virtualPublicKey = Arrays.copyOfRange(m1, 0, publicKeyLength);
        byte[] randomGroupElementA = Arrays.copyOfRange(m1, publicKeyLength, m1HeaderLength);
        byte[] message = Arrays.copyOfRange(m1, m1HeaderLength, m1.length);

        byte[] randomB = calculateRand(message, secretKeyB, nonceB);
        byte[] randomGroupElementB = baseScalarMult(randomB);
        byte[] randomGroupElement = addGroupElements(randomGroupElementA, randomGroupElementB);

        byte[] hash = calculateHash(randomGroupElement, virtualPublicKey, message);
        byte[] signatureB = calculateSignature(randomB, hash, secretKeyB);

        System.arraycopy(randomGroupElementB, 0, m2, 0, groupElementLength);
        System.arraycopy(signatureB, 0, m2, groupElementLength,
                scalarLength);
        return m2;
    }

    /**
     * See description in signCreateDual1()
     * 
     * @param m1
     *            The m1 message from signCreateDual1
     * @param m2
     *            The m2 message from signCreateDual2
     * @param secretKeyA
     *            The first secret key of the ones that shall sign
     * @param nonceA
     *            Reused form signCreateDual1
     * @return The signature
     */
    public static byte[] signCreateDual3(byte[] m1, byte[] m2, byte[] secretKeyA, byte[] nonceA) {
        if (m1.length < m1HeaderLength)
            throw new IllegalArgumentException("M1 message is to short");
        if (m2.length != m2Length)
            throw new IllegalArgumentException("M2 message has the wrong length");
        if (secretKeyA.length != dualSecretKeyLength)
            throw new IllegalArgumentException("Secret key has the wrong length");
        if (nonceA.length != dualSignNonceLength)
            throw new IllegalArgumentException("Nonce has the wrong length");

        byte[] virtualPublicKey = Arrays.copyOfRange(m1, 0, publicKeyLength);
        byte[] message = Arrays.copyOfRange(m1, m1HeaderLength, m1.length);
        byte[] randomGroupElementB = Arrays.copyOfRange(m2, 0,
                groupElementLength);
        byte[] signatureB = Arrays.copyOfRange(m2, groupElementLength,
                m2Length);
        byte[] publicKeyA = Arrays.copyOfRange(secretKeyA, scalarLength+secretRandomLength, dualSecretKeyLength);
        byte[] sign = new byte[signatureLength + message.length];

        byte[] randomA = calculateRand(message, secretKeyA, nonceA);
        byte[] randomGroupElementA = baseScalarMult(randomA);
        byte[] randomGroupElement = addGroupElements(randomGroupElementA, randomGroupElementB);

        byte[] hash = calculateHash(randomGroupElement, virtualPublicKey, message);
        byte[] publicKeyB = subtractGroupElements(virtualPublicKey, publicKeyA);
        if (!validateSignatureSpecial(publicKeyB, randomGroupElementB, signatureB, hash))
            throw new IllegalArgumentException("M2 do not validate correctly");

        byte[] signatureA = calculateSignature(randomA, hash, secretKeyA);
        byte[] signature = addScalars(signatureA, signatureB);

        System.arraycopy(randomGroupElement, 0, sign, 0, groupElementLength);
        System.arraycopy(signature, 0, sign, groupElementLength,
                scalarLength);
        System.arraycopy(message, 0, sign, signatureLength, message.length);
        return sign;
    }

    /**
     * Function used to create the pseudo random used used in a EdDSA signature used with single keypair signing.
     * 
     * @param message
     *            The signature message used as seed to the random
     * @param secretKey
     *            The secret key used as seed to the random
     * @return The pseudo random
     */
    private static byte[] calculateRand(byte[] message, byte[] secretKey) {
        byte[] tempBuffer = new byte[secretRandomLength + message.length];
        byte[] rand = new byte[hashLength];
        System.arraycopy(secretKey, scalarLength, tempBuffer, 0, secretRandomLength);
        System.arraycopy(message, 0, tempBuffer, secretRandomLength, message.length);
        TweetNaclFast.crypto_hash(rand, tempBuffer, 0, tempBuffer.length);
        TweetNaclFast.reduce(rand);
        return rand;
    }

    /**
     * Function used to create the pseudo random used used in a EdDSA signature used with dual keypair signing.
     *
     * @param message
     *            The signature message used as seed to the random
     * @param secretKey
     *            The secret key used as seed to the random
     * @param nonce
     *            Nonce used as seed to the random.
     * @return The pseudo random
     */
    private static byte[] calculateRand(byte[] message, byte[] secretKey, byte[] nonce) {
        byte[] tempBuffer = new byte[secretRandomLength + dualSignNonceLength + message.length];
        byte[] rand = new byte[hashLength];
        System.arraycopy(secretKey, scalarLength, tempBuffer, 0, secretRandomLength);
        System.arraycopy(nonce, 0, tempBuffer, dualSignNonceLength, secretRandomLength);
        System.arraycopy(message, 0, tempBuffer, secretRandomLength + dualSignNonceLength, message.length);
        TweetNaclFast.crypto_hash(rand, tempBuffer, 0, tempBuffer.length);
        TweetNaclFast.reduce(rand);
        return rand;
    }

    /**
     * Used to calculate the hash used in both verify and create EdDSA
     * signatures
     * 
     * @param randomGroupEl
     *            The pseudo random point used in the signature
     * @param publicKey
     *            The public key of the signature
     * @param message
     *            The message of the signature
     * @return The hash value.
     */
    static byte[] calculateHash(byte[] randomGroupEl, byte[] publicKey, byte[] message) {
        byte[] hash = new byte[hashLength];
        byte[] tempBuffer = new byte[groupElementLength + publicKeyLength
                + message.length];

        System.arraycopy(randomGroupEl, 0, tempBuffer, 0,
                groupElementLength);
        System.arraycopy(publicKey, 0, tempBuffer, groupElementLength,
                publicKeyLength);
        System.arraycopy(message, 0, tempBuffer, groupElementLength
                + publicKeyLength, message.length);
        TweetNaclFast.crypto_hash(hash, tempBuffer, 0, tempBuffer.length);

        TweetNaclFast.reduce(hash);
        return hash;
    }

    /**
     * The calculation of the scalars in a EdDSA signature
     * 
     * @param rand
     *            The pseudo random
     * @param hash
     *            The hash value
     * @param secretKey
     *            The secret key
     * @return The scalar to be included in the signature
     */
    static byte[] calculateSignature(byte[] rand, byte[] hash, byte[] secretKey) {
        byte[] signature = new byte[scalarLength];

        int i, j;
        long[] x = new long[64];
        for (i = 0; i < 64; i++)
            x[i] = 0;
        for (i = 0; i < 32; i++)
            x[i] = (long) (rand[i] & 0xff);
        for (i = 0; i < 32; i++)
            for (j = 0; j < 32; j++)
                x[i + j] += (hash[i] & 0xff) * (long) (secretKey[j] & 0xff);
        TweetNaclFast.modL(signature, 0, x);
        return signature;
    }

    /**
     * In signCreateDual3() the function validates m2. M2 is quite close to a
     * signature with the difference how the hash is calculated. So this
     * function do the exact same as a usual EdDSA verify dose with the
     * exception that the hash comes from a parameter.
     * 
     * @param publicKey
     *            The public key the signature sghall be validated agains
     * @param randomGroupEl
     *            The first part of the signature
     * @param signature
     *            The second part of the signature
     * @param hash
     *            The hash used in the validation
     * @return True if valid
     */
    protected static boolean validateSignatureSpecial(byte[] publicKey, byte[] randomGroupEl,
                                                      byte[] signature, byte[] hash) {
        long[][] p = createUnpackedGroupElement();
        long[][] q = createUnpackedGroupElement();
        byte[] t = new byte[groupElementLength];

        if (TweetNaclFast.unpackneg(q, publicKey) != 0)
            return false;
        TweetNaclFast.scalarmult(p, q, hash, 0);
        TweetNaclFast.scalarbase(q, signature, 0);
        TweetNaclFast.add(p, q);
        TweetNaclFast.pack(t, p);
        return TweetNaclFast.crypto_verify_32(randomGroupEl, 0, t, 0) == 0;
    }

    /**
     * Encryption a message with forward secrecy if random is forgotten. Uses
     * Ed25519
     * 
     * @param message
     *            The message to be encrypted
     * @param toPublicKey
     *            The public key to encrypt to
     * @param random
     *            Random
     * @return The cipher message
     */
    public static byte[] encrypt(byte[] message, byte[] toPublicKey, byte[] random) {
        if (message == null)
            throw new IllegalArgumentException("The message is null");
        if (toPublicKey.length != publicKeyLength)
            throw new IllegalArgumentException("Public key has the wrong length");
        if (random.length != seedLength)
            throw new IllegalArgumentException("Random seed has the wrong length");

        byte[] tempSecretKey = new byte[secretKeyLength];
        TweetNaclFast.crypto_hash(tempSecretKey, random, 0, seedLength);
        tempSecretKey[0] &= 248;
        tempSecretKey[31] &= 127;
        tempSecretKey[31] |= 64;
        byte[] tempPublicKey = baseScalarMult(tempSecretKey);

        byte[] sharedSecret = calculateSharedSecret(toPublicKey, tempSecretKey);
        byte[] cipherText = encryptWithSharedSecret(message, sharedSecret);

        byte[] cipherMessage = new byte[publicKeyLength + cipherText.length];
        System.arraycopy(tempPublicKey, 0, cipherMessage, 0, publicKeyLength);
        System.arraycopy(cipherText, 0, cipherMessage, publicKeyLength, cipherText.length);
        return cipherMessage;
    }

    /**
     * Decryption function
     * 
     * @param cipherMessage
     *            The cipher message
     * @param secretKey
     *            The secret key encrypted to
     * @return The decrypted message
     */
    public static byte[] decrypt(byte[] cipherMessage, byte[] secretKey) {
        if (cipherMessage.length <= publicKeyLength)
            throw new IllegalArgumentException("The cipher message is to short");
        if (secretKey.length != secretKeyLength)
            throw new IllegalArgumentException("Secret key has the wrong length");

        byte[] cipherText = Arrays.copyOfRange(cipherMessage, publicKeyLength,
                cipherMessage.length);

        byte[] tempSecretKey = new byte[secretKeyLength];
        TweetNaclFast.crypto_hash(tempSecretKey, secretKey, 0, seedLength);
        tempSecretKey[0] &= 248;
        tempSecretKey[31] &= 127;
        tempSecretKey[31] |= 64;

        byte[] sharedSecret = calculateSharedSecret(cipherMessage, tempSecretKey);
        return decryptWithSharedSecret(cipherText, sharedSecret);
    }

    /**
     * Check if field element is valid
     *
     * @param fieldElement
     *            the value to check
     * @return True if fieldElement is in range
     */
    private static boolean validFieldElement(byte[] fieldElement){
        byte[] tempFieldElement = fieldElement.clone();
        tempFieldElement[31] &= 0x7F;
        for (int i = 31; i >= 0; i--) {
            if ((maxElement[i]&0xFF) < (tempFieldElement[i]&0xFF)){
                return false;
            } else if ((maxElement[i]&0xFF) > (tempFieldElement[i]&0xFF)){
                return true;
            }
        }
        return false;
    }

    /**
     * Check if a element is in the group.
     * Test is taken from Chapter 3 in:
     * https://iacr.org/archive/pkc2003/25670211/25670211.pdf
     *
     * @param element
     *            Element to check
     * @return True if the element is in the group
     */
    private static boolean notInGroup(byte[] element){
        // 1. Element is not infinity
        if (Arrays.equals(element, infinityElement)){
            return true;
        }
        // 2. Element is [0,q-1] Can't be negative and not larger or equal to q
        if (!validFieldElement(element)){
            return true;
        }
        // 3. Skip test. Second coordinate is calculated from the first so it can not be wrong
        // 4. Element scalar multiplied with the order is infinity
        byte[] out = new byte[groupElementLength];
        long[][] p = createUnpackedGroupElement();
        long[][] q = unpack(element);
        TweetNaclFast.scalarmult(p, q, scalarOrder, 0);
        TweetNaclFast.pack(out, p);
        return !Arrays.equals(out, infinityElement);
    }

    /*-
     * The first of 2 functions that together can decrypt a cipher message from
     * encrypt() encrypted to an virtual key pair. d1 is recommended to be sent
     * in a encrypted channel with forward secrecy such as saltChannel
     *
     * *****************************************
     *    Device 1                Device 2
     *  decryptDual1()               |
     *       |-----------d1--------> |
     *       |                  decryptDual2()
     * *****************************************
     * 
     * @param cipherMessage
     *            The cipher message to be decrypted
     * @param secretKeyA
     *            The first secret key to be used in hte decryption
     * @return d1 a message used in decryptDual2() to finish the decryption
     */
    public static byte[] decryptDual1(byte[] cipherMessage, byte[] secretKeyA) {
        if (cipherMessage.length <= publicKeyLength)
            throw new IllegalArgumentException("The cipher message is to short");
        if (secretKeyA.length != dualSecretKeyLength)
            throw new IllegalArgumentException("Secret key has the wrong length");

        return calculateSharedSecret(cipherMessage, secretKeyA);
    }

    /**
     * See description in decryptDual1()
     * 
     * @param d1
     *            d1 a message from decryptDual1()
     * @param cipherMessage
     *            The cipher message to be decrypted
     * @param secretKeyB
     *            The second secret key to be used in hte decryption
     * @return The decrypted message
     */
    public static byte[] decryptDual2(byte[] d1, byte[] cipherMessage, byte[] secretKeyB) {
        if (d1.length != d1Length)
            throw new IllegalArgumentException("D1 has the wrong length");
        if (cipherMessage.length <= publicKeyLength)
            throw new IllegalArgumentException("The cipher message is to short");
        if (secretKeyB.length != dualSecretKeyLength)
            throw new IllegalArgumentException("Secret key has the wrong length");

        byte[] sharedSecret = new byte[groupElementLength];
        byte[] sharedSecretPart = calculateSharedSecret(cipherMessage, secretKeyB);
        long[][] p = unpack(sharedSecretPart);
        long[][] q = unpack(d1);
        TweetNaclFast.add(p, q);
        TweetNaclFast.pack(sharedSecret, p);

        return decryptWithSharedSecret(Arrays.copyOfRange(cipherMessage, publicKeyLength,
                cipherMessage.length), sharedSecret);
    }

    /**
     * Function to calculate the shared secret used in encryption decryption
     *
     * @param publicKey
     *            The cipher message starts with the public key so the cipherMessage can be used as parameter
     * @param secretScalar
     *            The secret scalar part of the secret key
     * @return The scared secret
     */
    static byte[] calculateSharedSecret(byte[] publicKey, byte[] secretScalar) {
        byte[] returnData = new byte[groupElementLength];
        byte[] tempPublicKey = Arrays.copyOfRange(publicKey, 0,
                groupElementLength);

        // Not needed in single decrypt and encrypt but left to reduce complexity
        if (notInGroup(tempPublicKey)){
            throw new IllegalArgumentException("Element not in group");
        }

        long[][] p = createUnpackedGroupElement();
        long[][] q = unpack(tempPublicKey);
        TweetNaclFast.scalarmult(p, q, secretScalar, 0);
        TweetNaclFast.pack(returnData, p);
        return returnData;
    }

    /**
     * Encrypt a message with a shared group element. A wrapper around the
     * TweetNaCl functions to not have to handel all buffers in the higher
     * layers
     * 
     * @param message
     *            Message to be encrypted
     * @param sharedSecret
     *            The shared group element used as key
     * @return The cipher text
     */
    private static byte[] encryptWithSharedSecret(byte[] message, byte[] sharedSecret) {
        byte[] sharedKey = new byte[TweetNaclFast.Box.sharedKeyLength];
        TweetNaclFast.crypto_core_hsalsa20(sharedKey, TweetNaclFast._0, sharedSecret,
                TweetNaclFast.sigma);

        byte[] messageBuffer = new byte[TweetNaclFast.Box.zerobytesLength + message.length];
        byte[] cipherBuffer = new byte[messageBuffer.length];
        System.arraycopy(message, 0, messageBuffer, TweetNaclFast.Box.zerobytesLength,
                message.length);

        TweetNaclFast.crypto_box_afternm(cipherBuffer, messageBuffer, messageBuffer.length, encryptionNonce,
                sharedKey);

        return Arrays.copyOfRange(cipherBuffer, TweetNaclFast.Box.boxzerobytesLength,
                cipherBuffer.length);
    }

    /**
     * Decrypt a cipher text with a shared group element. A wrapper around the
     * TweetNaCl functions to not have to handel all buffers in the higher
     * layers
     * 
     * @param cipherText
     *            Data to be decrypted
     * @param sharedSecret
     *            The shared group element used as key
     * @return The decrypted message
     */
    static byte[] decryptWithSharedSecret(byte[] cipherText, byte[] sharedSecret) {

        byte[] sharedKey = new byte[TweetNaclFast.Box.sharedKeyLength];
        TweetNaclFast.crypto_core_hsalsa20(sharedKey, TweetNaclFast._0, sharedSecret,
                TweetNaclFast.sigma);

        byte[] cipherBuffer = new byte[TweetNaclFast.Box.boxzerobytesLength + cipherText.length];
        byte[] messageBuffer = new byte[cipherBuffer.length];
        System.arraycopy(cipherText, 0, cipherBuffer, TweetNaclFast.Box.boxzerobytesLength,
                cipherText.length);

        if (TweetNaclFast.crypto_box_open_afternm(messageBuffer, cipherBuffer, cipherBuffer.length,
                encryptionNonce, sharedKey) != 0) {
            throw new IllegalArgumentException("Can not decrypt message");
        }

        return Arrays.copyOfRange(messageBuffer, TweetNaclFast.Box.zerobytesLength,
                messageBuffer.length);
    }
}
