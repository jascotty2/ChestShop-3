package com.Acrobot.ChestShop.Plugins;

import com.Acrobot.ChestShop.ChestShop;
import com.Acrobot.ChestShop.Configuration.Messages;
import com.Acrobot.ChestShop.Configuration.Properties;
import com.Acrobot.ChestShop.Events.Protection.ProtectBlockEvent;
import com.Acrobot.ChestShop.Events.Protection.ProtectionCheckEvent;
import com.Acrobot.ChestShop.Events.ShopCreatedEvent;
import com.Acrobot.ChestShop.Events.ShopDestroyedEvent;
import com.Acrobot.ChestShop.Security;
import com.griefcraft.lwc.LWC;
import com.griefcraft.model.Protection;
import com.griefcraft.scripting.event.LWCProtectionRegisterEvent;
import com.griefcraft.scripting.event.LWCProtectionRegistrationPostEvent;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

/**
 * @author Acrobot
 */
public class LightweightChestProtection implements Listener {
    private LWC lwc;
    /**
     * If both the server and LWC support block IDs
     */
    private boolean id_supported = false;
    /**
     * If the LWC version being used supports Materials
     */
    private boolean material_supported = false;
    Method protect_by_id = null;

    public LightweightChestProtection() {
        this.lwc = LWC.getInstance();
        try {
            if (Properties.PROTECT_SIGN_WITH_LWC)
                Protection.Type.valueOf(Properties.LWC_SIGN_PROTECTION_TYPE.name());
            if (Properties.PROTECT_CHEST_WITH_LWC)
                Protection.Type.valueOf(Properties.LWC_CHEST_PROTECTION_TYPE.name());
        } catch (IllegalArgumentException e) {
            ChestShop.getBukkitLogger().warning("Your installed LWC version doesn't seem to support the configured protection type! " + e.getMessage());
        }
        // cheap hack
        Class db = lwc.getPhysicalDatabase().getClass();
        try {
            Material.AIR.getId();
            protect_by_id = db.getDeclaredMethod("registerProtection", int.class, Protection.Type.class, String.class, String.class, String.class, int.class, int.class, int.class);
            id_supported = true;
        } catch (Throwable ignore) {}
        try {
            db.getDeclaredMethod("registerProtection", Material.class, Protection.Type.class, String.class, String.class, String.class, int.class, int.class, int.class);
            material_supported = true;
        } catch (NoSuchMethodException | SecurityException ignore) {}
    }

    @EventHandler
    public static void onShopCreation(ShopCreatedEvent event) {
        Player player = event.getPlayer();
        Sign sign = event.getSign();
        Container connectedContainer = event.getContainer();

        String message = null;
        if (Properties.PROTECT_SIGN_WITH_LWC) {
            if (Security.protect(player, sign.getBlock(), event.getOwnerAccount() != null ? event.getOwnerAccount().getUuid() : player.getUniqueId(), Properties.LWC_SIGN_PROTECTION_TYPE)) {
                message = Messages.PROTECTED_SHOP_SIGN;
            } else {
                message = Messages.NOT_ENOUGH_PROTECTIONS;
            }
        }

        if (Properties.PROTECT_CHEST_WITH_LWC && connectedContainer != null) {
            if (Security.protect(player, connectedContainer.getBlock(), event.getOwnerAccount() != null ? event.getOwnerAccount().getUuid() : player.getUniqueId(), Properties.LWC_CHEST_PROTECTION_TYPE)) {
                message = Messages.PROTECTED_SHOP;
            } else if (message == null) {
                message = Messages.NOT_ENOUGH_PROTECTIONS;
            }
        }

        if (message != null) {
            player.sendMessage(Messages.prefix(message));
        }
    }

    @EventHandler
    public void onProtectionCheck(ProtectionCheckEvent event) {
        if (event.getResult() == Event.Result.DENY) {
            return;
        }

        Block block = event.getBlock();
        Player player = event.getPlayer();

        Protection protection = lwc.findProtection(block);

        if (protection == null) {
            return;
        }

        if (!lwc.canAccessProtection(player, protection) || protection.getType() == Protection.Type.DONATION) {
            event.setResult(Event.Result.DENY);
        }
    }

    @EventHandler
    public void onBlockProtect(ProtectBlockEvent event) {
        if (event.isProtected()) {
            return;
        }

        Block block = event.getBlock();
        Player player = event.getPlayer();

        if (player == null) {
            return;
        }

        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();

        String worldName = block.getWorld().getName();

        Protection existingProtection = lwc.getPhysicalDatabase().loadProtection(worldName, x, y, z);

        if (existingProtection != null) {
            event.setProtected(true);
            return;
        }

        LWCProtectionRegisterEvent protectionEvent = new LWCProtectionRegisterEvent(player, block);
        lwc.getModuleLoader().dispatchEvent(protectionEvent);

        if (protectionEvent.isCancelled()) {
            return;
        }

        Protection.Type type = Protection.Type.PRIVATE;
        switch (event.getType()) {
            case PUBLIC:
                type = Protection.Type.PUBLIC;
                break;
            case DONATION:
                type = Protection.Type.DONATION;
                break;
            case DISPLAY:
                try {
                    type = Protection.Type.valueOf("DISPLAY");
                } catch (IllegalArgumentException ignored) {}
                break;
        }

        Protection protection = null;
        // funny bit: some versions of LWC being used on older servers don't support passing Material
        if(material_supported) {
            protection = lwc.getPhysicalDatabase().registerProtection(block.getType(), type, worldName, event.getProtectionOwner().toString(), "", x, y, z);
        } else if(id_supported) {
            try {
                // if we're on an older server that supports ids, use that.
                protection = (Protection) protect_by_id.invoke(lwc.getPhysicalDatabase(), block.getType().getId(), type, worldName, player.getUniqueId().toString(), "", x, y, z);
            } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException ex) {
                // something went wrong
                id_supported = false;

//        try {
//            protection = lwc.getPhysicalDatabase().registerProtection(block.getType(), type, worldName, event.getProtectionOwner().toString(), "", x, y, z);
//        } catch (LinkageError e) {
//            try {
//                int blockId = com.griefcraft.cache.BlockCache.getInstance().getBlockId(block);
//                if (blockId < 0) {
//                    return;
//                }
//                protection = lwc.getPhysicalDatabase().registerProtection(blockId, type, worldName, event.getProtectionOwner().toString(), "", x, y, z);
//            } catch (LinkageError e2) {
//                ChestShop.getBukkitLogger().warning(
//                        "Incompatible LWC version installed! (" + lwc.getPlugin().getName() + " v" + lwc.getVersion()  + ") \n" +
//                                "Material method error: " + e.getMessage() + "\n" +
//                                "Block cache/type id error: " + e2.getMessage()
//                );
            }
        }

        if (protection != null) {
            event.setProtected(true);
            protection.removeCache();
            lwc.getProtectionCache().addProtection(protection);
            lwc.getModuleLoader().dispatchEvent(new LWCProtectionRegistrationPostEvent(protection));
        }
    }

    @EventHandler
    public void onShopRemove(ShopDestroyedEvent event) {
        Protection signProtection = lwc.findProtection(event.getSign().getBlock());

        if (signProtection != null) {
            signProtection.remove();
        }

        if (event.getContainer() == null || !Properties.REMOVE_LWC_PROTECTION_AUTOMATICALLY) {
            return;
        }

        Protection chestProtection = lwc.findProtection(event.getContainer().getBlock());

        if (chestProtection != null) {
            chestProtection.remove();
        }
    }
}
