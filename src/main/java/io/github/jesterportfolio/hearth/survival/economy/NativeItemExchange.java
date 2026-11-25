package io.github.jesterportfolio.hearth.survival.economy;
import java.util.*;import java.security.*;
import org.bukkit.inventory.ItemStack;
import io.github.jesterportfolio.hearth.economy.PeerTrade;
import io.github.jesterportfolio.hearth.persistence.StorageFailure;
/** Exact source slots are quotes, never escrow. ItemStack metadata is copied without reconstruction. */
public final class NativeItemExchange {
 private NativeItemExchange(){}
 public static final class Quote{
  private final byte[] bytes;private final String hash;
  private Quote(ItemStack item){bytes=item.serializeAsBytes();if(bytes.length>io.github.jesterportfolio.hearth.inventory.InventoryPayload.MAX_SLOT_BYTES)throw new IllegalArgumentException("PEER_ITEM_SIZE");try{hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}}
  public String hash(){return hash;}public int size(){return bytes.length;}public ItemStack item(){return ItemStack.deserializeBytes(bytes.clone());}
  public boolean matches(ItemStack current){if(current==null||current.isEmpty())return false;byte[] data=current.serializeAsBytes();return data.length<=io.github.jesterportfolio.hearth.inventory.InventoryPayload.MAX_SLOT_BYTES&&ItemStack.deserializeBytes(data).equals(item());}
 }
 public static Quote quote(ItemStack item){return new Quote(item);}
 public static List<ItemStack> take(ItemStack[] inventory,Map<Integer,PeerTrade.Stack> offered,Map<Integer,Quote> snapshots){var result=new ArrayList<ItemStack>();for(var e:new TreeMap<>(offered).entrySet()){var item=inventory[e.getKey()];var offer=e.getValue();var snapshot=snapshots.get(e.getKey());if(snapshot==null||!snapshot.hash().equals(offer.hash())||item==null||item.isEmpty()||item.getAmount()<offer.quantity()||!snapshot.matches(item))throw new StorageFailure("peer_items","ECON_QUOTE_CHANGED");result.add(item.asQuantity(offer.quantity()));int left=item.getAmount()-offer.quantity();inventory[e.getKey()]=left==0?null:item.asQuantity(left);}return List.copyOf(result);}
 public static void insert(ItemStack[] inventory,List<ItemStack> incoming){for(var source:incoming){var remaining=source.clone();for(int i=0;i<36&&!remaining.isEmpty();i++){var item=inventory[i];if(item!=null&&item.isSimilar(remaining)){int n=Math.min(remaining.getAmount(),Math.max(0,item.getMaxStackSize()-item.getAmount()));item.setAmount(item.getAmount()+n);remaining.subtract(n);}}for(int i=0;i<36&&!remaining.isEmpty();i++)if(inventory[i]==null||inventory[i].isEmpty()){int n=Math.min(remaining.getAmount(),remaining.getMaxStackSize());inventory[i]=remaining.asQuantity(n);remaining.subtract(n);}if(!remaining.isEmpty())throw new StorageFailure("peer_items","ECON_INVENTORY_FULL");}}
}
