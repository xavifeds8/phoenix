/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.phoenix.end2end;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Integration tests for the REGEXP_LIKE function.
 */
@Category(ParallelStatsDisabledTest.class)
public class RegexpLikeFunctionIT extends ParallelStatsDisabledIT {

  private String tableName;

  @Before
  public void doBeforeTestSetup() throws Exception {
    tableName = generateUniqueName();
    try (Connection conn = DriverManager.getConnection(getUrl())) {
      String ddl = "CREATE TABLE " + tableName + " ("
          + "id INTEGER PRIMARY KEY, "
          + "name VARCHAR, "
          + "email VARCHAR, "
          + "phone VARCHAR)";
      conn.createStatement().execute(ddl);

      // Insert test data
      insertRow(conn, 1, "John Doe", "john.doe@example.com", "555-1234");
      insertRow(conn, 2, "Jane Smith", "jane.smith@test.org", "555-5678");
      insertRow(conn, 3, "Bob Johnson", "bob@company.net", "555-9012");
      insertRow(conn, 4, "Alice Brown", "alice.brown@example.com", "555-3456");
      insertRow(conn, 5, "Charlie Wilson", "charlie@test.org", "555-7890");
      conn.commit();
    }
  }

  private void insertRow(Connection conn, int id, String name, String email, String phone)
      throws SQLException {
    String sql = "UPSERT INTO " + tableName + " (id, name, email, phone) VALUES (?, ?, ?, ?)";
    try (PreparedStatement stmt = conn.prepareStatement(sql)) {
      stmt.setInt(1, id);
      stmt.setString(2, name);
      stmt.setString(3, email);
      stmt.setString(4, phone);
      stmt.executeUpdate();
    }
  }

  @Test
  public void testBasicPatternMatching() throws Exception {
    try (Connection conn = DriverManager.getConnection(getUrl())) {
      // Test matching email pattern
      String sql = "SELECT id, name FROM " + tableName
          + " WHERE REGEXP_LIKE(email, '^[a-z]+\\.[a-z]+@.*$') ORDER BY id";
      ResultSet rs = conn.createStatement().executeQuery(sql);

      assertTrue(rs.next());
      assertEquals(1, rs.getInt("id"));
      assertEquals("John Doe", rs.getString("name"));

      assertTrue(rs.next());
      assertEquals(2, rs.getInt("id"));
      assertEquals("Jane Smith", rs.getString("name"));

      assertTrue(rs.next());
      assertEquals(4, rs.getInt("id"));
      assertEquals("Alice Brown", rs.getString("name"));

      assertFalse(rs.next());
    }
  }

  @Test
  public void testPhoneNumberPattern() throws Exception {
    try (Connection conn = DriverManager.getConnection(getUrl())) {
      // Test matching phone numbers starting with 555-1 or 555-5
      String sql = "SELECT id, phone FROM " + tableName
          + " WHERE REGEXP_LIKE(phone, '^555-[15].*') ORDER BY id";
      ResultSet rs = conn.createStatement().executeQuery(sql);

      assertTrue(rs.next());
      assertEquals(1, rs.getInt("id"));
      assertEquals("555-1234", rs.getString("phone"));

      assertTrue(rs.next());
      assertEquals(2, rs.getInt("id"));
      assertEquals("555-5678", rs.getString("phone"));

      assertFalse(rs.next());
    }
  }

  @Test
  public void testNamePattern() throws Exception {
    try (Connection conn = DriverManager.getConnection(getUrl())) {
      // Test matching names containing "John" or "Brown"
      String sql = "SELECT id, name FROM " + tableName
          + " WHERE REGEXP_LIKE(name, '.*(John|Brown).*') ORDER BY id";
      ResultSet rs = conn.createStatement().executeQuery(sql);

      assertTrue(rs.next());
      assertEquals(1, rs.getInt("id"));
      assertEquals("John Doe", rs.getString("name"));

      assertTrue(rs.next());
      assertEquals(3, rs.getInt("id"));
      assertEquals("Bob Johnson", rs.getString("name"));

      assertTrue(rs.next());
      assertEquals(4, rs.getInt("id"));
      assertEquals("Alice Brown", rs.getString("name"));

      assertFalse(rs.next());
    }
  }

  @Test
  public void testBooleanResult() throws Exception {
    try (Connection conn = DriverManager.getConnection(getUrl())) {
      // Test that REGEXP_LIKE returns boolean values
      String sql = "SELECT id, REGEXP_LIKE(email, '@example\\.com$') as is_example "
          + "FROM " + tableName + " ORDER BY id";
      ResultSet rs = conn.createStatement().executeQuery(sql);

      assertTrue(rs.next());
      assertEquals(1, rs.getInt("id"));
      assertTrue(rs.getBoolean("is_example"));

      assertTrue(rs.next());
      assertEquals(2, rs.getInt("id"));
      assertFalse(rs.getBoolean("is_example"));

      assertTrue(rs.next());
      assertEquals(3, rs.getInt("id"));
      assertFalse(rs.getBoolean("is_example"));

      assertTrue(rs.next());
      assertEquals(4, rs.getInt("id"));
      assertTrue(rs.getBoolean("is_example"));

      assertTrue(rs.next());
      assertEquals(5, rs.getInt("id"));
      assertFalse(rs.getBoolean("is_example"));

      assertFalse(rs.next());
    }
  }

