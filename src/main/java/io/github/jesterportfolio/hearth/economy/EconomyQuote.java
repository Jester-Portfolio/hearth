package io.github.jesterportfolio.hearth.economy;

/** 읽기 전용 견적. 실제 확정은 기존 거래의 재고/revision/인벤토리 검사에 맡깁니다. */
public final class EconomyQuote {
    private EconomyQuote(){}
    public enum Reason { READY, STOCK, FUNDS, SPACE, ITEMS, BALANCE_LIMIT }
    public record View(int quantity,boolean buy,Long total,Reason reason,long balance,long stock){
        public boolean available(){return reason==Reason.READY;}
        public String guidance(){return switch(reason){case READY->"확정 시 최신 상태를 다시 검사합니다";case STOCK->"시장 재고가 부족합니다";case FUNDS->"개발 화폐 잔액이 부족합니다";case SPACE->"보관 슬롯의 빈 공간이 부족합니다";case ITEMS->"판매 가능한 일반 아이템이 부족합니다";case BALANCE_LIMIT->"개발 화폐 잔액의 저장 한도를 초과합니다";};}
        public String projectedBalance(){return available()?Long.toString(buy?balance-total:Math.addExact(balance,total)):"거래 불가";}
    }
    public record Capacity(int buy,int sell){public Capacity{if(buy<0||sell<0)throw new IllegalArgumentException("ECON_CAPACITY");}}
    public static int adjust(int quantity,int delta){quantity(quantity);return (int)Math.clamp((long)quantity+delta,1,4096);}
    private static void quantity(int quantity){if(quantity<1||quantity>4096)throw new IllegalArgumentException("ECON_QUANTITY");}
    public static View view(EconomyContent content,EconomyStore.Offer offer,Capacity capacity,int quantity,boolean buy){
        quantity(quantity);var market=offer.market();
        if(buy&&quantity>market.stock()||!buy&&market.stock()>EconomyEngine.MAX_STOCK-quantity)return new View(quantity,buy,null,Reason.STOCK,offer.balance(),market.stock());
        long total=EconomyEngine.quote(content,offer.key().good(),market,quantity,buy);
        Reason reason=buy?(quantity>capacity.buy()?Reason.SPACE:total>offer.balance()?Reason.FUNDS:Reason.READY):(quantity>capacity.sell()?Reason.ITEMS:offer.balance()>Long.MAX_VALUE-total?Reason.BALANCE_LIMIT:Reason.READY);
        return new View(quantity,buy,total,reason,offer.balance(),market.stock());
    }
    public static int maximum(EconomyContent content,EconomyStore.Offer offer,Capacity capacity,boolean buy){
        int low=0,high=Math.min(4096,buy?capacity.buy():capacity.sell());
        while(low<high){int mid=low+(high-low+1)/2;if(view(content,offer,capacity,mid,buy).available())low=mid;else high=mid-1;}return low;
    }
}
