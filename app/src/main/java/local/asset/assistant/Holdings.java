package local.asset.assistant;
import org.json.*;
import java.math.BigDecimal;
import java.util.*;

final class Holdings {
    record Result(Map<String, BigDecimal> quantities, BigDecimal valuation, List<String> missing, List<String> walletNames) {}
    static Result parse(JSONArray wallets) throws Exception {
        Map<String, BigDecimal> quantities = new TreeMap<>(); BigDecimal value = BigDecimal.ZERO;
        List<String> missing = new ArrayList<>(), names = new ArrayList<>(); Set<String> seen = new HashSet<>();
        for (int i=0; i<wallets.length(); i++) {
            JSONObject wallet = wallets.getJSONObject(i); String name = wallet.getString("walletName");
            if (!seen.add(name)) throw new IllegalArgumentException("钱包重复，拒绝重复合计");
            if (!wallet.getBoolean("activate")) continue;
            names.add(name); value = value.add(Ledger.decimal(wallet.getString("balance")));
            JSONArray rows = wallet.optJSONArray("assetBalances");
            if (rows == null) { missing.add(name); continue; }
            Set<String> unique = new HashSet<>();
            for (int j=0; j<rows.length(); j++) {
                JSONObject row = rows.getJSONObject(j); String asset = row.getString("asset");
                if(asset.equals("EQ_VOO"))asset="VOO";
                if (!unique.add(asset)) throw new IllegalArgumentException("钱包内资产重复");
                BigDecimal amount = BigDecimal.ZERO;
                for (String field : List.of("free", "locked", "freeze", "withdrawing")) {
                    if (!row.has(field)) { if (!missing.contains(name)) missing.add(name); continue; }
                    amount = amount.add(Ledger.decimal(row.getString(field)));
                }
                quantities.merge(asset, amount, BigDecimal::add);
            }
        }
        if (names.isEmpty()) missing.add("未返回已启用钱包");
        return new Result(quantities, value, missing, names);
    }
}