  @Test
  public void testComplexPattern() throws Exception {
    try (Connection conn = DriverManager.getConnection(getUrl())) {
      // Test complex email validation pattern
      String sql = "SELECT id, email FROM " + tableName
          + " WHERE REGEXP_LIKE(email, '^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$') "
          + "ORDER BY id";
      ResultSet rs = conn.createStatement().executeQuery(sql);

      // All emails should match this pattern
      assertTrue(rs.next());
      assertEquals(1, rs.getInt("id"));
      assertTrue(rs.next());
      assertEquals(2, rs.getInt("id"));
      assertTrue(rs.next());
      assertEquals(3, rs.getInt("id"));
      assertTrue(rs.next());
      assertEquals(4, rs.getInt("id"));
      assertTrue(rs.next());
      assertEquals(5, rs.getInt("id"));

      assertFalse(rs.next());
    }
  }

  @Test
  public void testNegativeMatch() throws Exception {
    try (Connection conn = DriverManager.getConnection(getUrl())) {
      // Test NOT REGEXP_LIKE
      String sql = "SELECT id, email FROM " + tableName
          + " WHERE NOT REGEXP_LIKE(email, '@example\\.com$') ORDER BY id";
      ResultSet rs = conn.createStatement().executeQuery(sql);

      assertTrue(rs.next());
      assertEquals(2, rs.getInt("id"));

      assertTrue(rs.next());
      assertEquals(3, rs.getInt("id"));

      assertTrue(rs.next());
      assertEquals(5, rs.getInt("id"));

      assertFalse(rs.next());
    }
  }

  @Test
  public void testWithPreparedStatement() throws Exception {
    try (Connection conn = DriverManager.getConnection(getUrl())) {
      String sql = "SELECT id, name FROM " + tableName
          + " WHERE REGEXP_LIKE(name, ?) ORDER BY id";
      try (PreparedStatement stmt = conn.prepareStatement(sql)) {
        stmt.setString(1, "^[JA].*");
        ResultSet rs = stmt.executeQuery();

        assertTrue(rs.next());
        assertEquals(1, rs.getInt("id"));
        assertEquals("John Doe", rs.getString("name"));

        assertTrue(rs.next());
        assertEquals(2, rs.getInt("id"));
        assertEquals("Jane Smith", rs.getString("name"));

        assertTrue(rs.next());
        assertEquals(4, rs.getInt("id"));
        assertEquals("Alice Brown", rs.getString("name"));

        assertFalse(rs.next());
      }
    }
  }

  @Test
  public void testEmptyStringAndNull() throws Exception {
    String testTable = generateUniqueName();
    try (Connection conn = DriverManager.getConnection(getUrl())) {
      String ddl = "CREATE TABLE " + testTable + " (id INTEGER PRIMARY KEY, val VARCHAR)";
      conn.createStatement().execute(ddl);

      conn.createStatement().execute(
          "UPSERT INTO " + testTable + " VALUES (1, 'a')");
      conn.createStatement().execute(
          "UPSERT INTO " + testTable + " VALUES (2, NULL)");
      conn.createStatement().execute(
          "UPSERT INTO " + testTable + " VALUES (3, 'test')");
      conn.createStatement().execute(
          "UPSERT INTO " + testTable + " VALUES (4, 'abc')");
      conn.commit();

      // Test pattern that requires at least one character
      String sql = "SELECT id FROM " + testTable + " WHERE REGEXP_LIKE(val, '.+') ORDER BY id";
      ResultSet rs = conn.createStatement().executeQuery(sql);
      assertTrue(rs.next());
      assertEquals(1, rs.getInt("id"));
      assertTrue(rs.next());
      assertEquals(3, rs.getInt("id"));
      assertTrue(rs.next());
      assertEquals(4, rs.getInt("id"));
      assertFalse(rs.next());

      // Test pattern that matches specific strings
      sql = "SELECT id FROM " + testTable + " WHERE REGEXP_LIKE(val, '^test$') ORDER BY id";
      rs = conn.createStatement().executeQuery(sql);
      assertTrue(rs.next());
      assertEquals(3, rs.getInt("id"));
      assertFalse(rs.next());

      // Test pattern that matches strings containing 'a'
      sql = "SELECT id FROM " + testTable + " WHERE REGEXP_LIKE(val, 'a') ORDER BY id";
      rs = conn.createStatement().executeQuery(sql);
      assertTrue(rs.next());
      assertEquals(1, rs.getInt("id"));
      assertTrue(rs.next());
      assertEquals(4, rs.getInt("id"));
      assertFalse(rs.next());
    }
  }

  @Test
  public void testCaseInsensitivePattern() throws Exception {
    try (Connection conn = DriverManager.getConnection(getUrl())) {
      // Test case-sensitive matching (default)
      String sql = "SELECT COUNT(*) as cnt FROM " + tableName
          + " WHERE REGEXP_LIKE(name, 'john')";
      ResultSet rs = conn.createStatement().executeQuery(sql);
      assertTrue(rs.next());
      assertEquals(0, rs.getInt("cnt"));

      // Test with case-insensitive pattern using (?i)
      sql = "SELECT COUNT(*) as cnt FROM " + tableName
          + " WHERE REGEXP_LIKE(name, '(?i)john')";
      rs = conn.createStatement().executeQuery(sql);
      assertTrue(rs.next());
      assertEquals(2, rs.getInt("cnt")); // John Doe and Bob Johnson
    }
  }
}
