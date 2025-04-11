package xyz.nikitacartes.easyauth.storage.database;

import net.minecraft.util.Uuids;
import xyz.nikitacartes.easyauth.EasyAuth;
import xyz.nikitacartes.easyauth.config.StorageConfigV1;
import xyz.nikitacartes.easyauth.storage.PlayerEntryV1;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.File;
import java.sql.*;
import java.util.HashMap;
import java.util.Locale;

import static xyz.nikitacartes.easyauth.EasyAuth.extendedConfig;
import static xyz.nikitacartes.easyauth.utils.EasyLogger.*;

public class SQLite implements DbApi {
    private final StorageConfigV1 config;
    private Connection connection;

    public SQLite(StorageConfigV1 config) {
        this.config = config;
    }

    @Override
    public void connect() throws DBApiException {
        try {
            // 加载SQLite JDBC驱动
            Class.forName("org.sqlite.JDBC");

            // 连接到数据库文件
            File dbFile = new File(EasyAuth.gameDirectory + "/" + config.sqlite.sqlitePath);
            if (!dbFile.getParentFile().exists() && !dbFile.getParentFile().mkdirs()) {
                throw new DBApiException("无法为SQLite数据库创建目录", null);
            }
            String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();
            connection = DriverManager.getConnection(url);

            // 如果表不存在，则创建表
            Statement statement = connection.createStatement();
            statement.executeUpdate(
                    """
                            CREATE TABLE IF NOT EXISTS %s (
                                id INTEGER PRIMARY KEY AUTOINCREMENT,
                                username TEXT UNIQUE NOT NULL,
                                username_lower TEXT NOT NULL,
                                uuid TEXT NULL,
                                data TEXT NOT NULL
                            );
                            """.formatted(config.sqlite.sqliteTable)
            );
            statement.close();

            LogDebug("成功连接到SQLite数据库。");
        } catch (ClassNotFoundException | SQLException e) {
            throw new DBApiException("设置SQLite数据库失败", e);
        }
    }

    @Override
    public void close() {
        try {
            if (connection != null) {
                connection.close();
                connection = null;
                LogInfo("成功关闭SQLite数据库连接。");
            }
        } catch (SQLException e) {
            LogError("关闭SQLite数据库连接时出错", e);
        }
    }

    @Override
    public boolean isClosed() {
        return connection == null;
    }

    @Override
    public void registerUser(PlayerEntryV1 data) {
        try {
            // 插入用户数据到数据库
            PreparedStatement statement = connection.prepareStatement("INSERT INTO " + config.sqlite.sqliteTable + " (username, username_lower, uuid, data) VALUES (?, ?, ?, ?);");
            statement.setString(1, data.username); // 设置用户名
            statement.setString(2, data.usernameLowerCase); // 设置小写用户名
            statement.setObject(3, data.uuid); // 设置UUID
            statement.setString(4, data.toJson()); // 设置用户数据的JSON表示
            statement.executeUpdate();
            statement.close();
        } catch (SQLException e) {
            LogError("在SQLite数据库中注册用户时出错: " + data, e);
        }
    }

    @Override
    public @Nullable PlayerEntryV1 getUserData(String username) {
        try {
            PreparedStatement statement;
            // 根据配置决定是否区分大小写查询用户名
            if (extendedConfig.allowCaseInsensitiveUsername) {
                statement = connection.prepareStatement("SELECT username, username_lower, uuid, data FROM " + config.sqlite.sqliteTable + " WHERE username = ?;");
                statement.setString(1, username);
            } else {
                statement = connection.prepareStatement("SELECT username, username_lower, uuid, data FROM " + config.sqlite.sqliteTable + " WHERE username_lower = ?;");
                statement.setString(1, username.toLowerCase(Locale.ENGLISH));
            }
            ResultSet resultSet = statement.executeQuery();
            PlayerEntryV1 playerEntry = null;

            // 如果找到匹配的用户数据，创建PlayerEntryV1对象
            if (resultSet.next()) {
                playerEntry = new PlayerEntryV1(resultSet.getString("username"),
                                                resultSet.getString("username_lower"),
                                                resultSet.getString("uuid"),
                                                resultSet.getString("data"));
            }
            // 检查是否有完全匹配的用户名
            while (resultSet.next()) {
                String dbUsername = resultSet.getString("username");
                if (dbUsername.equals(username)) {
                    playerEntry = new PlayerEntryV1(dbUsername,
                                                    resultSet.getString("username_lower"),
                                                    resultSet.getString("uuid"),
                                                    resultSet.getString("data"));
                    break;
                }
            }

            resultSet.close();
            statement.close();
            return playerEntry;
        } catch (SQLException e) {
            LogError("在SQLite数据库中检查用户注册时出错", e);
        }
        return null;
    }

    public @Nonnull PlayerEntryV1 getUserDataOrCreate(String username) {
        // 获取用户数据，如果不存在则创建新用户
        PlayerEntryV1 playerEntry = getUserData(username);
        if (playerEntry == null) {
            playerEntry = new PlayerEntryV1(username); // 创建新用户
            registerUser(playerEntry); // 注册新用户
        }
        return playerEntry;
    }

    @Override
    public void deleteUserData(String username) {
        try {
            // 从数据库中删除用户数据
            PreparedStatement statement = connection.prepareStatement("DELETE FROM " + config.sqlite.sqliteTable + " WHERE username = ?;");
            statement.setString(1, username); // 设置要删除的用户名
            statement.executeUpdate();
            statement.close();
        } catch (SQLException e) {
            LogError("在SQLite数据库中删除用户数据时出错", e);
        }
    }

    @Override
    public void updateUserData(PlayerEntryV1 data) {
        try {
            // 更新用户数据
            PreparedStatement statement = connection.prepareStatement("UPDATE " + config.sqlite.sqliteTable + " SET uuid = ?, data = ? WHERE username = ?;");
            statement.setObject(1, data.uuid); // 更新UUID
            statement.setString(2, data.toJson()); // 更新用户数据的JSON表示
            statement.setString(3, data.username); // 设置用户名
            statement.executeUpdate();
            statement.close();
        } catch (SQLException e) {
            LogError("在SQLite数据库中更新用户数据时出错: " + data, e);
        }
    }

    @Override
    public HashMap<String, PlayerEntryV1> getAllData() {
        HashMap<String, PlayerEntryV1> registeredPlayers = new HashMap<>();
        try {
            // 获取所有用户数据
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("SELECT * FROM " + config.sqlite.sqliteTable + ";");
            while (resultSet.next()) {
                String username = resultSet.getString("username");
                String usernameLowerCase = resultSet.getString("username_lower");
                String uuid = resultSet.getString("uuid");
                String data = resultSet.getString("data");
                registeredPlayers.put(username, new PlayerEntryV1(username, usernameLowerCase, uuid, data)); // 将用户数据存入HashMap
            }
            resultSet.close();
            statement.close();
        } catch (SQLException e) {
            LogError("Error retrieving all data from SQLite database", e);
        }
        return registeredPlayers;
    }

    @Override
    public void migrateFromV1(HashMap<String, String> userCache) {
        LogInfo("Migrating from V1 to V2");
        LogInfo("But it's not implemented yet");
    }
}