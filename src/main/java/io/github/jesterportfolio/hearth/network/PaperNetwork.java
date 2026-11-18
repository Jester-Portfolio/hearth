package io.github.jesterportfolio.hearth.network;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.scheduler.BukkitTask;
import net.kyori.adventure.text.Component;

/** 같은 세션의 저장·동결·명시 이동. 실제 성공은 프록시 연결/클라이언트 수용에서 별도로 확인합니다. */
public final class PaperNetwork implements Listener,PluginMessageListener,AutoCloseable {
    public interface Boundary {Object token(Player p);boolean current(Player p,Object token);void freeze(Player p,Object token,boolean value);CompletionStage<Void> save(Player p,Object token);}
    private final JavaPlugin plugin;private final NetworkSettings settings;private final Boundary boundary;private final BackendTransfer transfers;private final byte[] key;private final BukkitTask pulse;private final Map<UUID,Object> tokens=new HashMap<>();private final Map<UUID,Player> actors=new HashMap<>();private boolean closed;
    private final class Screen implements InventoryHolder {final Player player;final Object token;final Inventory inventory;Screen(Player p,Object token){player=p;this.token=token;inventory=Bukkit.createInventory(this,9,Component.text("Hearth · 서버 선택"));var icon=new ItemStack(settings.lobbyRole()?Material.EMERALD:Material.COMPASS);icon.editMeta(m->{m.displayName(Component.text(settings.lobbyRole()?"경제 서버로 이동":"로비로 돌아가기"));m.lore(List.of(Component.text("현재 데이터 저장 확인 후 이동합니다.")));});inventory.setItem(4,icon);}public Inventory getInventory(){return inventory;}}
    public PaperNetwork(JavaPlugin plugin,NetworkSettings settings,Boundary boundary,Executor owner){this.plugin=plugin;this.settings=settings;this.boundary=boundary;key=NetworkSettings.secret(System.getenv(settings.relayEnvironment()));transfers=new BackendTransfer(settings,System::currentTimeMillis,owner);plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin,NetworkProtocol.CHANNEL);plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin,NetworkProtocol.CHANNEL,this);Bukkit.getPluginManager().registerEvents(this,plugin);pulse=Bukkit.getScheduler().runTaskTimer(plugin,transfers::tick,20,20);}
    private Object active(Player p){var token=boundary.token(p);return !closed&&token!=null&&boundary.current(p,token)&&transfers.phase(p.getUniqueId()).isEmpty()?token:null;}
    public boolean allowed(Player p,Inventory inv){return inv.getHolder() instanceof PaperNetwork.Screen s&&s.player==p&&s.token==active(p);}
    public boolean command(Player p,String line){if(!Set.of("/hearthserver","/hearthserver lobby","/hearthserver economy","/lobby").contains(line))return false;var token=active(p);if(token==null){p.sendMessage(Component.text("현재 저장·거래·이동 상태를 확인한 뒤 이용하세요."));return true;}if(line.equals("/hearthserver"))p.openInventory(new Screen(p,token).inventory);else if(line.equals("/lobby")||line.endsWith(" lobby")){if(!settings.lobbyRole())move(p,token);else p.sendMessage(Component.text("현재 로비에 있습니다."));}else if(settings.lobbyRole())move(p,token);else p.sendMessage(Component.text("현재 경제 서버에 있습니다."));return true;}
    private void move(Player p,Object token){if(active(p)!=token)return;p.closeInventory();try{tokens.put(p.getUniqueId(),token);actors.put(p.getUniqueId(),p);transfers.begin(p.getUniqueId(),token,new BackendTransfer.Port(){public boolean current(){return !closed&&p.isOnline()&&Bukkit.getPlayer(p.getUniqueId())==p&&boundary.current(p,token);}public void freeze(boolean value){boundary.freeze(p,token,value);}public CompletionStage<Void> save(){return boundary.save(p,token);}public void send(NetworkProtocol.Frame frame){p.sendPluginMessage(plugin,NetworkProtocol.CHANNEL,NetworkProtocol.encode(frame,key));p.sendMessage(Component.text("저장 완료 · 서버 연결을 기다립니다."));}public void notice(String code){if(p.isOnline()&&Bukkit.getPlayer(p.getUniqueId())==p)p.sendMessage(Component.text(code.equals("NETWORK_TARGET_REFUSED")?"대상 서버에 연결하지 못했습니다. 현재 서버에서 계속할 수 있습니다.":"이동 처리 보류 · 자동 재시도하지 않습니다. 재접속 후 확인하세요."));}});}catch(RuntimeException error){tokens.remove(p.getUniqueId(),token);actors.remove(p.getUniqueId(),p);p.sendMessage(Component.text("현재는 서버를 이동할 수 없습니다."));}}
    @Override public void onPluginMessageReceived(String channel,Player player,byte[] bytes){if(closed||!NetworkProtocol.CHANNEL.equals(channel))return;try{var frame=NetworkProtocol.decode(bytes,key);var token=actors.get(player.getUniqueId())==player?tokens.get(player.getUniqueId()):null;if(token!=null)transfers.response(player.getUniqueId(),token,frame);}catch(RuntimeException ignored){}}
    @EventHandler(priority=EventPriority.HIGHEST) public void click(InventoryClickEvent event){if(!(event.getView().getTopInventory().getHolder() instanceof PaperNetwork.Screen s))return;boolean denied=event.isCancelled();event.setCancelled(true);if(!NetworkMenuRules.activate(event.getWhoClicked()==s.player&&allowed(s.player,s.inventory)&&event.getClickedInventory()==s.inventory,denied,event.getSlot(),event.getClick().name()))return;Bukkit.getScheduler().runTask(plugin,()->{if(s.player.getOpenInventory().getTopInventory()==s.inventory&&allowed(s.player,s.inventory))move(s.player,s.token);});}
    @EventHandler(priority=EventPriority.HIGHEST) public void drag(InventoryDragEvent event){if(event.getView().getTopInventory().getHolder() instanceof PaperNetwork.Screen)event.setCancelled(true);}
    @EventHandler public void quit(PlayerQuitEvent event){if(!actors.remove(event.getPlayer().getUniqueId(),event.getPlayer()))return;var token=tokens.remove(event.getPlayer().getUniqueId());if(token!=null)transfers.quit(event.getPlayer().getUniqueId(),token);}
    public Optional<BackendTransfer.Phase> phase(UUID player){return transfers.phase(player);}
    @Override public void close(){closed=true;pulse.cancel();transfers.close();Arrays.fill(key,(byte)0);HandlerList.unregisterAll(this);plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin,NetworkProtocol.CHANNEL,this);plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin,NetworkProtocol.CHANNEL);}
}
