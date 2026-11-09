package io.github.jesterportfolio.hearth.inventory;

import io.github.jesterportfolio.hearth.session.InventoryWindowRules;
import java.util.*;
import java.util.function.Predicate;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.inventory.*;

/** 실제 월드 블록의 현재 보관함만 허용합니다. GUI/엔더 상자/이동식 보관함은 이 경계를 통과하지 않습니다. */
public final class WorldInventoryAccess {
    private WorldInventoryAccess(){}
    public static List<Block> blocks(Inventory inventory){
        var holder=inventory.getHolder(false);var blocks=new ArrayList<Block>();
        if(holder instanceof DoubleChest chest){if(!add(blocks,chest.getLeftSide(false))||!add(blocks,chest.getRightSide(false)))return List.of();}
        else if(!add(blocks,holder))return List.of();
        return List.copyOf(blocks);
    }
    private static boolean add(List<Block> found,InventoryHolder holder){if(!(holder instanceof Container c)||!InventoryWindowRules.container(c.getType().name()))return false;found.add(c.getBlock());return true;}
    public static boolean allowed(UUID world,Inventory inventory,Predicate<Location> access){
        var blocks=blocks(inventory);if(blocks.isEmpty()||blocks.size()>2)return false;
        for(var block:blocks){if(!block.getWorld().getUID().equals(world)||!block.getWorld().isChunkLoaded(block.getX()>>4,block.getZ()>>4)||!InventoryWindowRules.container(block.getType().name())||!access.test(block.getLocation()))return false;
            if(!(block.getState(false) instanceof Container current))return false;
            if(inventory instanceof DoubleChestInventory combined){
                if(!(current instanceof Chest chest)||!(chest.getInventory() instanceof DoubleChestInventory live)||!new HashSet<>(blocks(live)).equals(new HashSet<>(blocks)))return false;
                var half=chest.getBlockInventory();if(!half.equals(combined.getLeftSide())&&!half.equals(combined.getRightSide()))return false;
            }else if(!current.getInventory().equals(inventory))return false;}
        return true;
    }
}
