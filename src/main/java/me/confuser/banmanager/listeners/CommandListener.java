package me.confuser.banmanager.listeners;

import me.confuser.banmanager.BanManager;
import me.confuser.bukkitutil.Message;
import me.confuser.bukkitutil.listeners.Listeners;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

public class CommandListener extends Listeners<BanManager> {

  @EventHandler
  public void onCommand(PlayerCommandPreprocessEvent event) {
	  if (!plugin.getPlayerMuteStorage().isMuted(BanManager.getUUID(event.getPlayer().getName()))) {
	//old
	//if (!plugin.getPlayerMuteStorage().isMuted(event.getPlayer().getUniqueId())) {
      return;
    }

    // Split the command
    String[] args = event.getMessage().split(" ");

    // Get rid of the first /
    String cmd = args[0].replace("/", "").toLowerCase();

    if (!plugin.getConfiguration().isBlockedCommand(cmd)) {
      return;
    }

    event.setCancelled(true);
    event.getPlayer().sendMessage(Message.get("mute.player.blocked").set("command", cmd).toString());
  }
}
