package local.asset.assistant;

import android.app.Instrumentation;
import android.content.*;
import android.os.Bundle;
import java.io.File;
import java.util.*;

/** Runs in a separate cache directory, never touches the user's ledger or credentials. */
public final class SmokeTest extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    private void check(boolean v, String message) { if (!v) throw new AssertionError(message); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            String run = "qa-" + UUID.randomUUID();
            Context isolated = new ContextWrapper(getTargetContext()) {
                @Override public File getFilesDir() { File f = new File(super.getCacheDir(), run); f.mkdirs(); return f; }
                @Override public SharedPreferences getSharedPreferences(String name, int mode) { return super.getSharedPreferences(run + "-" + name, mode); }
            };
            BookStore book = new BookStore(isolated);
            var entry = new Ledger.Event("qa-one", 1, "期初", "验证数据", List.of(new Ledger.Posting("未分配", "USDT", "100.123456789123456789")));
            check(book.merge(List.of(entry)) == 1, "首次入账");
            String backup = book.export();
            check(new BookStore(isolated).export().equals(backup), "重启精确恢复");
            check(book.importBook(backup) == 0, "重复备份导入去重");
            check(new File(isolated.getFilesDir(), "before-import.json").exists(), "导入前备份");
            try { book.importBook(backup.replace("100.123456789123456789", "200")); throw new AssertionError("应拒绝冲突"); } catch (IllegalArgumentException expected) {}
            check(book.export().equals(backup), "拒绝冲突后原账本不变");
            Vault vault = new Vault(isolated);
            String key = "QATESTONLY000000000000000000000001", secret = "QATESTONLY000000000000000000000002";
            vault.save(key, secret);
            check(Arrays.equals(vault.read(), new String[]{key, secret}), "Keystore 加解密");
            check(!isolated.getSharedPreferences("vault", 0).getString("cipher", "").contains(secret), "磁盘存储密文");
            try { vault.save("QATESTONLY000000000000000000000003", secret); throw new AssertionError("应拒绝混合身份"); } catch (IllegalArgumentException expected) {}
            check(Arrays.equals(vault.read(), new String[]{key, secret}), "拒绝替换后保留原凭据");
            SyncTests.run(isolated);
            OpportunityTests.run(isolated);
            result.putString("stream", "PASS: persistence, exact decimals, backup, deduplication, conflict rollback, Android Keystore encryption, identity isolation, transaction ingestion, fee deduplication, canceled exclusion, pagination coverage, offline preservation, full restore, wallet completeness, opportunity crossings, multi-stage signals, pause/resume, cycle completion, stale/disputed data exclusion\n");
            finish(-1, result);
        } catch (Throwable e) { result.putString("stream", "FAIL: " + e.getClass().getSimpleName() + ": " + e.getMessage() + "\n"); finish(1, result); }
    }
}
