package io.github.freecoreeconomy.database;

import at.favre.lib.crypto.bcrypt.BCrypt;
import at.favre.lib.crypto.bcrypt.BCrypt.Version;
import com.mysql.cj.jdbc.MysqlDataSource;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ThreadPoolExecutor.AbortPolicy;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

public final class DatabaseGateway implements AutoCloseable {
   private static final String UNSET_PASSWORD_PREFIX = "!freecore-unset!";
   private static final SecureRandom SECURE_RANDOM = new SecureRandom();
   private static final DatabaseGateway.SqlStatements MODERN_BLESSING_SKIN = joinedSchema("name", "players.name");
   private static final DatabaseGateway.SqlStatements LEGACY_BLESSING_SKIN = joinedSchema("player_name", "players.player_name");
   private static final DatabaseGateway.SqlStatements DIRECT_USERS = new DatabaseGateway.SqlStatements(
      "users.player_name",
      "SELECT `u`.`score`, `u`.`player_name` FROM `users` AS `u` LIMIT 0",
      "SELECT `u`.`score` FROM `users` AS `u` WHERE `u`.`player_name` = ? LIMIT 1",
      "SELECT `u`.`score` FROM `users` AS `u` WHERE `u`.`player_name` = ? LIMIT 1 FOR UPDATE",
      "SELECT 1 FROM `users` AS `u` WHERE `u`.`player_name` = ? LIMIT 1",
      "UPDATE `users` AS `u` SET `score` = COALESCE(`u`.`score`, 0) + ? WHERE `u`.`player_name` = ? LIMIT 1",
      "UPDATE `users` AS `u` SET `score` = COALESCE(`u`.`score`, 0) - ? WHERE `u`.`player_name` = ? AND COALESCE(`u`.`score`, 0) >= ? LIMIT 1",
      "UPDATE `users` AS `u` SET `score` = ? WHERE `u`.`player_name` = ? LIMIT 1",
      "SELECT `u`.`player_name` AS `player_name`, COALESCE(`u`.`score`, 0) AS `score` FROM `users` AS `u` ORDER BY `u`.`score` DESC, `u`.`player_name` ASC LIMIT ?"
   );
   private final HikariDataSource dataSource;
   private final ThreadPoolExecutor executor;
   private final Logger logger;
   private final long operationTimeoutMs;
   private final int queryTimeoutSeconds;
   private final boolean bedrockAccountsEnabled;
   private final Object bedrockAccountLock = new Object();
   private volatile DatabaseGateway.SqlStatements statements;
   private volatile DatabaseGateway.BedrockSchema bedrockSchema;
   private volatile DatabaseGateway.SkinSchema skinSchema;
   private volatile boolean open = true;

   private DatabaseGateway(HikariDataSource var1, ThreadPoolExecutor var2, Logger var3, long var4, int var6, boolean var7) {
      this.dataSource = var1;
      this.executor = var2;
      this.logger = var3;
      this.operationTimeoutMs = var4;
      this.queryTimeoutSeconds = var6;
      this.bedrockAccountsEnabled = var7;
   }

   public static DatabaseGateway create(Plugin var0, FileConfiguration var1) {
      ConfigurationSection var2 = var1.getConfigurationSection("database");
      if (var2 == null) {
         throw new IllegalArgumentException("Missing database configuration section");
      }

      String var3 = required(var2, "host");
      int var4 = var2.getInt("port", 3306);
      String var5 = required(var2, "name");
      String var6 = required(var2, "username");
      String var7 = var2.getString("password", "");
      boolean var8 = var2.getBoolean("use-ssl", false);
      boolean var9 = var2.getBoolean("allow-public-key-retrieval", !var8);
      int var10 = bounded(var2.getInt("maximum-pool-size", 6), 1, 32);
      int var11 = bounded(var2.getInt("minimum-idle", 1), 0, var10);
      long var12 = Math.max(250L, var2.getLong("connection-timeout-ms", 1500L));
      long var14 = Math.max(250L, var2.getLong("validation-timeout-ms", 1000L));
      long var16 = Math.max(30000L, var2.getLong("max-lifetime-ms", 1800000L));
      long var18 = Math.max(0L, var2.getLong("keepalive-time-ms", 30000L));
      int var20 = Math.max(250, var2.getInt("connect-timeout-ms", 1500));
      int var21 = Math.max(250, var2.getInt("socket-timeout-ms", 2000));
      int var22 = bounded(var2.getInt("query-timeout-seconds", 2), 1, 30);
      long var23 = Math.max(250L, var2.getLong("operation-timeout-ms", 2500L));
      boolean var25 = var1.getBoolean("bedrock-accounts.enabled", true);
      HikariConfig var26 = new HikariConfig();
      var26.setPoolName("FreeCoreEssentials-Hikari");
      var26.setMaximumPoolSize(var10);
      var26.setMinimumIdle(var11);
      var26.setConnectionTimeout(var12);
      var26.setValidationTimeout(Math.min(var14, var12));
      var26.setMaxLifetime(var16);
      if (var18 > 0L) {
         var26.setKeepaliveTime(var18);
      }

      var26.setInitializationFailTimeout(-1L);
      MysqlDataSource var27 = new MysqlDataSource();
      var27.setServerName(var3);
      var27.setPort(var4);
      var27.setDatabaseName(var5);
      var27.setUser(var6);
      var27.setPassword(var7);

      try {
         var27.setUseSSL(var8);
         var27.setRequireSSL(var8);
         var27.setVerifyServerCertificate(var8);
         var27.setAllowPublicKeyRetrieval(var9);
         var27.setConnectTimeout(var20);
         var27.setSocketTimeout(var21);
         var27.setCharacterEncoding("UTF-8");
         var27.setCachePrepStmts(true);
         var27.setPrepStmtCacheSize(128);
         var27.setPrepStmtCacheSqlLimit(2048);
      } catch (SQLException var31) {
         throw new IllegalArgumentException("Invalid MySQL connection setting", var31);
      }

      if (var9 && !var8) {
         var0.getLogger().warning("MySQL public-key retrieval is enabled without SSL; use only on localhost or a trusted network.");
      }

      var26.setDataSource(var27);
      HikariDataSource var28 = new HikariDataSource(var26);
      DatabaseGateway.DatabaseThreadFactory var29 = new DatabaseGateway.DatabaseThreadFactory(var0.getName());
      ThreadPoolExecutor var30 = new ThreadPoolExecutor(var10, var10, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(var10 * 32), var29, new AbortPolicy());
      return new DatabaseGateway(var28, var30, var0.getLogger(), var23, var22, var25);
   }

