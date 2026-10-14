package com.logss.main.analysis;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class SqlShapesTest {

    @Test
    void replacesLiteralsAndCollapsesInLists() {
        assertEquals("select * from users where id in (?) and name = ? and age > ?",
                SqlShapes.normalize("SELECT *  FROM users WHERE id IN (1, 2, 3) AND name = 'O''Brien' AND age > 21"));
    }

    @Test
    void handlesVeryLongStringLiterals() {
        // pgjdbc runs a query like this with a 4.5 KB literal; it used to overflow the regex stack.
        String sql = "select string_agg(word, ',') from pg_get_keywords() where word <> ALL ('{" + "abc,".repeat(5_000) + "z}'::text[])";
        assertEquals("select string_agg(word, ?) from pg_get_keywords() where word <> all (?::text[])", SqlShapes.normalize(sql));
    }

    @Test
    void keepsHibernateAliasesIntact() {
        String sql = "select ai1_0.id from action_invocation ai1_0 where ai1_0.id=?";
        assertEquals(sql, SqlShapes.normalize(sql));
    }

    @Test
    void sameShapeHashesTheSame() {
        assertEquals(SqlShapes.hash(SqlShapes.normalize("select * from t where id = 1")),
                SqlShapes.hash(SqlShapes.normalize("select * from t where id = 2")));
        assertNotEquals(SqlShapes.hash("select * from a"), SqlShapes.hash("select * from b"));
    }
}
