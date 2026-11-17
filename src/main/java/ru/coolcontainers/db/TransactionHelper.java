package ru.coolcontainers.db;

import org.jetbrains.annotations.NotNull;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Обёртка над ручной транзакцией: commit / rollback по результату лямбды.
 * Лямбда должна работать только с переданным Connection.
 *
 * В отличие от java.util.function.Function, SqlFunction позволяет
 * бросать SQLException — иначе методы репозиториев пришлось бы оборачивать
 * в try/catch прямо внутри лямбды.
 */
public final class TransactionHelper {

    @FunctionalInterface
    public interface SqlFunction<T> {
        T apply(@NotNull Connection c) throws SQLException;
    }

    private TransactionHelper() {}

    public static <T> @NotNull T inTransaction(@NotNull Database db,
                                               @NotNull SqlFunction<T> work)
            throws SQLException {
        try (Connection c = db.getConnection()) {
            boolean previousAutoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                T result = work.apply(c);
                c.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                try {
                    c.rollback();
                } catch (SQLException rollbackErr) {
                    e.addSuppressed(rollbackErr);
                }
                throw e;
            } finally {
                try {
                    c.setAutoCommit(previousAutoCommit);
                } catch (SQLException ignored) {
                    // соединение уже уходит в пул
                }
            }
        }
    }
}