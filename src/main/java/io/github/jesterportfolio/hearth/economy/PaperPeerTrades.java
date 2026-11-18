package io.github.jesterportfolio.hearth.economy;

import io.github.jesterportfolio.hearth.session.PlayerSessions;
import io.github.jesterportfolio.hearth.inventory.InventorySaveSession;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import net.kyori.adventure.text.Component;

/** 같은 경제 월드의 근거리 상호 동의 거래. 제안에는 기존 등록 상품과 PG 화폐만 사용합니다. */
public final class PaperPeerTrades implements Listener,AutoCloseable {
    private final JavaPlugin plugin;private final PaperEconomy economy;private final Executor owner;public final PeerExchange exchange;private final Map<UUID,Board> boards=new HashMap<>();private final Map<UUID,Screen> screens=new HashMap<>();private final BukkitTask pulse;private boolean closed,closing;
    private final class Board {
        final PeerTrade deal;final PlayerSessions.Session first,second;final AtomicBoolean alive=new AtomicBoolean(true);final AtomicBoolean firstFence,secondFence;CompletionStage<Void> work=CompletableFuture.completedFuture(null);final long generation,expires=System.nanoTime()+TimeUnit.MINUTES.toNanos(3);
        Board(Player a,Player b){first=economy.adapter.current(a);second=economy.adapter.current(b);generation=economy.world.generation();deal=new PeerTrade(UUID.randomUUID(),economy.realm,a.getUniqueId(),b.getUniqueId());firstFence=economy.fence(first);secondFence=economy.fence(second);}
        boolean valid(){return !closed&&alive.get()&&firstFence.get()&&secondFence.get()&&generation==economy.world.generation();}
    }
    private final class Screen implements InventoryHolder {
        final Board board;final UUID player;final long seen;final int page;final Inventory inventory;final Map<Integer,String> goods=new HashMap<>();
        Screen(Board board,UUID player,int page){this.board=board;this.player=player;this.page=page;seen=board.deal.revision();inventory=Bukkit.createInventory(this,54,Component.text("플레이어 거래 · 제안 "+seen));}
        public Inventory getInventory(){return inventory;}
    }
    public PaperPeerTrades(JavaPlugin plugin,PaperEconomy economy,Executor owner){this.plugin=plugin;this.economy=economy;this.owner=owner;exchange=new PeerExchange(economy.store,economy.trades,owner);economy.adapter.questCommand(this::command);Bukkit.getPluginManager().registerEvents(this,plugin);pulse=Bukkit.getScheduler().runTaskTimer(plugin,this::tick,1,1);Objects.requireNonNull(plugin.getCommand("hearthtrade")).setExecutor((sender,cmd,label,args)->{if(sender instanceof Player p)command(p,"/hearthtrade "+String.join(" ",args));else sender.sendMessage("플레이어 거래는 경제 월드 접속자 전용입니다.");return true;});}
    private static void main(){if(!Bukkit.isPrimaryThread())throw new IllegalStateException("ECON_PEER_MAIN");}
    private Player player(UUID id){return Bukkit.getPlayer(id);}
    public boolean busy(UUID player){main();return boards.containsKey(player);}
    private boolean available(Player player,PlayerSessions.Session session,Board board){return player!=null&&player.isOnline()&&!player.isDead()&&economy.adapter.linked(player)==session&&session.state()==PlayerSessions.State.ACTIVE&&player.getWorld().getUID().equals(economy.physicalWorld())&&board.valid();}
    private boolean near(Board board){var a=player(board.deal.first());var b=player(board.deal.second());return available(a,board.first,board)&&available(b,board.second,board)&&a.getWorld()==b.getWorld()&&a.getLocation().distanceSquared(b.getLocation())<=36;}
    public boolean allowed(Player player,Inventory inventory){main();if(!(inventory.getHolder() instanceof PaperPeerTrades.Screen screen))return false;return screens.get(player.getUniqueId())==screen&&screen.player.equals(player.getUniqueId())&&boards.get(player.getUniqueId())==screen.board&&near(screen.board);}
    private void say(Board board,String text){for(var id:List.of(board.deal.first(),board.deal.second())){var p=player(id);if(p!=null)p.sendMessage(Component.text(text));}}
    private void remove(Board board){boards.remove(board.deal.first(),board);boards.remove(board.deal.second(),board);for(var id:List.of(board.deal.first(),board.deal.second())){var s=screens.get(id);if(s!=null&&s.board==board){screens.remove(id);var p=player(id);if(p!=null&&p.getOpenInventory().getTopInventory()==s.inventory)p.closeInventory();}}}
    private void cancel(Board board,UUID actor){board.alive.set(false);board.deal.cancel(actor);if(board.deal.state()!=PeerTrade.State.HOLD)remove(board);say(board,board.deal.state()==PeerTrade.State.HOLD?"거래 결과 확인 보류 · 중복 요청하지 말고 관리자에게 요청 번호를 알려주세요: "+board.deal.id():"거래가 취소되었습니다. 제안한 아이템과 화폐는 이동하지 않았습니다.");}
    private void tick(){main();for(var board:new HashSet<>(boards.values()))if(board.deal.state()!=PeerTrade.State.HOLD&&(!near(board)||System.nanoTime()>=board.expires))cancel(board,board.deal.first());}
    public boolean command(Player player,String text){main();String[] words=text.trim().split("\\s+");if(words.length==0||!words[0].equalsIgnoreCase("/hearthtrade"))return false;
        try{if(closed||closing||economy.adapter.current(player)==null)throw new IllegalStateException();String action=words.length>1?words[1]:"help";
            if(action.equals("request")&&words.length==3){if(boards.size()>=256||boards.containsKey(player.getUniqueId()))throw new IllegalStateException();var target=Bukkit.getPlayerExact(words[2]);if(target==null||target==player||economy.adapter.current(target)==null||boards.containsKey(target.getUniqueId())||target.getWorld()!=player.getWorld()||player.getLocation().distanceSquared(target.getLocation())>36)throw new IllegalStateException();var board=new Board(player,target);boards.put(player.getUniqueId(),board);boards.put(target.getUniqueId(),board);player.sendMessage(Component.text(target.getName()+"에게 거래를 요청했습니다."));target.sendMessage(Component.text(player.getName()+"의 거래 요청 · /hearthtrade accept 또는 /hearthtrade cancel"));return true;}
            if(action.equals("receipt")&&words.length==3){var session=economy.adapter.current(player);economy.store.peerReceipt(session.context(),UUID.fromString(words[2])).whenCompleteAsync((found,error)->{if(economy.adapter.current(player)!=session)return;player.sendMessage(Component.text(error!=null?"거래 영수증 조회 보류":found.isEmpty()?"확정 영수증 없음 · 미확정 결과를 재제출하지 마세요.":"확정 거래 "+found.get().id()+" / 내 제안 "+(found.get().first().equals(player.getUniqueId())?found.get().firstOffer():found.get().secondOffer())));},owner);return true;}
            var board=boards.get(player.getUniqueId());if(action.equals("help")){player.sendMessage(Component.text("/hearthtrade request <이름> · accept · show · cancel · item <품목> <수량> · money <금액> · receipt <요청 번호>"));return true;}if(board==null||!near(board))throw new IllegalStateException();
            if(action.equals("cancel")&&words.length==2){cancel(board,player.getUniqueId());return true;}
            if(action.equals("accept")&&words.length==2){board.deal.accept(player.getUniqueId());show(board,board.deal.first(),0);show(board,board.deal.second(),0);return true;}
            if(action.equals("show")&&words.length==2){show(board,player.getUniqueId(),0);return true;}
            if(action.equals("item")&&words.length==4){if(!economy.store.content().goods().containsKey(words[2]))throw new IllegalArgumentException();int quantity=Integer.parseInt(words[3]);if(quantity<0||quantity>4096)throw new IllegalArgumentException();var old=board.deal.offer(player.getUniqueId());var goods=new HashMap<>(old.goods());if(quantity==0)goods.remove(words[2]);else goods.put(words[2],quantity);board.deal.edit(player.getUniqueId(),new PeerTrade.Offer(goods,old.money()));refresh(board);return true;}
            if(action.equals("money")&&words.length==3){var old=board.deal.offer(player.getUniqueId());board.deal.edit(player.getUniqueId(),new PeerTrade.Offer(old.goods(),Long.parseLong(words[2])));refresh(board);return true;}
            throw new IllegalArgumentException();
        }catch(RuntimeException refused){player.sendMessage(Component.text("거래 요청을 처리할 수 없습니다. 같은 경제 월드의 가까운 활성 참가자와 현재 제안을 확인하세요."));return true;}
    }
    private void icon(Screen s,int slot,Material material,String label,String... lore){var item=new ItemStack(material);item.editMeta(m->{m.displayName(Component.text(label));m.lore(Arrays.stream(lore).map(Component::text).toList());});s.inventory.setItem(slot,item);}
    private void show(Board board,UUID who,int page){if(!near(board)||board.deal.state()!=PeerTrade.State.OPEN&&board.deal.state()!=PeerTrade.State.COMMITTING)return;var player=player(who);var all=economy.store.content().goods().keySet().stream().sorted().toList();int pages=(all.size()+15)/16;page=Math.clamp(page,0,pages-1);var screen=new Screen(board,who,page);var own=board.deal.offer(who);UUID other=who.equals(board.deal.first())?board.deal.second():board.deal.first();var theirs=board.deal.offer(other);
        icon(screen,0,Material.PAPER,"내 제안 · 화폐 "+own.money(),"좌클릭 +1 / 우클릭 -1","Shift 클릭은 16개씩 변경합니다.","ESC 후에도 제안 유지 · 명시적 취소 또는 3분 후 종료");icon(screen,8,Material.PAPER,"상대 제안 · 화폐 "+theirs.money(),"제안 변경 시 양쪽 확인이 취소됩니다.");
        for(int i=page*16;i<Math.min(all.size(),page*16+16);i++){String id=all.get(i);int index=i-page*16,left=9+(index/4)*9+index%4;var type=Material.valueOf(economy.store.content().goods().get(id).material());icon(screen,left,type,id+" · "+own.goods().getOrDefault(id,0)+"개");icon(screen,left+5,type,id+" · "+theirs.goods().getOrDefault(id,0)+"개");screen.goods.put(left,id);}
        icon(screen,45,Material.BARRIER,"취소");icon(screen,46,Material.GOLD_NUGGET,"화폐 -10");icon(screen,47,Material.GOLD_NUGGET,"화폐 -1");icon(screen,48,Material.PAPER,"제안 "+screen.seen+" / "+(page+1)+" / "+pages);icon(screen,49,Material.GOLD_NUGGET,"화폐 +1");icon(screen,50,Material.GOLD_NUGGET,"화폐 +10");icon(screen,51,Material.LIME_DYE,board.deal.state()==PeerTrade.State.COMMITTING?"거래 확정 중":"최종 확인", "내 확인: "+board.deal.approvals().contains(who),"상대 확인: "+board.deal.approvals().contains(other));icon(screen,52,Material.ARROW,"이전 품목");icon(screen,53,Material.ARROW,"다음 품목");screens.put(who,screen);player.openInventory(screen.inventory);
    }
    private void refresh(Board board){for(var id:List.of(board.deal.first(),board.deal.second())){var screen=screens.get(id);var p=player(id);if(screen!=null&&p!=null&&p.getOpenInventory().getTopInventory()==screen.inventory)show(board,id,screen.page);}}
    private void commit(Board board){refresh(board);board.work=exchange.execute(economy.sessions,board.first,board.second,board.deal,()->board.alive.get()&&board.firstFence.get()&&board.secondFence.get(),EconomyStore.Probe.NONE).handleAsync((result,error)->{
        if(error==null&&board.deal.state()==PeerTrade.State.COMMITTING){board.deal.complete(true);say(board,"거래 완료 · 요청 "+board.deal.id());remove(board);}else if(PeerExchange.REFUSALS.contains(InventorySaveSession.code(error))){board.deal.refused();say(board,"잔액·상품·보관 공간을 확인하지 못해 거래하지 않았습니다.");remove(board);}else{board.deal.complete(false);plugin.getLogger().warning("HEARTH_ECONOMY_PEER_HOLD request="+board.deal.id()+" first="+board.deal.first()+" second="+board.deal.second());economy.sessions.inventoryHold(board.first,"ECON_PEER_REVIEW_REQUIRED");economy.sessions.inventoryHold(board.second,"ECON_PEER_REVIEW_REQUIRED");say(board,"거래 결과 확인 보류 · 요청 "+board.deal.id());throw new CompletionException(new IllegalStateException("ECON_PEER_REVIEW_REQUIRED"));}return (Void)null;
    },owner);}
    @EventHandler(priority=EventPriority.HIGHEST) public void click(InventoryClickEvent event){if(!(event.getView().getTopInventory().getHolder() instanceof PaperPeerTrades.Screen screen))return;event.setCancelled(true);if(closing||!(event.getWhoClicked() instanceof Player player)||!allowed(player,screen.inventory)||event.getClickedInventory()!=screen.inventory||screen.board.deal.state()!=PeerTrade.State.OPEN)return;var click=event.getClick();if(!Set.of(ClickType.LEFT,ClickType.RIGHT,ClickType.SHIFT_LEFT,ClickType.SHIFT_RIGHT).contains(click))return;int slot=event.getSlot();Bukkit.getScheduler().runTask(plugin,()->{if(!allowed(player,screen.inventory)||player.getOpenInventory().getTopInventory()!=screen.inventory||screen.board.deal.revision()!=screen.seen)return;var board=screen.board;try{
        if(slot==45){cancel(board,player.getUniqueId());return;}if(slot==52||slot==53){show(board,player.getUniqueId(),screen.page+(slot==52?-1:1));return;}if(slot==51){if(board.deal.confirm(player.getUniqueId(),screen.seen))commit(board);else refresh(board);return;}
        var offer=board.deal.offer(player.getUniqueId());var goods=new HashMap<>(offer.goods());long money=offer.money();if(screen.goods.containsKey(slot)){String id=screen.goods.get(slot);int delta=(click.isRightClick()?-1:1)*(click.isShiftClick()?16:1);int count=Math.clamp(goods.getOrDefault(id,0)+delta,0,4096);if(count==0)goods.remove(id);else goods.put(id,count);}else if(Set.of(46,47,49,50).contains(slot))money=Math.max(0,Math.addExact(money,switch(slot){case 46->-10;case 47->-1;case 49->1;default->10;}));else return;board.deal.edit(player.getUniqueId(),new PeerTrade.Offer(goods,money));refresh(board);
    }catch(RuntimeException refused){player.sendMessage(Component.text("현재 제안을 다시 확인하세요."));}});}
    @EventHandler(priority=EventPriority.HIGHEST) public void drag(InventoryDragEvent event){if(event.getView().getTopInventory().getHolder() instanceof PaperPeerTrades.Screen)event.setCancelled(true);}
    @EventHandler public void close(InventoryCloseEvent event){if(event.getInventory().getHolder() instanceof PaperPeerTrades.Screen screen&&screens.remove(event.getPlayer().getUniqueId(),screen)&&screen.board.deal.state()==PeerTrade.State.COMMITTING)cancel(screen.board,event.getPlayer().getUniqueId());}
    @EventHandler(priority=EventPriority.LOWEST) public void quit(PlayerQuitEvent event){var board=boards.get(event.getPlayer().getUniqueId());if(board!=null)cancel(board,event.getPlayer().getUniqueId());}
    public CompletionStage<Void> drain(){main();closing=true;var work=new ArrayList<CompletableFuture<?>>();for(var board:new HashSet<>(boards.values())){if(board.deal.state()==PeerTrade.State.INVITED||board.deal.state()==PeerTrade.State.OPEN)cancel(board,board.deal.first());else if(board.deal.state()==PeerTrade.State.HOLD)return CompletableFuture.failedFuture(new IllegalStateException("ECON_PEER_REVIEW_REQUIRED"));else work.add(board.work.toCompletableFuture());}return CompletableFuture.allOf(work.toArray(CompletableFuture[]::new)).thenRunAsync(this::stop,owner);}
    public void stop(){main();closed=true;pulse.cancel();for(var board:new HashSet<>(boards.values()))cancel(board,board.deal.first());HandlerList.unregisterAll(this);}
    @Override public void close(){if(!closed)stop();}
}
