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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Map;
import org.apache.avro.Schema;
import org.apache.avro.file.DataFileWriter;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.generic.GenericRecord;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.phoenix.mapreduce.AvroBulkLoadTool;
import org.apache.phoenix.query.QueryServices;
import org.apache.phoenix.util.PhoenixRuntime;
import org.apache.phoenix.util.ReadOnlyProps;
import org.apache.phoenix.util.TestUtil;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import org.apache.phoenix.thirdparty.com.google.common.collect.Maps;

@Category(NeedsOwnMiniClusterTest.class)
public class AvroBulkLoadToolIT extends BaseOwnClusterIT {

  private static Connection conn;
  private static String zkQuorum;

  @BeforeClass
  public static synchronized void doSetup() throws Exception {
    Map<String, String> clientProps = Maps.newHashMapWithExpectedSize(1);
    clientProps.put(QueryServices.INDEX_REGION_OBSERVER_ENABLED_ATTRIB, Boolean.FALSE.toString());
    setUpTestDriver(ReadOnlyProps.EMPTY_PROPS,
        new ReadOnlyProps(clientProps.entrySet().iterator()));
    zkQuorum = TestUtil.LOCALHOST + PhoenixRuntime.JDBC_PROTOCOL_SEPARATOR
        + getUtility().getZkCluster().getClientPort();
    conn = DriverManager.getConnection(getUrl());
  }

  @Test
  public void testBasicImport() throws Exception {
    Statement stmt = conn.createStatement();
    stmt.execute("CREATE TABLE AVRO_TABLE1 "
        + "(ID INTEGER NOT NULL PRIMARY KEY, NAME VARCHAR, AGE INTEGER)");

    String avroSchema = "{"
        + "\"type\": \"record\","
        + "\"name\": \"TestRecord\","
        + "\"fields\": ["
        + "  {\"name\": \"id\", \"type\": \"int\"},"
        + "  {\"name\": \"name\", \"type\": \"string\"},"
        + "  {\"name\": \"age\", \"type\": \"int\"}"
        + "]}";

    Schema schema = new Schema.Parser().parse(avroSchema);
    FileSystem fs = FileSystem.get(getUtility().getConfiguration());
    Path avroPath = new Path("/tmp/input_avro1.avro");

    writeAvroFile(fs, avroPath, schema,
        createRecord(schema, 1, "Alice", 30),
        createRecord(schema, 2, "Bob", 25));

    AvroBulkLoadTool tool = new AvroBulkLoadTool();
    tool.setConf(new Configuration(getUtility().getConfiguration()));
    int exitCode = tool.run(new String[] {
        "--input", "/tmp/input_avro1.avro",
        "--table", "AVRO_TABLE1",
        "--zookeeper", zkQuorum });
    assertEquals(0, exitCode);

    ResultSet rs = stmt.executeQuery("SELECT id, name, age FROM AVRO_TABLE1 ORDER BY id");
    assertTrue(rs.next());
    assertEquals(1, rs.getInt(1));
    assertEquals("Alice", rs.getString(2));
    assertEquals(30, rs.getInt(3));
    assertTrue(rs.next());
    assertEquals(2, rs.getInt(1));
    assertEquals("Bob", rs.getString(2));
    assertEquals(25, rs.getInt(3));
    assertFalse(rs.next());

    rs.close();
    stmt.close();
  }

  @Test
  public void testImportWithNulls() throws Exception {
    Statement stmt = conn.createStatement();
    stmt.execute("CREATE TABLE AVRO_TABLE2 "
        + "(ID INTEGER NOT NULL PRIMARY KEY, NAME VARCHAR, SCORE DOUBLE)");

    String avroSchema = "{"
        + "\"type\": \"record\","
        + "\"name\": \"TestRecord\","
        + "\"fields\": ["
        + "  {\"name\": \"id\", \"type\": \"int\"},"
        + "  {\"name\": \"name\", \"type\": [\"null\", \"string\"], \"default\": null},"
        + "  {\"name\": \"score\", \"type\": [\"null\", \"double\"], \"default\": null}"
        + "]}";

    Schema schema = new Schema.Parser().parse(avroSchema);
    FileSystem fs = FileSystem.get(getUtility().getConfiguration());
    Path avroPath = new Path("/tmp/input_avro2.avro");

    GenericRecord record1 = new GenericData.Record(schema);
    record1.put("id", 1);
    record1.put("name", "Alice");
    record1.put("score", 95.5);

    GenericRecord record2 = new GenericData.Record(schema);
    record2.put("id", 2);
    record2.put("name", null);
    record2.put("score", null);

    writeAvroFile(fs, avroPath, schema, record1, record2);

    AvroBulkLoadTool tool = new AvroBulkLoadTool();
    tool.setConf(new Configuration(getUtility().getConfiguration()));
    int exitCode = tool.run(new String[] {
        "--input", "/tmp/input_avro2.avro",
        "--table", "AVRO_TABLE2",
        "--zookeeper", zkQuorum });
    assertEquals(0, exitCode);

    ResultSet rs = stmt.executeQuery("SELECT id, name, score FROM AVRO_TABLE2 ORDER BY id");
    assertTrue(rs.next());
    assertEquals(1, rs.getInt(1));
    assertEquals("Alice", rs.getString(2));
    assertEquals(95.5, rs.getDouble(3), 0.001);
    assertTrue(rs.next());
    assertEquals(2, rs.getInt(1));
    assertNull(rs.getString(2));
    assertEquals(0.0, rs.getDouble(3), 0.001);
    assertTrue(rs.wasNull());
    assertFalse(rs.next());

    rs.close();
    stmt.close();
  }

