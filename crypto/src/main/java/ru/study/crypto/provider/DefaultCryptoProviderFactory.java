package ru.study.crypto.provider;

import ru.study.crypto.api.*;
import ru.study.crypto.impl.des.DesEcbCipher;
import ru.study.crypto.impl.keymgmt.KeyManagementImpl;
import ru.study.crypto.impl.md.MdDigest;
import ru.study.crypto.impl.rsa.RsaCipher;
import ru.study.crypto.impl.rsa.RsaSigner;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public class DefaultCryptoProviderFactory implements CryptoProviderFactory {

    private final Map<String, Supplier<SymmetricCipher>> sym = new ConcurrentHashMap<>();
    private final Map<String, Supplier<AsymmetricCipher>> asym = new ConcurrentHashMap<>();
    private final Map<String, Supplier<Digest>> digs = new ConcurrentHashMap<>();
    private final Map<String, Supplier<Signer>> signs = new ConcurrentHashMap<>();
    private final KeyManagement keyManagement;

    public DefaultCryptoProviderFactory() {
        // register defaults (can be extended by callers)
        sym.put("DES-ECB", DesEcbCipher::new);
        asym.put("RSA", RsaCipher::new);
        digs.put("MD5", MdDigest::new);
        signs.put("MD5withRSA", RsaSigner::new);

        this.keyManagement = new KeyManagementImpl();
    }

    // allow runtime registration (optional)
    public void registerSymmetric(String id, Supplier<SymmetricCipher> impl) { sym.put(id, impl); }
    public void registerAsymmetric(String id, Supplier<AsymmetricCipher> impl) { asym.put(id, impl); }
    public void registerDigest(String id, Supplier<Digest> impl) { digs.put(id, impl); }
    public void registerSigner(String id, Supplier<Signer> impl) { signs.put(id, impl); }

    @Override
    public SymmetricCipher getSymmetricCipher(String id) {
        Supplier<SymmetricCipher> s = sym.get(id);
        if (s == null) throw new IllegalArgumentException("Unknown symmetric cipher: " + id);
        return s.get();
    }

    @Override
    public AsymmetricCipher getAsymmetricCipher(String id) {
        Supplier<AsymmetricCipher> s = asym.get(id);
        if (s == null) throw new IllegalArgumentException("Unknown asymmetric cipher: " + id);
        return s.get();
    }

    @Override
    public Digest getDigest(String id) {
        Supplier<Digest> s = digs.get(id);
        if (s == null) throw new IllegalArgumentException("Unknown digest: " + id);
        return s.get();
    }

    @Override
    public Signer getSigner(String id) {
        Supplier<Signer> s = signs.get(id);
        if (s == null) throw new IllegalArgumentException("Unknown signer: " + id);
        return s.get();
    }

    @Override
    public KeyManagement getKeyManagement() {
        return keyManagement;
    }
}
