package me.confuser.banmanager;

import com.j256.ormlite.jdbc.JdbcPooledConnectionSource;
import com.j256.ormlite.logger.LocalLog;

import lombok.Getter;
import me.confuser.banmanager.commands.*;
import me.confuser.banmanager.commands.external.*;
import me.confuser.banmanager.configs.*;
import me.confuser.banmanager.listeners.*;
import me.confuser.banmanager.runnables.*;
import me.confuser.banmanager.storage.*;
import me.confuser.banmanager.storage.conversion.UUIDConvert;
import me.confuser.banmanager.storage.external.*;
import me.confuser.banmanager.storage.mysql.MySQLDatabase;
import me.confuser.banmanager.util.DateUtils;
import me.confuser.banmanager.util.UpdateUtils;
import me.confuser.bukkitutil.BukkitPlugin;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.mcstats.MetricsLite;

import java.io.IOException;
import java.sql.SQLException;


import java.util.UUID;





//For 1.6.4 UUID Compatibility - @MrWisski
import net.kaikk.mc.uuidprovider.UUIDProvider;

public class BanManager extends BukkitPlugin {

  @Getter
  public static BanManager plugin;
  
  //For 1.6.4 UUID Compat - @MrWisski
  private UUIDProvider uuidprov;

  private JdbcPooledConnectionSource localConn;
  private JdbcPooledConnectionSource externalConn;
  private JdbcPooledConnectionSource conversionConn;

  @Getter
  private PlayerBanStorage playerBanStorage;
  @Getter
  private PlayerBanRecordStorage playerBanRecordStorage;
  @Getter
  private PlayerKickStorage playerKickStorage;
  @Getter
  private PlayerMuteStorage playerMuteStorage;
  @Getter
  private PlayerMuteRecordStorage playerMuteRecordStorage;
  @Getter
  private PlayerStorage playerStorage;
  @Getter
  private PlayerWarnStorage playerWarnStorage;
  @Getter
  private PlayerNoteStorage playerNoteStorage;
  @Getter
  private ActivityStorage activityStorage;
  @Getter
  private HistoryStorage historyStorage;

  @Getter
  private IpBanStorage ipBanStorage;
  @Getter
  private IpBanRecordStorage ipBanRecordStorage;
  @Getter
  private IpRangeBanStorage ipRangeBanStorage;
  @Getter
  private IpRangeBanRecordStorage ipRangeBanRecordStorage;

  @Getter
  private ExternalPlayerBanStorage externalPlayerBanStorage;
  @Getter
  private ExternalPlayerBanRecordStorage externalPlayerBanRecordStorage;
  @Getter
  private ExternalPlayerMuteStorage externalPlayerMuteStorage;
  @Getter
  private ExternalPlayerMuteRecordStorage externalPlayerMuteRecordStorage;
  @Getter
  private ExternalPlayerNoteStorage externalPlayerNoteStorage;

  @Getter
  private ExternalIpBanStorage externalIpBanStorage;
  @Getter
  private ExternalIpBanRecordStorage externalIpBanRecordStorage;

  @Getter
  private DefaultConfig configuration;
  @Getter
  private ConsoleConfig consoleConfig;
  @Getter
  private SchedulesConfig schedulesConfig;
  @Getter
  private ExemptionsConfig exemptionsConfig;
  @Getter
  private ReasonsConfig reasonsConfig;
  @Getter
  private GeoIpConfig geoIpConfig;

  @Getter
  private BanSync banSync;
  @Getter
  private MuteSync muteSync;
  @Getter
  private IpSync ipSync;
  @Getter
  private IpRangeSync ipRangeSync;
  @Getter
  private ExpiresSync expiresSync;

  @Getter
  private ExternalBanSync externalBanSync;
  @Getter
  private ExternalMuteSync externalMuteSync;
  @Getter
  private ExternalIpSync externalIpSync;
  @Getter
  private ExternalNoteSync externalNoteSync;

