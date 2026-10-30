package local.asset.assistant;
import java.math.*;
final class FundingMath {
    static BigDecimal share(String cnyPaid,String usdtReceived,String cnyShare){
        BigDecimal cost=Ledger.decimal(Ledger.positive(cnyPaid)),received=Ledger.decimal(Ledger.positive(usdtReceived)),part=Ledger.decimal(Ledger.positive(cnyShare));
        if(part.compareTo(cost)>0)throw new IllegalArgumentException("分配份额超过该笔人民币实付");
        return received.multiply(part).divide(cost,18,RoundingMode.DOWN);
    }
}