   public CompletableFuture<Void> verifySchemaAsync() {
      return this.submitAsync(() -> {
         this.verifySchemaNow();
         return null;
      });
   }

   public CompletableFuture<java.util.List<LeaderboardEntry>> fetchLeaderboardAsync(int var1) {
      int var2 = bounded(var1, 1, 1000);
      DatabaseGateway.SqlStatements var3 = this.statements();
      return this.submitAsync(() -> {
         try (
            Connection var4 = this.dataSource.getConnection();
            PreparedStatement var5 = var4.prepareStatement(var3.leaderboard());
         ) {
            var5.setQueryTimeout(this.queryTimeoutSeconds);
            var5.setInt(1, var2);

            try (ResultSet var6 = var5.executeQuery()) {
               java.util.List<LeaderboardEntry> var7 = new java.util.ArrayList<>();
               while (var6.next()) {
                  String var8 = var6.getString("player_name");
                  if (var8 != null && !var8.isBlank()) {
                     BigDecimal var9 = var6.getBigDecimal("score");
                     var7.add(new LeaderboardEntry(var8, var9 == null ? BigDecimal.ZERO : var9));
                  }
               }

               return java.util.List.copyOf(var7);
            }
         }
      });
   }

   private void verifySchemaNow() throws DatabaseException {
      SQLException var1 = null;
      DatabaseGateway.SqlStatements var2 = null;

      for (DatabaseGateway.SqlStatements var6 : new DatabaseGateway.SqlStatements[]{MODERN_BLESSING_SKIN, LEGACY_BLESSING_SKIN, DIRECT_USERS}) {
         try (
            Connection var7 = this.dataSource.getConnection();
            Statement var8 = var7.createStatement();
         ) {
            var8.setQueryTimeout(this.queryTimeoutSeconds);
            var8.executeQuery(var6.verify());
            var2 = var6;
            break;
         } catch (SQLException var15) {
            if (var1 != null) {
               var15.addSuppressed(var1);
            }

            var1 = var15;
         }
      }

      if (var2 == null) {
         throw new DatabaseException(
            "Unsupported Blessing Skin schema. Expected players.name (or legacy players.player_name) joined to users by uid, or a direct users.player_name column.",
            var1
         );
      }

      this.statements = var2;
      this.logger.info("Detected Blessing Skin balance schema: " + var2.description());
      this.skinSchema = this.detectSkinSchema();
      if (this.skinSchema != null) {
         this.logger.info("Detected Blessing Skin texture schema: " + this.skinSchema.description());
      } else {
         this.logger.warning("Blessing Skin texture tables were not detected; the cross-edition skin bridge is unavailable.");
      }

      if (this.bedrockAccountsEnabled) {
         this.bedrockSchema = this.detectBedrockSchema();
         this.logger.info("Detected Blessing Skin Bedrock account schema: " + this.bedrockSchema.description());
      }
   }