  @Test
  public void testImportWithVariousTypes() throws Exception {
    Statement stmt = conn.createStatement();
    stmt.execute("CREATE TABLE AVRO_TABLE3 "
        + "(ID INTEGER NOT NULL PRIMARY KEY, NAME VARCHAR, "
        + "SCORE FLOAT, ACTIVE BOOLEAN, BIG_VAL BIGINT)");

    String avroSchema = "{"
        + "\"type\": \"record\","
        + "\"name\": \"TestRecord\","
        + "\"fields\": ["
        + "  {\"name\": \"id\", \"type\": \"int\"},"
        + "  {\"name\": \"name\", \"type\": \"string\"},"
        + "  {\"name\": \"score\", \"type\": \"float\"},"
        + "  {\"name\": \"active\", \"type\": \"boolean\"},"
        + "  {\"name\": \"big_val\", \"type\": \"long\"}"
        + "]}";

    Schema schema = new Schema.Parser().parse(avroSchema);
    FileSystem fs = FileSystem.get(getUtility().getConfiguration());
    Path avroPath = new Path("/tmp/input_avro3.avro");

    GenericRecord record1 = new GenericData.Record(schema);
    record1.put("id", 1);
    record1.put("name", "Alice");
    record1.put("score", 3.14f);
    record1.put("active", true);
    record1.put("big_val", 9876543210L);

    GenericRecord record2 = new GenericData.Record(schema);
    record2.put("id", 2);
    record2.put("name", "Bob");
    record2.put("score", 2.71f);
    record2.put("active", false);
    record2.put("big_val", 1234567890L);

    writeAvroFile(fs, avroPath, schema, record1, record2);

    AvroBulkLoadTool tool = new AvroBulkLoadTool();
    tool.setConf(new Configuration(getUtility().getConfiguration()));
    int exitCode = tool.run(new String[] {
        "--input", "/tmp/input_avro3.avro",
        "--table", "AVRO_TABLE3",
        "--zookeeper", zkQuorum });
    assertEquals(0, exitCode);

    ResultSet rs = stmt.executeQuery(
        "SELECT id, name, score, active, big_val FROM AVRO_TABLE3 ORDER BY id");
    assertTrue(rs.next());
    assertEquals(1, rs.getInt(1));
    assertEquals("Alice", rs.getString(2));
    assertEquals(3.14f, rs.getFloat(3), 0.001);
    assertTrue(rs.getBoolean(4));
    assertEquals(9876543210L, rs.getLong(5));
    assertTrue(rs.next());
    assertEquals(2, rs.getInt(1));
    assertEquals("Bob", rs.getString(2));
    assertEquals(2.71f, rs.getFloat(3), 0.001);
    assertFalse(rs.getBoolean(4));
    assertEquals(1234567890L, rs.getLong(5));
    assertFalse(rs.next());

    rs.close();
    stmt.close();
  }

