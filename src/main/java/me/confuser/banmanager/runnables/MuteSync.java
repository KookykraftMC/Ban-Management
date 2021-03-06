package me.confuser.banmanager.runnables;

import com.j256.ormlite.dao.CloseableIterator;
import lombok.Getter;
import me.confuser.banmanager.BanManager;
import me.confuser.banmanager.data.PlayerMuteData;
import me.confuser.banmanager.data.PlayerMuteRecord;
import me.confuser.banmanager.storage.PlayerMuteStorage;

import java.sql.SQLException;

public class MuteSync implements Runnable {

  private BanManager plugin = BanManager.getPlugin();
  private PlayerMuteStorage muteStorage = plugin.getPlayerMuteStorage();
  private long lastChecked = 0;
  @Getter
  private boolean isRunning = false;

  public MuteSync() {
    lastChecked = plugin.getSchedulesConfig().getLastChecked("playerMutes");
  }

  @Override
  public void run() {
    isRunning = true;
    // New/updated mutes check
    newMutes();

    // New unbans
    newUnmutes();

    lastChecked = System.currentTimeMillis() / 1000L;
    plugin.getSchedulesConfig().setLastChecked("playerMutes", lastChecked);
    isRunning = false;
  }

  private void newMutes() {

    CloseableIterator<PlayerMuteData> itr = null;
    try {
      itr = muteStorage.findMutes(lastChecked);

      while (itr.hasNext()) {
        final PlayerMuteData mute = itr.next();

        if (muteStorage.isMuted(mute.getPlayer().getUUID()) && mute.getUpdated() < lastChecked) {
          continue;
        }

        muteStorage.addMute(mute);

      }
    } catch (SQLException e) {
      e.printStackTrace();
    } finally {
      if (itr != null) itr.closeQuietly();
    }

  }

  private void newUnmutes() {

    CloseableIterator<PlayerMuteRecord> itr = null;
    try {
      itr = plugin.getPlayerMuteRecordStorage().findUnmutes(lastChecked);

      while (itr.hasNext()) {
        final PlayerMuteRecord mute = itr.next();

        if (!muteStorage.isMuted(mute.getPlayer().getUUID())) {
          continue;
        }

        if (!mute.equalsMute(muteStorage.getMute(mute.getPlayer().getUUID()))) {
          continue;
        }

        muteStorage.removeMute(mute.getPlayer().getUUID());

      }
    } catch (SQLException e) {
      e.printStackTrace();
    } finally {
      if (itr != null) itr.closeQuietly();
    }

  }
}
