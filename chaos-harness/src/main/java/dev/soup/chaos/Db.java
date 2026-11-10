package dev.soup.chaos;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only JDBC access for observation and invariant checks. One short connection per call. */
final class Db {

    private final String url;
    private final String user;
    private final String password;

    Db(String url, String user, String password) {
        this.url = url;
        this.user = user;
        this.password = password;
    }

    String url() {
        return url;
    }

    Connection connect() throws SQLException {
        Connection c = DriverManager.getConnection(url, user, password);
        c.setReadOnly(true);
        return c;
    }

    long count(String sql, Object... args) {
        try (Connection c = connect(); PreparedStatement ps = prepare(c, sql, args); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0;
        } catch (SQLException e) {
            throw new IllegalStateException(sql + ": " + e.getMessage(), e);
        }
    }

    List<String> strings(String sql, Object... args) {
        List<String> out = new ArrayList<>();
        try (Connection c = connect(); PreparedStatement ps = prepare(c, sql, args); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException(sql + ": " + e.getMessage(), e);
        }
    }

    /** First column → second column. */
    Map<String, String> pairs(String sql, Object... args) {
        Map<String, String> out = new LinkedHashMap<>();
        try (Connection c = connect(); PreparedStatement ps = prepare(c, sql, args); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.put(rs.getString(1), rs.getString(2));
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException(sql + ": " + e.getMessage(), e);
        }
    }

    boolean reachable() {
        try (Connection c = connect()) {
            return c.isValid(2);
        } catch (SQLException e) {
            return false;
        }
    }

    private static PreparedStatement prepare(Connection c, String sql, Object... args) throws SQLException {
        PreparedStatement ps = c.prepareStatement(sql);
        for (int i = 0; i < args.length; i++) {
            ps.setObject(i + 1, args[i]);
        }
        return ps;
    }
}