   private DatabaseGateway.BedrockSchema detectBedrockSchema() throws DatabaseException {
      SQLException var1 = null;

      for (DatabaseGateway.BedrockSchema var5 : new DatabaseGateway.BedrockSchema[]{
         joinedBedrockSchema("name", "users.xuid with players.name"),
         joinedBedrockSchema("player_name", "users.xuid with players.player_name"),
         directBedrockSchema()
      }) {
         try (
            Connection var6 = this.dataSource.getConnection();
            Statement var7 = var6.createStatement();
         ) {
            var7.setQueryTimeout(this.queryTimeoutSeconds);
            var7.executeQuery(var5.verify());
            return var5;
         } catch (SQLException var14) {
            if (var1 != null) {
               var14.addSuppressed(var1);
            }

            var1 = var14;
         }
      }

      throw new DatabaseException(
         "Bedrock account schema is unavailable. Add users.xuid as VARCHAR(32) and ensure the Blessing Skin account columns are present. Recommended SQL: ALTER TABLE users ADD COLUMN xuid VARCHAR(32) NULL, ADD UNIQUE KEY uq_users_xuid (xuid)",
         var1
      );
   }

   public CompletableFuture<BedrockAccountResult> syncBedrockAccountAsync(String var1, String var2) {
      if (var1 == null || var1.isBlank()) {
         return CompletableFuture.failedFuture(new IllegalArgumentException("Floodgate XUID is blank"));
      } else {
         return var2 != null && !var2.isBlank()
            ? this.submitAsync(
               () -> {
                  DatabaseGateway.BedrockSchema var3 = this.bedrockSchema();
                  synchronized (this.bedrockAccountLock) {
                     return this.inTransaction(
                        var4 -> var3.joined() ? this.syncJoinedBedrockAccount(var4, var3, var1, var2) : this.syncDirectBedrockAccount(var4, var3, var1, var2)
                     );
                  }
               }
            )
            : CompletableFuture.failedFuture(new IllegalArgumentException("Floodgate username is blank"));
      }
   }

   public CompletableFuture<Boolean> setBedrockPasswordAsync(String var1, char[] var2, int var3) {
      if (var1 != null && !var1.isBlank()) {
         int var4 = bounded(var3, 10, 15);
         return this.submitAsync(() -> {
            String var4x;
            try {
               var4x = BCrypt.with(Version.VERSION_2Y).hashToString(var4, var2);
            } finally {
               Arrays.fill(var2, '\u0000');
            }

            try (
               Connection var5 = this.dataSource.getConnection();
               PreparedStatement var6 = var5.prepareStatement("UPDATE `users` SET `password` = ? WHERE `xuid` = ? LIMIT 1");
            ) {
               var6.setQueryTimeout(this.queryTimeoutSeconds);
               var6.setString(1, var4x);
               var6.setString(2, var1);
               return var6.executeUpdate() == 1;
            }
         });
      } else {
         Arrays.fill(var2, '\u0000');
         return CompletableFuture.failedFuture(new IllegalArgumentException("Floodgate XUID is blank"));
      }
   }

   public boolean skinIntegrationAvailable() {
      return this.skinSchema != null;
   }

   public CompletableFuture<Optional<BlessingSkinTexture>> findSkinByUsernameAsync(String var1) {
      if (var1 != null && !var1.isBlank()) {
         DatabaseGateway.SkinSchema var2 = this.skinSchema;
         return var2 == null ? CompletableFuture.completedFuture(Optional.empty()) : this.findSkinAsync(var2.selectByUsername(), var1);
      } else {
         return CompletableFuture.completedFuture(Optional.empty());
      }
   }

   public CompletableFuture<Optional<BlessingSkinTexture>> findSkinByXuidAsync(String var1) {
      if (var1 != null && !var1.isBlank()) {
         DatabaseGateway.SkinSchema var2 = this.skinSchema;
         return var2 != null && var2.selectByXuid() != null
            ? this.findSkinAsync(var2.selectByXuid(), var1)
            : CompletableFuture.completedFuture(Optional.empty());
      } else {
         return CompletableFuture.completedFuture(Optional.empty());
      }
   }

   private CompletableFuture<Optional<BlessingSkinTexture>> findSkinAsync(String var1, String var2) {
      return this.submitAsync(() -> {
         try (
            Connection var3 = this.dataSource.getConnection();
            PreparedStatement var4 = var3.prepareStatement(var1);
         ) {
            var4.setQueryTimeout(this.queryTimeoutSeconds);
            var4.setString(1, var2);

            try (ResultSet var5 = var4.executeQuery()) {
               if (!var5.next()) {
                  return Optional.empty();
               }

               String var6 = var5.getString("hash");
               String var7 = var5.getString("type");
               return var6 != null && !var6.isBlank() && var7 != null && !var7.isBlank() ? Optional.of(new BlessingSkinTexture(var6, var7)) : Optional.empty();
            }
         }
      });
   }