  @Test
  public void testImportWithVarbinary() throws Exception {
    Statement stmt = conn.createStatement();
    stmt.execute("CREATE TABLE AVRO_TABLE4 "
        + "(ID INTEGER NOT NULL PRIMARY KEY, NAME VARCHAR, DATA VARBINARY)");

    String avroSchema = "{"
        + "\"type\": \"record\","
        + "\"name\": \"TestRecord\","
        + "\"fields\": ["
        + "  {\"name\": \"id\", \"type\": \"int\"},"
        + "  {\"name\": \"name\", \"type\": \"string\"},"
        + "  {\"name\": \"data\", \"type\": \"bytes\"}"
        + "]}";

    Schema schema = new Schema.Parser().parse(avroSchema);
    FileSystem fs = FileSystem.get(getUtility().getConfiguration());
    Path avroPath = new Path("/tmp/input_avro4.avro");

    byte[] testBytes = new byte[] { 0x01, 0x02, 0x03, 0x04 };

    GenericRecord record1 = new GenericData.Record(schema);
    record1.put("id", 1);
    record1.put("name", "BinaryTest");
    record1.put("data", ByteBuffer.wrap(testBytes));

    writeAvroFile(fs, avroPath, schema, record1);

    AvroBulkLoadTool tool = new AvroBulkLoadTool();
    tool.setConf(new Configuration(getUtility().getConfiguration()));
    int exitCode = tool.run(new String[] {
        "--input", "/tmp/input_avro4.avro",
        "--table", "AVRO_TABLE4",
        "--zookeeper", zkQuorum });
    assertEquals(0, exitCode);

    ResultSet rs = stmt.executeQuery("SELECT id, name, data FROM AVRO_TABLE4 ORDER BY id");
    assertTrue(rs.next());
    assertEquals(1, rs.getInt(1));
    assertEquals("BinaryTest", rs.getString(2));
    assertArrayEquals(testBytes, rs.getBytes(3));
    assertFalse(rs.next());

    rs.close();
    stmt.close();
  }

  @Test
  public void testImportWithSchemaName() throws Exception {
    Statement stmt = conn.createStatement();
    stmt.execute("CREATE TABLE S.AVRO_TABLE5 "
        + "(ID INTEGER NOT NULL PRIMARY KEY, NAME VARCHAR, VALUE DOUBLE)");

    String avroSchema = "{"
        + "\"type\": \"record\","
        + "\"name\": \"TestRecord\","
        + "\"fields\": ["
        + "  {\"name\": \"id\", \"type\": \"int\"},"
        + "  {\"name\": \"name\", \"type\": \"string\"},"
        + "  {\"name\": \"value\", \"type\": \"double\"}"
        + "]}";

    Schema schema = new Schema.Parser().parse(avroSchema);
    FileSystem fs = FileSystem.get(getUtility().getConfiguration());
    Path avroPath = new Path("/tmp/input_avro5.avro");

    writeAvroFile(fs, avroPath, schema,
        createRecord(schema, 1, "Test1", 1.1),
        createRecord(schema, 2, "Test2", 2.2));

    AvroBulkLoadTool tool = new AvroBulkLoadTool();
    tool.setConf(new Configuration(getUtility().getConfiguration()));
    int exitCode = tool.run(new String[] {
        "--input", "/tmp/input_avro5.avro",
        "--table", "AVRO_TABLE5",
        "--schema", "S",
        "--zookeeper", zkQuorum });
    assertEquals(0, exitCode);

    ResultSet rs = stmt.executeQuery("SELECT id, name, value FROM S.AVRO_TABLE5 ORDER BY id");
    assertTrue(rs.next());
    assertEquals(1, rs.getInt(1));
    assertEquals("Test1", rs.getString(2));
    assertEquals(1.1, rs.getDouble(3), 0.001);
    assertTrue(rs.next());
    assertEquals(2, rs.getInt(1));
    assertEquals("Test2", rs.getString(2));
    assertEquals(2.2, rs.getDouble(3), 0.001);
    assertFalse(rs.next());

    rs.close();
    stmt.close();
  }

  private static GenericRecord createRecord(Schema schema, int id, String name, int age) {
    GenericRecord record = new GenericData.Record(schema);
    record.put("id", id);
    record.put("name", name);
    record.put("age", age);
    return record;
  }

  private static GenericRecord createRecord(Schema schema, int id, String name, double value) {
    GenericRecord record = new GenericData.Record(schema);
    record.put("id", id);
    record.put("name", name);
    record.put("value", value);
    return record;
  }

  private static void writeAvroFile(FileSystem fs, Path path, Schema schema,
      GenericRecord... records) throws Exception {
    GenericDatumWriter<GenericRecord> datumWriter = new GenericDatumWriter<>(schema);
    try (OutputStream out = fs.create(path);
         DataFileWriter<GenericRecord> writer = new DataFileWriter<>(datumWriter)) {
      writer.create(schema, out);
      for (GenericRecord record : records) {
        writer.append(record);
      }
    }
  }
}