  @Override
  public void onEnable() {
    //UUID Provider service integration - @MrWisski
    try {
    	Plugin uuidplugin = this.getServer().getPluginManager().getPlugin("UUIDProvider");
    	if(uuidplugin instanceof UUIDProvider){
    		this.uuidprov = (UUIDProvider)uuidplugin;
    		getLogger().info("Found UUIDProvider plugin! Using UUIDProvider ("+uuidprov.getDescription().getVersion()+") for all UUID related activities!");
    	} else {
    		//We shouldn't ever get here, as UUIDProvider is listed as a depend.... but just in case.
    		getLogger().severe("**********************************************************************************");
    		getLogger().severe("Could not find UUIDProvider - This is a REQUIRED Dependency!");
    		getLogger().severe("This version of BanManager will not run without it!");
    		getLogger().severe("BanManager is DISABLED.");
    		getLogger().severe("**********************************************************************************");
    		Thread.sleep(20000);
    		return;
    	}
    } catch (Exception e) {
    	getLogger().severe("Caught exception establishing external UUID Provider services : ");
    	getLogger().severe(e.getMessage());
    	e.printStackTrace();
    }    
    
    plugin = this;
    
    setupConfigs();
    try {
      if (!configuration.isDebugEnabled()) {
        disableDatabaseLogging();
      }

      if (!setupConnections()) {
        return;
      }

      setupStorages();
    } catch (SQLException e) {
      getLogger().warning("An error occurred attempting to make a database connection, please see stack trace below");
      plugin.getPluginLoader().disablePlugin(this);
      e.printStackTrace();
      return;
    }

    try {
      long timeDiff = DateUtils.findTimeDiff();

      if (timeDiff > 1) {
        getLogger()
                .severe("The time on your server and MySQL database are out by " + timeDiff + " seconds, this may cause syncing issues.");
      }
    } catch (SQLException e) {
      getLogger().warning("An error occurred attempting to find the time difference, please see stack trace below");
      plugin.getPluginLoader().disablePlugin(this);
      e.printStackTrace();
    }

    if (conversionConn != null) {
      setupConversion();
    }

    setupListeners();
    setupCommands();
    setupRunnables();
  
    try {
      MetricsLite metrics = new MetricsLite(this);
      metrics.start();
    } catch (IOException e) {
      // Failed to submit the stats :-(
    }
  }

  @Override
  public void onDisable() {
    getServer().getScheduler().cancelTasks(plugin);

    if (localConn != null) {
      localConn.closeQuietly();
    }

    if (externalConn != null) {
      externalConn.closeQuietly();
    }

    if (conversionConn != null) {
      conversionConn.closeQuietly();
    }
  }

  private void disableDatabaseLogging() {
    System.setProperty(LocalLog.LOCAL_LOG_LEVEL_PROPERTY, "INFO");
  }