   private DatabaseGateway.SkinSchema detectSkinSchema() {
      String var1 = MODERN_BLESSING_SKIN.equals(this.statements) ? "name" : (LEGACY_BLESSING_SKIN.equals(this.statements) ? "player_name" : null);
      if (var1 == null) {
         return null;
      }

      String var2 = "`p`." + quoted(var1);
      String var3 = " FROM `players` AS `p` INNER JOIN `users` AS `u` ON `u`.`uid` = `p`.`uid` INNER JOIN `textures` AS `t` ON `t`.`tid` = `p`.`tid_skin` ";
      DatabaseGateway.SkinSchema var4 = new DatabaseGateway.SkinSchema(
         "players.tid_skin -> textures.hash/type",
         "SELECT `t`.`hash`, `t`.`type`" + var3 + "WHERE " + var2 + " = ? LIMIT 1",
         "SELECT `t`.`hash`, `t`.`type`" + var3 + "WHERE `u`.`xuid` = ? LIMIT 1",
         "SELECT `p`.`tid_skin`, `t`.`hash`, `t`.`type`" + var3 + "LIMIT 0"
      );

      try (
         Connection var5 = this.dataSource.getConnection();
         Statement var6 = var5.createStatement();
      ) {
         var6.setQueryTimeout(this.queryTimeoutSeconds);
         var6.executeQuery(var4.verify());
         return var4;
      } catch (SQLException var13) {
         this.logger.fine("Blessing Skin texture schema detection failed: " + var13.getMessage());
         return null;
      }
   }

   private BedrockAccountResult syncJoinedBedrockAccount(Connection var1, DatabaseGateway.BedrockSchema var2, String var3, String var4) throws SQLException {
      String var5 = quoted(var2.playerColumn());
      String var6 = "SELECT `u`.`uid`, `u`.`email`, `u`.`password`, `p`.`pid`, `p`."
         + var5
         + " FROM `users` AS `u` LEFT JOIN `players` AS `p` ON `p`.`uid` = `u`.`uid` WHERE `u`.`xuid` = ? ORDER BY `p`.`pid` LIMIT 1 FOR UPDATE";

      try (PreparedStatement var7 = var1.prepareStatement(var6)) {
         var7.setQueryTimeout(this.queryTimeoutSeconds);
         var7.setString(1, var3);

         try (ResultSet var8 = var7.executeQuery()) {
            if (var8.next()) {
               long var28 = var8.getLong(1);
               String var11 = var8.getString(2);
               String var12 = var8.getString(3);
               Long var13 = var8.getObject(4) == null ? null : var8.getLong(4);
               String var14 = var8.getString(5);
               String var15 = this.chooseAvailableName(var1, var2, var4, var28);
               boolean var16 = var14 == null || !var14.equals(var15);
               if (var13 == null) {
                  this.insertPlayer(var1, var2, var28, var15);
               } else if (var16) {
                  try (PreparedStatement var17 = var1.prepareStatement("UPDATE `players` SET " + var5 + " = ?, `last_modified` = NOW() WHERE `pid` = ? LIMIT 1")) {
                     var17.setQueryTimeout(this.queryTimeoutSeconds);
                     var17.setString(1, var15);
                     var17.setLong(2, var13);
                     var17.executeUpdate();
                  }
               }

               try (PreparedStatement var29 = var1.prepareStatement("UPDATE `users` SET `nickname` = ? WHERE `uid` = ? LIMIT 1")) {
                  var29.setQueryTimeout(this.queryTimeoutSeconds);
                  var29.setString(1, var15);
                  var29.setLong(2, var28);
                  var29.executeUpdate();
               }

               return new BedrockAccountResult(
                  var16 ? BedrockAccountResult.Status.RENAMED : BedrockAccountResult.Status.UNCHANGED,
                  var15,
                  var11 != null && !var11.isBlank() ? var11 : bedrockEmail(var3),
                  passwordWasSet(var12),
                  !var15.equals(var4)
               );
            } else {
               return this.createJoinedBedrockAccount(var1, var2, var3, var4);
            }
         }
      }
   }

   private BedrockAccountResult createJoinedBedrockAccount(Connection var1, DatabaseGateway.BedrockSchema var2, String var3, String var4) throws SQLException {
      String var5 = this.chooseAvailableName(var1, var2, var4, -1L);
      String var6 = bedrockEmail(var3);

      long var7;
      try (PreparedStatement var9 = var1.prepareStatement(
            "INSERT INTO `users` (`email`, `nickname`, `score`, `password`, `ip`, `last_sign_at`, `register_at`, `xuid`) VALUES (?, ?, 0, ?, '0.0.0.0', NOW(), NOW(), ?)",
            1
         )) {
         var9.setQueryTimeout(this.queryTimeoutSeconds);
         var9.setString(1, var6);
         var9.setString(2, var5);
         var9.setString(3, placeholderPassword());
         var9.setString(4, var3);
         if (var9.executeUpdate() != 1) {
            throw new SQLException("Could not insert the Blessing Skin user");
         }

         try (ResultSet var10 = var9.getGeneratedKeys()) {
            if (!var10.next()) {
               throw new SQLException("Blessing Skin did not return the generated uid");
            }

            var7 = var10.getLong(1);
         }
      }

      this.insertPlayer(var1, var2, var7, var5);
      return new BedrockAccountResult(BedrockAccountResult.Status.CREATED, var5, var6, false, !var5.equals(var4));
   }

