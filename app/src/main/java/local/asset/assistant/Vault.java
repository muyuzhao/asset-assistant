package local.asset.assistant;

import android.content.Context;
import android.security.keystore.*;
import android.util.Base64;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import java.security.KeyStore;
import java.nio.charset.StandardCharsets;

final class Vault {
    private static final String ALIAS = "asset-assistant-api-v1";
    private final Context context;
    Vault(Context context) { this.context = context; }
    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (!store.containsAlias(ALIAS)) {
            KeyGenerator generator = KeyGenerator.getInstance("AES", "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        return (SecretKey) store.getKey(ALIAS, null);
    }
    boolean exists() { return context.getSharedPreferences("vault", 0).contains("cipher"); }
    void save(String api, String secret) throws Exception {
        if (!api.matches("[A-Za-z0-9]{16,256}") || !secret.matches("[A-Za-z0-9]{16,256}")) throw new IllegalArgumentException("请输入系统生成的 HMAC API Key 和 Secret");
        if (exists() && !read()[0].equals(api)) throw new IllegalArgumentException("首版仅支持一个 API 身份。更换账号前请先导出账本；当前不可覆盖已有 API Key，以免混合不同账户的记录。");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key());
        String encoded = Base64.encodeToString(cipher.doFinal((api + "\n" + secret).getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
        if (!context.getSharedPreferences("vault", 0).edit().putString("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)).putString("cipher", encoded).commit()) throw new IllegalStateException("凭据保存失败");
    }
    String[] read() throws Exception {
        var prefs = context.getSharedPreferences("vault", 0);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(prefs.getString("iv", ""), Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(prefs.getString("cipher", ""), Base64.NO_WRAP)), StandardCharsets.UTF_8).split("\n", -1);
    }
}