  private void setupConversion() {
    getLogger().info("Running pre-conversion launch checks");
    ConvertDatabaseConfig conversionDb = configuration.getConversionDb();

    if (configuration.getLocalDb().getHost().equals(conversionDb.getHost()) && configuration.getLocalDb().getName()
                                                                                            .equals(conversionDb
                                                                                                    .getName())) {
      if (!conversionChecks()) {
        return;
      }
    }

    // Begin the converting
    getLogger().info("Conversion will begin shortly. You have 30 seconds to kill the process to abort.");
    try {
      Thread.sleep(30000L);
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
    getLogger().info("Launching conversion process");
    new UUIDConvert(conversionConn);
  }

  private boolean conversionChecks() {
    ConvertDatabaseConfig conversionDb = configuration.getConversionDb();

    if (playerStorage.getTableInfo().getTableName().equals(conversionDb.getTableName("playerIpsTable"))) {
      getLogger().severe("players table equals playerIpsTable, aborting");
      return false;
    }

    if (playerBanStorage.getTableInfo().getTableName().equals(conversionDb.getTableName("bansTable"))) {
      getLogger().severe("playerBans table equals bansTable, aborting");
      return false;
    }

    if (playerBanRecordStorage.getTableInfo().getTableName().equals(conversionDb.getTableName("bansRecordTable"))) {
      getLogger().severe("playerBanRecords table equals bansRecordTable, aborting");
      return false;
    }

    if (playerMuteStorage.getTableInfo().getTableName().equals(conversionDb.getTableName("mutesTable"))) {
      getLogger().severe("playerMutes table equals mutesTable, aborting");
      return false;
    }

    if (playerMuteRecordStorage.getTableInfo().getTableName().equals(conversionDb.getTableName("mutesRecordTable"))) {
      getLogger().severe("playerMuteRecords table equals mutesRecordTable, aborting");
      return false;
    }

    if (playerKickStorage.getTableInfo().getTableName().equals(conversionDb.getTableName("kicksTable"))) {
      getLogger().severe("playerKicks table equals kicksTable, aborting");
      return false;
    }

    if (playerWarnStorage.getTableInfo().getTableName().equals(conversionDb.getTableName("warningsTable"))) {
      getLogger().severe("playerWarnings table equals warningsTable, aborting");
      return false;
    }

    if (ipBanStorage.getTableInfo().getTableName().equals(conversionDb.getTableName("ipBansTable"))) {
      getLogger().severe("ipBans table equals ipBansTable, aborting");
      return false;
    }

    if (ipBanRecordStorage.getTableInfo().getTableName().equals(conversionDb.getTableName("ipBansRecordTable"))) {
      getLogger().severe("ipBanRecords table equals ipBansRecordTable, aborting");
      return false;
    }

    return true;
  }

  @Override
  public String getPermissionBase() {
    return "bm";
  }

  @Override
  public String getPluginFriendlyName() {
    return "BanManager";
  }

  @Override
  public void setupCommands() {
    // Player bans
    new BanCommand().register();
    new TempBanCommand().register();
    new UnbanCommand().register();

    // Player mutes
    new MuteCommand().register();
    new TempMuteCommand().register();
    new UnmuteCommand().register();

    // IP Bans
    new BanIpCommand().register();
    new TempIpBanCommand().register();
    new UnbanIpCommand().register();
    new BanIpRangeCommand().register();
    new TempIpRangeBanCommand().register();
    new UnbanIpRangeCommand().register();

    // Misc
    new ExportCommand().register();
    new ImportCommand().register();
    new FindAltsCommand().register();
    new ReloadCommand().register();
    new InfoCommand().register();
    new BanListCommand().register();
    new ActivityCommand().register();

    // Kicks
    new KickCommand().register();
    new LoglessKickCommand().register();

    new WarnCommand().register();
    new TempWarnCommand().register();
    new DeleteLastWarningCommand().register();

    new AddNoteCommand().register();
    new NotesCommand().register();

    new ClearCommand().register();
    new DeleteCommand().register();

    new SyncCommand().register();

    if (externalConn == null) return;

    new BanAllCommand().register();
    new TempBanAllCommand().register();
    new UnbanAllCommand().register();

    new MuteAllCommand().register();
    new TempMuteAllCommand().register();
    new UnmuteAllCommand().register();

    new BanIpAllCommand().register();
    new TempBanIpAllCommand().register();
    new UnbanIpAllCommand().register();

    new AddNoteAllCommand().register();
  }

  @Override
  public void setupConfigs() {
    new MessagesConfig().load();

    configuration = new DefaultConfig();
    configuration.load();

    consoleConfig = new ConsoleConfig();
    consoleConfig.load();

    schedulesConfig = new SchedulesConfig();
    schedulesConfig.load();

    exemptionsConfig = new ExemptionsConfig();
    exemptionsConfig.load();

    reasonsConfig = new ReasonsConfig();
    reasonsConfig.load();

    geoIpConfig = new GeoIpConfig();
    geoIpConfig.load();
  }

  public boolean setupConnections() throws SQLException {
    if (!configuration.getLocalDb().isEnabled()) {
      getLogger().warning("Local Database is not enabled, disabling plugin");
      plugin.getPluginLoader().disablePlugin(this);
      return false;
    }

    localConn = setupConnection(configuration.getLocalDb());

    if (configuration.getConversionDb().isEnabled()) {
      conversionConn = setupConnection(configuration.getConversionDb());
    }

    if (configuration.getExternalDb().isEnabled()) {
      externalConn = setupConnection(configuration.getExternalDb());
    }

    return true;
  }

  private JdbcPooledConnectionSource setupConnection(DatabaseConfig dbConfig) throws SQLException {
    JdbcPooledConnectionSource connection = new JdbcPooledConnectionSource(dbConfig.getJDBCUrl());

    if (!dbConfig.getUser().isEmpty()) {
      connection.setUsername(dbConfig.getUser());
    }
    if (!dbConfig.getPassword().isEmpty()) {
      connection.setPassword(dbConfig.getPassword());
    }

    connection.setMaxConnectionsFree(dbConfig.getMaxConnections());
    /*
     * There is a memory leak in ormlite-jbcd that means we should not use
     * this. AutoReconnect handles this for us.
     */
    connection.setTestBeforeGet(false);
    /* Keep the connection open for 15 minutes */
    connection.setMaxConnectionAgeMillis(900000);
    /*
     * We should not use this. Auto reconnect does this for us. Waste of
     * packets and CPU.
     */
    connection.setCheckConnectionsEveryMillis(0);
    connection.setDatabaseType(new MySQLDatabase());
    connection.initialize();

    return connection;
  }

  @SuppressWarnings("unchecked")
  public void setupStorages() throws SQLException {
    // TODO Refactor this
    playerStorage = new PlayerStorage(localConn);
    playerBanStorage = new PlayerBanStorage(localConn);
    playerBanRecordStorage = new PlayerBanRecordStorage(localConn);
    playerMuteStorage = new PlayerMuteStorage(localConn);
    playerMuteRecordStorage = new PlayerMuteRecordStorage(localConn);
    playerWarnStorage = new PlayerWarnStorage(localConn);
    playerKickStorage = new PlayerKickStorage(localConn);
    playerNoteStorage = new PlayerNoteStorage(localConn);

    ipBanStorage = new IpBanStorage(localConn);
    ipBanRecordStorage = new IpBanRecordStorage(localConn);
    ipRangeBanStorage = new IpRangeBanStorage(localConn);
    ipRangeBanRecordStorage = new IpRangeBanRecordStorage(localConn);

    activityStorage = new ActivityStorage(localConn);
    historyStorage = new HistoryStorage(localConn);

    if (externalConn == null) {
      return;
    }

    externalPlayerBanStorage = new ExternalPlayerBanStorage(externalConn);
    externalPlayerBanRecordStorage = new ExternalPlayerBanRecordStorage(externalConn);
    externalPlayerMuteStorage = new ExternalPlayerMuteStorage(externalConn);
    externalPlayerMuteRecordStorage = new ExternalPlayerMuteRecordStorage(externalConn);
    externalPlayerNoteStorage = new ExternalPlayerNoteStorage(externalConn);
    externalIpBanStorage = new ExternalIpBanStorage(externalConn);
    externalIpBanRecordStorage = new ExternalIpBanRecordStorage(externalConn);
  }

  @Override
  public void setupListeners() {
    new JoinListener().register();
    new ChatListener().register();
    new CommandListener().register();
    new HookListener().register();

    if (configuration.isDisplayNotificationsEnabled()) {
      new BanListener().register();
      new MuteListener().register();
      new NoteListener().register();
    }
  }

  @Override
  public void setupRunnables() {
    banSync = new BanSync();
    muteSync = new MuteSync();
    ipSync = new IpSync();
    ipRangeSync = new IpRangeSync();
    expiresSync = new ExpiresSync();

    setupAsyncRunnable(schedulesConfig.getSchedule("playerBans"), banSync);
    setupAsyncRunnable(schedulesConfig.getSchedule("playerMutes"), muteSync);
    setupAsyncRunnable(schedulesConfig.getSchedule("ipBans"), ipSync);
    setupAsyncRunnable(schedulesConfig.getSchedule("ipRangeBans"), ipRangeSync);
    setupAsyncRunnable(schedulesConfig.getSchedule("expiresCheck"), expiresSync);

    if (externalConn != null) {
      externalBanSync = new ExternalBanSync();
      externalMuteSync = new ExternalMuteSync();
      externalIpSync = new ExternalIpSync();
      externalNoteSync = new ExternalNoteSync();

      setupAsyncRunnable(schedulesConfig.getSchedule("externalPlayerBans"), externalBanSync);
      setupAsyncRunnable(schedulesConfig.getSchedule("externalPlayerMutes"), externalMuteSync);
      setupAsyncRunnable(schedulesConfig.getSchedule("externalIpBans"), externalIpSync);
      setupAsyncRunnable(schedulesConfig.getSchedule("externalPlayerNotes"), externalNoteSync);
    }

    /*
     * This task should be ran last with a 1L offset as it gets modified
     * above.
     */
    setupAsyncRunnable(schedulesConfig.getSchedule("saveLastChecked") + 1L, new SaveLastChecked());

    // Purge
    getServer().getScheduler().runTaskAsynchronously(plugin, new Purge());

    // TODO Refactor
    if (!getConfiguration().isCheckForUpdates()) return;

    getServer().getScheduler().runTaskAsynchronously(plugin, new Runnable() {

      @Override
      public void run() {
        if (UpdateUtils.isUpdateAvailable(getFile())) {
          getServer().getScheduler().runTask(plugin, new Runnable() {

            @Override
            public void run() {
              new UpdateListener().register();
            }
          });
        }
      }
    });
  }

  private void setupAsyncRunnable(long length, Runnable runnable) {
    if (length <= 0) return;

    getServer().getScheduler().runTaskTimerAsynchronously(plugin, runnable, length, length);
  }
  
  //UUID Provider Integration @MrWisski
  @SuppressWarnings("deprecation")
  public static UUID getUUID(String name){
	  UUID uuid;
	  if(plugin.uuidprov != null) {
		  uuid = UUIDProvider.getCachedPlayer(name);
		  if(uuid == null){
			  //fallback to getting UUID from mojang.
			  uuid = UUIDProvider.uuidFetcher(name);
		  }
		  //if null, this player never even existed!
		  return uuid;
	  }
	
	  return plugin.getServer().getPlayer(name).getUniqueId();
	  
  }
  
  @SuppressWarnings("deprecation")
public static Player getPlayer(UUID uuid){
	  String name;
	  
	  if(plugin.uuidprov != null){
		  
		  name = UUIDProvider.getCachedPlayer(uuid);
		  if(name == ""){
			  //Fallback to getting the name from mojang.
			name = UUIDProvider.nameFetcher(uuid);
		  } 
		  
		  Player p = null;
		  try {
			  //getPlayer likes to throw exceptions. catch, ignore.
			  p = plugin.getServer().getPlayer(name);
		  } catch(Throwable t){
			  return null;
		  }
		  return p;
	  }
	  
	  return plugin.getServer().getPlayer(UUIDProvider.retrieve(uuid));
  }
  
}