   private void insertPlayer(Connection var1, DatabaseGateway.BedrockSchema var2, long var3, String var5) throws SQLException {
      String var6 = "INSERT INTO `players` (`uid`, " + quoted(var2.playerColumn()) + ", `last_modified`) VALUES (?, ?, NOW())";

      try (PreparedStatement var7 = var1.prepareStatement(var6)) {
         var7.setQueryTimeout(this.queryTimeoutSeconds);
         var7.setLong(1, var3);
         var7.setString(2, var5);
         if (var7.executeUpdate() != 1) {
            throw new SQLException("Could not insert the Blessing Skin player record");
         }
      }
   }

   private BedrockAccountResult syncDirectBedrockAccount(Connection var1, DatabaseGateway.BedrockSchema var2, String var3, String var4) throws SQLException {
      try (PreparedStatement var5 = var1.prepareStatement("SELECT `uid`, `email`, `password`, `player_name` FROM `users` WHERE `xuid` = ? LIMIT 1 FOR UPDATE")) {
         var5.setQueryTimeout(this.queryTimeoutSeconds);
         var5.setString(1, var3);

         try (ResultSet var6 = var5.executeQuery()) {
            if (var6.next()) {
               long var23 = var6.getLong(1);
               String var9 = var6.getString(2);
               String var10 = var6.getString(3);
               String var11 = var6.getString(4);
               String var12 = this.chooseAvailableName(var1, var2, var4, var23);
               boolean var13 = !var12.equals(var11);
               if (var13) {
                  try (PreparedStatement var14 = var1.prepareStatement("UPDATE `users` SET `player_name` = ? WHERE `uid` = ? LIMIT 1")) {
                     var14.setQueryTimeout(this.queryTimeoutSeconds);
                     var14.setString(1, var12);
                     var14.setLong(2, var23);
                     var14.executeUpdate();
                  }
               }

               return new BedrockAccountResult(
                  var13 ? BedrockAccountResult.Status.RENAMED : BedrockAccountResult.Status.UNCHANGED,
                  var12,
                  var9 != null && !var9.isBlank() ? var9 : bedrockEmail(var3),
                  passwordWasSet(var10),
                  !var12.equals(var4)
               );
            } else {
               return this.createDirectBedrockAccount(var1, var2, var3, var4);
            }
         }
      }
   }

   private BedrockAccountResult createDirectBedrockAccount(Connection var1, DatabaseGateway.BedrockSchema var2, String var3, String var4) throws SQLException {
      String var5 = this.chooseAvailableName(var1, var2, var4, -1L);
      String var6 = bedrockEmail(var3);

      try (PreparedStatement var7 = var1.prepareStatement("INSERT INTO `users` (`player_name`, `email`, `password`, `score`, `xuid`) VALUES (?, ?, ?, 0, ?)")) {
         var7.setQueryTimeout(this.queryTimeoutSeconds);
         var7.setString(1, var5);
         var7.setString(2, var6);
         var7.setString(3, placeholderPassword());
         var7.setString(4, var3);
         if (var7.executeUpdate() != 1) {
            throw new SQLException("Could not insert the Bedrock user");
         }
      }

      return new BedrockAccountResult(BedrockAccountResult.Status.CREATED, var5, var6, false, !var5.equals(var4));
   }

   private String chooseAvailableName(Connection var1, DatabaseGateway.BedrockSchema var2, String var3, long var4) throws SQLException {
      String var6 = normalizePlayerName(var3, var2.nameMaxLength());
      String var7 = var6;
      int var8 = 0;

      while (this.nameIsTaken(var1, var2, var7, var4)) {
         if (++var8 >= var2.nameMaxLength()) {
            throw new SQLException("Could not allocate a unique player name for " + var3);
         }

         String var9 = "_".repeat(var8);
         int var10 = Math.min(var6.length(), var2.nameMaxLength() - var9.length());
         var7 = var6.substring(0, var10) + var9;
      }

      return var7;
   }

   private boolean nameIsTaken(Connection var1, DatabaseGateway.BedrockSchema var2, String var3, long var4) throws SQLException {
      String var6;
      if (var2.joined()) {
         var6 = "SELECT 1 FROM `players` WHERE " + quoted(var2.playerColumn()) + " = ? AND `uid` <> ? LIMIT 1";
      } else {
         var6 = "SELECT 1 FROM `users` WHERE `player_name` = ? AND `uid` <> ? LIMIT 1";
      }

      try (PreparedStatement var7 = var1.prepareStatement(var6)) {
         var7.setQueryTimeout(this.queryTimeoutSeconds);
         var7.setString(1, var3);
         var7.setLong(2, var4);

         try (ResultSet var8 = var7.executeQuery()) {
            return var8.next();
         }
      }
   }

   private static String normalizePlayerName(String var0, int var1) {
      String var2 = var0.trim();
      if (var2.isEmpty()) {
         var2 = "BedrockPlayer";
      }

      return var2.length() <= var1 ? var2 : var2.substring(0, var1);
   }

   private static String placeholderPassword() {
      byte[] var0 = new byte[32];
      SECURE_RANDOM.nextBytes(var0);
      return "!freecore-unset!" + Base64.getUrlEncoder().withoutPadding().encodeToString(var0);
   }

