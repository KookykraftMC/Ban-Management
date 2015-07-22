package me.confuser.banmanager.commands;

import me.confuser.banmanager.BanManager;
import me.confuser.banmanager.configs.ActionCommand;
import me.confuser.banmanager.configs.TimeLimitType;
import me.confuser.banmanager.data.PlayerData;
import me.confuser.banmanager.data.PlayerWarnData;
import me.confuser.banmanager.util.CommandParser;
import me.confuser.banmanager.util.CommandUtils;
import me.confuser.banmanager.util.DateUtils;
import me.confuser.banmanager.util.UUIDUtils;
import me.confuser.bukkitutil.Message;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

public class TempWarnCommand extends AutoCompleteNameTabCommand<BanManager> {

  public TempWarnCommand() {
    super("tempwarn");
  }

  @Override
  public boolean onCommand(final CommandSender sender, Command command, String commandName, String[] args) {
    CommandParser parser = new CommandParser(args);
    final String[] parsedArgs = parser.getArgs();
    final boolean isSilent = parser.isSilent();

    if (isSilent && !sender.hasPermission(command.getPermission() + ".silent")) {
      sender.sendMessage(Message.getString("sender.error.noPermission"));
      return true;
    }

    if (parsedArgs.length < 3) {
      return false;
    }

    if (CommandUtils.isValidNameDelimiter(parsedArgs[0])) {
      CommandUtils.handleMultipleNames(sender, commandName, parsedArgs);
      return true;
    }

    if (parsedArgs[0].toLowerCase().equals(sender.getName().toLowerCase())) {
      sender.sendMessage(Message.getString("sender.error.noSelf"));
      return true;
    }

    // Check if UUID vs name
    final String playerName = parsedArgs[0];
    final boolean isUUID = playerName.length() > 16;
    Player onlinePlayer;

    if (isUUID) {
      onlinePlayer = plugin.getServer().getPlayer(UUID.fromString(playerName));
    } else {
      onlinePlayer = plugin.getServer().getPlayer(playerName);
    }

    if (onlinePlayer == null) {
      if (!sender.hasPermission("bm.command.tempwarn.offline")) {
        sender.sendMessage(Message.getString("sender.error.offlinePermission"));
        return true;
      }
    } else if (!sender.hasPermission("bm.exempt.override.tempwarn") && onlinePlayer
            .hasPermission("bm.exempt.tempwarn")) {
      Message.get("sender.error.exempt").set("player", onlinePlayer.getName()).sendTo(sender);
      return true;
    }

    long expiresCheck;

    try {
      expiresCheck = DateUtils.parseDateDiff(parsedArgs[1], true);
    } catch (Exception e1) {
      sender.sendMessage(Message.get("time.error.invalid").toString());
      return true;
    }

    if (plugin.getConfiguration().getTimeLimits().isPastLimit(sender, TimeLimitType.PLAYER_WARN, expiresCheck)) {
      Message.get("time.error.limit").sendTo(sender);
      return true;
    }

    final long expires = expiresCheck;
    final String reason = CommandUtils.getReason(2, parsedArgs);

    plugin.getServer().getScheduler().runTaskAsynchronously(plugin, new Runnable() {

      @Override
      public void run() {
        final PlayerData player;

        if (isUUID) {
          try {
            player = plugin.getPlayerStorage().queryForId(UUIDUtils.toBytes(UUID.fromString(playerName)));
          } catch (SQLException e) {
            sender.sendMessage(Message.get("sender.error.exception").toString());
            e.printStackTrace();
            return;
          }
        } else {
          player = plugin.getPlayerStorage().retrieve(playerName, true);
        }

        if (player == null) {
          sender.sendMessage(Message.get("sender.error.notFound").set("player", playerName).toString());
          return;
        }

        if (plugin.getExemptionsConfig().isExempt(player, "tempwarn")) {
          sender.sendMessage(Message.get("sender.error.exempt").set("player", playerName).toString());
          return;
        }

        try {
          if (plugin.getPlayerWarnStorage().isRecentlyWarned(player)) {
            Message.get("warn.error.cooldown").sendTo(sender);
            return;
          }
        } catch (SQLException e) {
          sender.sendMessage(Message.get("sender.error.exception").toString());
          e.printStackTrace();
          return;
        }

        final PlayerData actor;

        if (sender instanceof Player) {
          try {
            actor = plugin.getPlayerStorage().queryForId(UUIDUtils.toBytes((Player) sender));
          } catch (SQLException e) {
            sender.sendMessage(Message.get("sender.error.exception").toString());
            e.printStackTrace();
            return;
          }
        } else {
          actor = plugin.getPlayerStorage().getConsole();
        }

        boolean isOnline = BanManager.getPlayer(player.getUUID()) != null;

        final PlayerWarnData warning = new PlayerWarnData(player, actor, reason, isOnline, expires);

        boolean created;

        try {
          created = plugin.getPlayerWarnStorage().addWarning(warning, isSilent);
        } catch (SQLException e) {
          sender.sendMessage(Message.get("sender.error.exception").toString());
          e.printStackTrace();
          return;
        }

        if (!created) {
          return;
        }

        if (isOnline) {
        	Player bukkitPlayer = BanManager.getPlayer(player.getUUID());

          Message warningMessage = Message.get("tempwarn.player.warned")
                                          .set("displayName", bukkitPlayer.getDisplayName())
                                          .set("player", player.getName())
                                          .set("reason", warning.getReason())
                                          .set("actor", actor.getName())
                                          .set("expires", DateUtils.getDifferenceFormat(warning.getExpires()));

          bukkitPlayer.sendMessage(warningMessage.toString());
        }

        Message message = Message.get("tempwarn.notify")
                                 .set("player", player.getName())
                                 .set("actor", actor.getName())
                                 .set("reason", warning.getReason())
                                 .set("expires", DateUtils.getDifferenceFormat(warning.getExpires()));

        if (!sender.hasPermission("bm.notify.tempwarn")) {
          message.sendTo(sender);
        }

        CommandUtils.broadcast(message.toString(), "bm.notify.tempwarn");

        final List<ActionCommand> actionCommands;

        try {
          actionCommands = plugin.getConfiguration().getWarningActions()
                                 .getCommand((int) plugin.getPlayerWarnStorage().getCount(player));
        } catch (SQLException e) {
          e.printStackTrace();
          return;
        }

        if (actionCommands == null || actionCommands.isEmpty()) {
          return;
        }

        for (final ActionCommand action : actionCommands) {

          plugin.getServer().getScheduler().runTaskLater(plugin, new Runnable() {

            @Override
            public void run() {
              String actionCommand = action.getCommand()
                                           .replace("[player]", player.getName())
                                           .replace("[actor]", actor.getName())
                                           .replace("[reason]", warning.getReason())
                                           .replace("[expires]", parsedArgs[1]);

              plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), actionCommand);
            }
          }, action.getDelay());
        }
      }
    });

    return true;
  }
}
