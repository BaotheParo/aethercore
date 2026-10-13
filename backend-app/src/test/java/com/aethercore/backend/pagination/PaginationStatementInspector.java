package com.aethercore.backend.pagination;

import org.hibernate.resource.jdbc.spi.StatementInspector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Instance-based StatementInspector to observe SQL queries generated
 * by an individual EntityManagerFactory during collection pagination tests (M2-E4B).
 */
public class PaginationStatementInspector implements StatementInspector {

    private final List<String> statements = new CopyOnWriteArrayList<>();

    public void clear() {
        statements.clear();
    }

    public List<String> getStatements() {
        return Collections.unmodifiableList(new ArrayList<>(statements));
    }

    @Override
    public String inspect(String sql) {
        if (sql != null) {
            statements.add(sql);
        }
        return sql;
    }
}