   private static boolean passwordWasSet(String var0) {
      return var0 != null && !var0.isBlank() && !var0.startsWith("!freecore-unset!");
   }

   private static String bedrockEmail(String var0) {
      return var0 + "@bedrock.local";
   }

   private static String quoted(String var0) {
      return "`" + var0 + "`";
   }

   public Optional<BigDecimal> getBalance(String var1) throws DatabaseException {
      DatabaseGateway.SqlStatements var2 = this.statements();
      return this.execute(() -> {
         try (
            Connection var3 = this.dataSource.getConnection();
            PreparedStatement var4 = var3.prepareStatement(var2.selectBalance());
         ) {
            var4.setQueryTimeout(this.queryTimeoutSeconds);
            var4.setString(1, var1);

            try (ResultSet var5 = var4.executeQuery()) {
               if (!var5.next()) {
                  return Optional.empty();
               }

               BigDecimal var6 = var5.getBigDecimal(1);
               return Optional.of(var6 == null ? BigDecimal.ZERO : var6);
            }
         }
      });
   }

   public boolean hasAccount(String var1) throws DatabaseException {
      DatabaseGateway.SqlStatements var2 = this.statements();
      return this.execute(() -> {
         try (
            Connection var3 = this.dataSource.getConnection();
            PreparedStatement var4 = var3.prepareStatement(var2.selectAccount());
         ) {
            var4.setQueryTimeout(this.queryTimeoutSeconds);
            var4.setString(1, var1);

            try (ResultSet var5 = var4.executeQuery()) {
               return var5.next();
            }
         }
      });
   }

   public TransactionOutcome deposit(String var1, BigDecimal var2) throws DatabaseException {
      DatabaseGateway.SqlStatements var3 = this.statements();
      return this.execute(() -> this.inTransaction(var4 -> {
         try (PreparedStatement var5 = var4.prepareStatement(var3.deposit())) {
            var5.setQueryTimeout(this.queryTimeoutSeconds);
            var5.setBigDecimal(1, var2);
            var5.setString(2, var1);
            if (var5.executeUpdate() != 1) {
               return TransactionOutcome.accountNotFound();
            }
         }

         return TransactionOutcome.success(this.requireBalance(var4, var1));
      }));
   }

   public TransactionOutcome withdraw(String var1, BigDecimal var2) throws DatabaseException {
      DatabaseGateway.SqlStatements var3 = this.statements();
      return this.execute(() -> this.inTransaction(var4 -> {
         try (PreparedStatement var5 = var4.prepareStatement(var3.withdraw())) {
            var5.setQueryTimeout(this.queryTimeoutSeconds);
            var5.setBigDecimal(1, var2);
            var5.setString(2, var1);
            var5.setBigDecimal(3, var2);
            if (var5.executeUpdate() == 1) {
               return TransactionOutcome.success(this.requireBalance(var4, var1));
            }
         }

         Optional<BigDecimal> var10 = this.selectBalance(var4, var1);
         return var10.map(TransactionOutcome::insufficientFunds).orElseGet(TransactionOutcome::accountNotFound);
      }));
   }

   public TransactionOutcome setBalance(String var1, BigDecimal var2) throws DatabaseException {
      DatabaseGateway.SqlStatements var3 = this.statements();
      return this.execute(() -> this.inTransaction(var4 -> {
         Optional var5 = this.selectBalanceForUpdate(var4, var1);
         if (var5.isEmpty()) {
            return TransactionOutcome.accountNotFound();
         }

         if (((BigDecimal)var5.get()).compareTo(var2) == 0) {
            return TransactionOutcome.success((BigDecimal)var5.get());
         }

         try (PreparedStatement var6 = var4.prepareStatement(var3.setBalance())) {
            var6.setQueryTimeout(this.queryTimeoutSeconds);
            var6.setBigDecimal(1, var2);
            var6.setString(2, var1);
            if (var6.executeUpdate() != 1) {
               throw new SQLException("Account disappeared while setting balance");
            }
         }

         return TransactionOutcome.success(this.requireBalance(var4, var1));
      }));
   }

   public TransferOutcome transfer(String var1, String var2, BigDecimal var3) throws DatabaseException {
      DatabaseGateway.SqlStatements var4 = this.statements();
      return this.execute(() -> this.inTransaction(var5 -> {
         String var6 = String.CASE_INSENSITIVE_ORDER.compare(var1, var2) <= 0 ? var1 : var2;
         String var7 = var6.equals(var1) ? var2 : var1;
         Optional var8 = this.selectBalanceForUpdate(var5, var6);
         Optional var9 = this.selectBalanceForUpdate(var5, var7);
         Optional var10 = var6.equals(var1) ? var8 : var9;
         Optional var11 = var6.equals(var1) ? var9 : var8;
         if (var10.isEmpty()) {
            return TransferOutcome.sourceNotFound();
         }

         if (var11.isEmpty()) {
            return TransferOutcome.targetNotFound((BigDecimal)var10.get());
         }

         if (((BigDecimal)var10.get()).compareTo(var3) < 0) {
            return TransferOutcome.insufficientFunds((BigDecimal)var10.get(), (BigDecimal)var11.get());
         }

         try (PreparedStatement var12 = var5.prepareStatement(var4.withdraw())) {
            var12.setQueryTimeout(this.queryTimeoutSeconds);
            var12.setBigDecimal(1, var3);
            var12.setString(2, var1);
            var12.setBigDecimal(3, var3);
            if (var12.executeUpdate() != 1) {
               throw new SQLException("Source account changed during transfer");
            }
         }

         try (PreparedStatement var19 = var5.prepareStatement(var4.deposit())) {
            var19.setQueryTimeout(this.queryTimeoutSeconds);
            var19.setBigDecimal(1, var3);
            var19.setString(2, var2);
            if (var19.executeUpdate() != 1) {
               throw new SQLException("Target account changed during transfer");
            }
         }

         return TransferOutcome.success(this.requireBalance(var5, var1), this.requireBalance(var5, var2));
      }));
   }

   private BigDecimal requireBalance(Connection var1, String var2) throws SQLException {
      return this.selectBalance(var1, var2).orElseThrow(() -> new SQLException("Account disappeared during transaction"));
   }

   private Optional<BigDecimal> selectBalance(Connection var1, String var2) throws SQLException {
      try (PreparedStatement var3 = var1.prepareStatement(this.statements().selectBalance())) {
         var3.setQueryTimeout(this.queryTimeoutSeconds);
         var3.setString(1, var2);

         try (ResultSet var4 = var3.executeQuery()) {
            if (var4.next()) {
               BigDecimal var11 = var4.getBigDecimal(1);
               return Optional.of(var11 == null ? BigDecimal.ZERO : var11);
            } else {
               return Optional.empty();
            }
         }
      }
   }

   private Optional<BigDecimal> selectBalanceForUpdate(Connection var1, String var2) throws SQLException {
      try (PreparedStatement var3 = var1.prepareStatement(this.statements().selectBalanceForUpdate())) {
         var3.setQueryTimeout(this.queryTimeoutSeconds);
         var3.setString(1, var2);

         try (ResultSet var4 = var3.executeQuery()) {
            if (var4.next()) {
               BigDecimal var11 = var4.getBigDecimal(1);
               return Optional.of(var11 == null ? BigDecimal.ZERO : var11);
            } else {
               return Optional.empty();
            }
         }
      }
   }

   private <T> T inTransaction(DatabaseGateway.TransactionWork<T> var1) throws SQLException {
      try (Connection var2 = this.dataSource.getConnection()) {
         boolean var3 = var2.getAutoCommit();
         var2.setAutoCommit(false);

         try {
            Object var4 = var1.run(var2);
            var2.commit();
            return (T)var4;
         } catch (SQLException | RuntimeException var14) {
            try {
               var2.rollback();
            } catch (SQLException var13) {
               var14.addSuppressed(var13);
            }

            throw var14;
         } finally {
            var2.setAutoCommit(var3);
         }
      }
   }

   private <T> T execute(DatabaseGateway.SqlWork<T> var1) throws DatabaseException {
      if (!this.open) {
         throw new DatabaseException("Database service is closed");
      }

      Future var2;
      try {
         var2 = this.executor.submit(var1::run);
      } catch (RejectedExecutionException var5) {
         throw new DatabaseException("Database work queue is full", var5);
      }

      try {
         return (T)var2.get(this.operationTimeoutMs, TimeUnit.MILLISECONDS);
      } catch (TimeoutException var6) {
         var2.cancel(true);
         throw new DatabaseException("Database operation exceeded " + this.operationTimeoutMs + " ms", var6);
      } catch (InterruptedException var7) {
         var2.cancel(true);
         Thread.currentThread().interrupt();
         throw new DatabaseException("Database operation was interrupted", var7);
      } catch (ExecutionException var8) {
         Throwable var4 = var8.getCause();
         throw new DatabaseException("Database operation failed: " + (var4 == null ? "unknown error" : var4.getMessage()), var4 == null ? var8 : var4);
      }
   }

   private <T> CompletableFuture<T> submitAsync(DatabaseGateway.SqlWork<T> var1) {
      CompletableFuture var2 = new CompletableFuture();
      if (!this.open) {
         var2.completeExceptionally(new DatabaseException("Database service is closed"));
         return var2;
      }

      try {
         this.executor.execute(() -> {
            try {
               var2.complete(var1.run());
            } catch (Throwable var4x) {
               if (var4x instanceof DatabaseException var3) {
                  var2.completeExceptionally(var3);
               } else {
                  var2.completeExceptionally(new DatabaseException("Database operation failed: " + var4x.getMessage(), var4x));
               }
            }
         });
      } catch (RejectedExecutionException var4) {
         var2.completeExceptionally(new DatabaseException("Database work queue is full", var4));
      }

      return var2;
   }

   @Override
   public void close() {
      this.open = false;
      this.executor.shutdownNow();
      this.dataSource.close();
   }

   private static String required(ConfigurationSection var0, String var1) {
      String var2 = var0.getString(var1);
      if (var2 != null && !var2.isBlank()) {
         return var2;
      } else {
         throw new IllegalArgumentException("database." + var1 + " must not be blank");
      }
   }

   private static int bounded(int var0, int var1, int var2) {
      return Math.max(var1, Math.min(var2, var0));
   }

   private DatabaseGateway.SqlStatements statements() {
      DatabaseGateway.SqlStatements var1 = this.statements;
      if (var1 == null) {
         throw new IllegalStateException("Blessing Skin schema has not been verified");
      } else {
         return var1;
      }
   }

   private DatabaseGateway.BedrockSchema bedrockSchema() {
      DatabaseGateway.BedrockSchema var1 = this.bedrockSchema;
      if (var1 == null) {
         throw new IllegalStateException("Blessing Skin Bedrock account schema has not been verified");
      } else {
         return var1;
      }
   }

   private static DatabaseGateway.SqlStatements joinedSchema(String var0, String var1) {
      String var2 = "`p`.`" + var0 + "`";
      String var3 = " FROM `users` AS `u` INNER JOIN `players` AS `p` ON `p`.`uid` = `u`.`uid` ";
      String var4 = "(SELECT `p`.`uid` FROM `players` AS `p` WHERE " + var2 + " = ? LIMIT 1)";
      return new DatabaseGateway.SqlStatements(
         var1 + " -> users.score via uid",
         "SELECT `u`.`score`, " + var2 + var3 + "LIMIT 0",
         "SELECT `u`.`score`" + var3 + "WHERE " + var2 + " = ? LIMIT 1",
         "SELECT `u`.`score` FROM `users` AS `u` WHERE `u`.`uid` = " + var4 + " LIMIT 1 FOR UPDATE",
         "SELECT 1" + var3 + "WHERE " + var2 + " = ? LIMIT 1",
         "UPDATE `users` AS `u` SET `score` = COALESCE(`u`.`score`, 0) + ? WHERE `u`.`uid` = " + var4 + " LIMIT 1",
         "UPDATE `users` AS `u` SET `score` = COALESCE(`u`.`score`, 0) - ? WHERE `u`.`uid` = " + var4 + " AND COALESCE(`u`.`score`, 0) >= ? LIMIT 1",
         "UPDATE `users` AS `u` SET `score` = ? WHERE `u`.`uid` = " + var4 + " LIMIT 1",
         "SELECT " + var2 + " AS `player_name`, COALESCE(`u`.`score`, 0) AS `score`" + var3 + "ORDER BY `u`.`score` DESC, " + var2 + " ASC LIMIT ?"
      );
   }

   private static DatabaseGateway.BedrockSchema joinedBedrockSchema(String var0, String var1) {
      String var2 = "`p`." + quoted(var0);
      return new DatabaseGateway.BedrockSchema(
         true,
         var0,
         50,
         var1,
         "SELECT `u`.`uid`, `u`.`xuid`, `u`.`email`, `u`.`nickname`, `u`.`password`, `u`.`score`, `u`.`ip`, `u`.`last_sign_at`, `u`.`register_at`, `p`.`pid`, "
            + var2
            + ", `p`.`last_modified` FROM `users` AS `u` LEFT JOIN `players` AS `p` ON `p`.`uid` = `u`.`uid` LIMIT 0"
      );
   }

   private static DatabaseGateway.BedrockSchema directBedrockSchema() {
      return new DatabaseGateway.BedrockSchema(
         false,
         "player_name",
         50,
         "users.xuid with users.player_name",
         "SELECT `uid`, `xuid`, `player_name`, `email`, `password`, `score` FROM `users` LIMIT 0"
      );
   }

   private record BedrockSchema(boolean joined, String playerColumn, int nameMaxLength, String description, String verify) {
   }

   private static final class DatabaseThreadFactory implements ThreadFactory {
      private final AtomicInteger counter = new AtomicInteger();
      private final String prefix;

      private DatabaseThreadFactory(String var1) {
         this.prefix = var1 + "-Database-";
      }

      @Override
      public Thread newThread(Runnable var1) {
         Thread var2 = new Thread(var1, this.prefix + this.counter.incrementAndGet());
         var2.setDaemon(true);
         return var2;
      }
   }

   private record SkinSchema(String description, String selectByUsername, String selectByXuid, String verify) {
   }

   private record SqlStatements(
      String description,
      String verify,
      String selectBalance,
      String selectBalanceForUpdate,
      String selectAccount,
      String deposit,
      String withdraw,
      String setBalance,
      String leaderboard
   ) {
   }

   public record LeaderboardEntry(String playerName, BigDecimal balance) {
   }

   @FunctionalInterface
   private interface SqlWork<T> {
      T run() throws Exception;
   }

   @FunctionalInterface
   private interface TransactionWork<T> {
      T run(Connection var1) throws SQLException;
   }
}
