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
package org.apache.phoenix.mapreduce;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.mapred.AvroKey;
import org.apache.avro.util.Utf8;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.Pair;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.WritableUtils;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.phoenix.jdbc.PhoenixConnection;
import org.apache.phoenix.mapreduce.bulkload.TableRowkeyPair;
import org.apache.phoenix.mapreduce.bulkload.TargetTableRefFunctions;
import org.apache.phoenix.mapreduce.util.PhoenixConfigurationUtil;
import org.apache.phoenix.query.QueryConstants;
import org.apache.phoenix.schema.PColumn;
import org.apache.phoenix.schema.PColumnFamily;
import org.apache.phoenix.schema.PTable;
import org.apache.phoenix.schema.PTable.ImmutableStorageScheme;
import org.apache.phoenix.util.ColumnInfo;
import org.apache.phoenix.util.EncodedColumnsUtil;
import org.apache.phoenix.util.IndexUtil;
import org.apache.phoenix.util.IndexUtil.IndexStatusUpdater;
import org.apache.phoenix.util.PhoenixRuntime;
import org.apache.phoenix.util.QueryUtil;
import org.apache.phoenix.util.SchemaUtil;
import org.apache.phoenix.util.UpsertExecutor;
import org.apache.phoenix.util.json.JsonUpsertExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.phoenix.thirdparty.com.google.common.base.Joiner;
import org.apache.phoenix.thirdparty.com.google.common.base.Preconditions;
import org.apache.phoenix.thirdparty.com.google.common.base.Splitter;
import org.apache.phoenix.thirdparty.com.google.common.collect.ImmutableList;
import org.apache.phoenix.thirdparty.com.google.common.collect.Lists;

/**
 * MapReduce mapper that converts Avro {@link GenericRecord} input into KeyValues that can be
 * written to HFiles. Records are converted to a Map and processed via {@link JsonUpsertExecutor}.
 */
public class AvroToKeyValueMapper
    extends Mapper<AvroKey<GenericRecord>, NullWritable, TableRowkeyPair, ImmutableBytesWritable> {

  private static final Logger LOGGER = LoggerFactory.getLogger(AvroToKeyValueMapper.class);

  private static final String COUNTER_GROUP_NAME = "Phoenix MapReduce Import";

  private PhoenixConnection conn;
  private UpsertExecutor<Map<?, ?>, ?> upsertExecutor;
  private ImportPreUpsertKeyValueProcessor preUpdateProcessor;
  private IndexStatusUpdater[] indexStatusUpdaters;
  private List<String> tableNames;
  private List<String> logicalNames;
  private FormatToBytesWritableMapper.MapperUpsertListener<Map<?, ?>> upsertListener;
  private boolean ignoreInvalidRows;
  private PrintWriter badRecordsWriter;
  private Map<byte[], Integer> columnIndexes;

  @Override
  protected void setup(Context context) throws IOException, InterruptedException {
    Configuration conf = context.getConfiguration();

    Properties clientInfos = new Properties();
    for (Map.Entry<String, String> entry : conf) {
      clientInfos.setProperty(entry.getKey(), entry.getValue());
    }

    try {
      conn = (PhoenixConnection) QueryUtil.getConnectionOnServer(clientInfos, conf);
      conn.setAutoCommit(false);

      final String tableNamesConf = conf.get(FormatToBytesWritableMapper.TABLE_NAMES_CONFKEY);
      final String logicalNamesConf = conf.get(FormatToBytesWritableMapper.LOGICAL_NAMES_CONFKEY);
      tableNames = TargetTableRefFunctions.NAMES_FROM_JSON.apply(tableNamesConf);
      logicalNames = TargetTableRefFunctions.NAMES_FROM_JSON.apply(logicalNamesConf);

      initColumnIndexes();
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }

    ignoreInvalidRows =
        conf.getBoolean(FormatToBytesWritableMapper.IGNORE_INVALID_ROW_CONFKEY, true);
    upsertListener = new FormatToBytesWritableMapper.MapperUpsertListener<>(context,
        ignoreInvalidRows, this::writeBadRecord);
    upsertExecutor = buildUpsertExecutor(conf);
    preUpdateProcessor = PhoenixConfigurationUtil.loadPreUpsertProcessor(conf);

    String badRecordsPath = conf.get(FormatToBytesWritableMapper.BAD_RECORDS_PATH_CONFKEY);
    if (badRecordsPath != null) {
      Path outputDir = new Path(badRecordsPath);
      FileSystem fs = outputDir.getFileSystem(conf);
      if (!fs.exists(outputDir)) {
        fs.mkdirs(outputDir);
      }
      String taskAttemptId = context.getTaskAttemptID().toString();
      Path badRecordFile = new Path(outputDir, taskAttemptId + ".bad");
      badRecordsWriter = new PrintWriter(
          new OutputStreamWriter(fs.create(badRecordFile, false), StandardCharsets.UTF_8));
    }
  }

  @Override
  protected void map(AvroKey<GenericRecord> key, NullWritable value, Context context)
      throws IOException, InterruptedException {
    if (conn == null) {
      throw new RuntimeException("Connection not initialized.");
    }
    try {
      GenericRecord record = key.datum();
      if (record == null) {
        context.getCounter(COUNTER_GROUP_NAME, "Empty records").increment(1L);
        return;
      }

      Map<String, Object> recordMap = convertGenericRecordToMap(record);

      upsertExecutor.execute(ImmutableList.<Map<?, ?>>of(recordMap));
      Map<Integer, List<Cell>> cellMap = new HashMap<>();
      Iterator<Pair<byte[], List<Cell>>> uncommittedDataIterator =
          PhoenixRuntime.getUncommittedDataIterator(conn, true);
      while (uncommittedDataIterator.hasNext()) {
        Pair<byte[], List<Cell>> kvPair = uncommittedDataIterator.next();
        List<Cell> keyValueList = kvPair.getSecond();
        byte[] tableName = kvPair.getFirst();
        keyValueList = preUpdateProcessor.preUpsert(tableName, keyValueList);
        for (int i = 0; i < tableNames.size(); i++) {
          if (Bytes.compareTo(Bytes.toBytes(tableNames.get(i)), tableName) == 0) {
            if (!cellMap.containsKey(i)) {
              cellMap.put(i, new ArrayList<Cell>());
            }
            List<Cell> cellsForTable = cellMap.get(i);
            if (indexStatusUpdaters[i] != null) {
              indexStatusUpdaters[i].setVerified(keyValueList);
            }
            cellsForTable.addAll(keyValueList);
            break;
          }
        }
      }
      for (Map.Entry<Integer, List<Cell>> rowEntry : cellMap.entrySet()) {
        int tableIndex = rowEntry.getKey();
        List<Cell> lkv = rowEntry.getValue();
        writeAggregatedRow(context, tableNames.get(tableIndex), lkv);
      }
      conn.rollback();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Convert an Avro {@link GenericRecord} to a flat Map for use with {@link JsonUpsertExecutor}.
   * Field names are lowercased to match Phoenix's case-insensitive column matching.
   */
  static Map<String, Object> convertGenericRecordToMap(GenericRecord record) {
    Map<String, Object> map = new HashMap<>();
    for (org.apache.avro.Schema.Field field : record.getSchema().getFields()) {
      Object value = record.get(field.name());
      map.put(field.name().toLowerCase(), convertAvroValue(value));
    }
    return map;
  }

  /**
   * Convert Avro values to Java types compatible with {@link JsonUpsertExecutor}.
   */
  private static Object convertAvroValue(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof Utf8) {
      return value.toString();
    }
    if (value instanceof ByteBuffer) {
      ByteBuffer bb = (ByteBuffer) value;
      byte[] bytes = new byte[bb.remaining()];
      bb.duplicate().get(bytes);
      return bytes;
    }
    if (value instanceof GenericRecord) {
      // Nested records are not directly supported; convert to string representation
      return value.toString();
    }
    if (value instanceof List) {
      List<?> avroList = (List<?>) value;
      List<Object> result = new ArrayList<>(avroList.size());
      for (Object item : avroList) {
        result.add(convertAvroValue(item));
      }
      return result;
    }
    if (value instanceof Map) {
      Map<?, ?> avroMap = (Map<?, ?>) value;
      Map<String, Object> result = new HashMap<>();
      for (Map.Entry<?, ?> entry : avroMap.entrySet()) {
        result.put(entry.getKey().toString(), convertAvroValue(entry.getValue()));
      }
      return result;
    }
    // Primitive types (Integer, Long, Float, Double, Boolean) pass through
    return value;
  }

  private UpsertExecutor<Map<?, ?>, ?> buildUpsertExecutor(Configuration conf) {
    String tableName = conf.get(FormatToBytesWritableMapper.TABLE_NAME_CONFKEY);
    Preconditions.checkNotNull(tableName, "table name is not configured");

    List<ColumnInfo> columnInfoList = FormatToBytesWritableMapper.buildColumnInfoList(conf);

    return new JsonUpsertExecutor(conn, tableName, columnInfoList, upsertListener);
  }

  private void initColumnIndexes() throws SQLException {
    columnIndexes = new TreeMap<>(Bytes.BYTES_COMPARATOR);
    indexStatusUpdaters = new IndexStatusUpdater[logicalNames.size()];
    int columnIndex = 0;
    for (int index = 0; index < logicalNames.size(); index++) {
      PTable table = conn.getTable(logicalNames.get(index));
      if (!table.getImmutableStorageScheme().equals(ImmutableStorageScheme.ONE_CELL_PER_COLUMN)) {
        List<PColumnFamily> cfs = table.getColumnFamilies();
        for (int i = 0; i < cfs.size(); i++) {
          byte[] family = cfs.get(i).getName().getBytes();
          byte[] cfn = Bytes.add(family, QueryConstants.NAMESPACE_SEPARATOR_BYTES,
              QueryConstants.SINGLE_KEYVALUE_COLUMN_QUALIFIER_BYTES);
          columnIndexes.put(cfn, Integer.valueOf(columnIndex));
          columnIndex++;
        }
      } else {
        List<PColumn> cls = table.getColumns();
        for (int i = 0; i < cls.size(); i++) {
          PColumn c = cls.get(i);
          byte[] family = new byte[0];
          byte[] cq;
          if (!SchemaUtil.isPKColumn(c)) {
            family = c.getFamilyName().getBytes();
            cq = c.getColumnQualifierBytes();
          } else {
            cq = c.getName().getBytes();
          }
          byte[] cfn = Bytes.add(family, QueryConstants.NAMESPACE_SEPARATOR_BYTES, cq);
          if (!columnIndexes.containsKey(cfn)) {
            columnIndexes.put(cfn, Integer.valueOf(columnIndex));
            columnIndex++;
          }
        }
      }
      byte[] emptyColumnFamily = SchemaUtil.getEmptyColumnFamily(table);
      byte[] emptyKeyValue = EncodedColumnsUtil.getEmptyKeyValueInfo(table).getFirst();
      byte[] cfn =
          Bytes.add(emptyColumnFamily, QueryConstants.NAMESPACE_SEPARATOR_BYTES, emptyKeyValue);
      columnIndexes.put(cfn, Integer.valueOf(columnIndex));
      columnIndex++;
      if (IndexUtil.isGlobalIndex(table)) {
        indexStatusUpdaters[index] = new IndexStatusUpdater(emptyColumnFamily, emptyKeyValue);
      }
    }
  }

  private int findIndex(Cell cell) {
    byte[] familyName =
        Bytes.copy(cell.getFamilyArray(), cell.getFamilyOffset(), cell.getFamilyLength());
    byte[] cq =
        Bytes.copy(cell.getQualifierArray(), cell.getQualifierOffset(), cell.getQualifierLength());
    byte[] cfn = Bytes.add(familyName, QueryConstants.NAMESPACE_SEPARATOR_BYTES, cq);
    if (columnIndexes.containsKey(cfn)) {
      return columnIndexes.get(cfn);
    }
    return -1;
  }

  private void writeAggregatedRow(Context context, String tableName, List<Cell> lkv)
      throws IOException, InterruptedException {
    ByteArrayOutputStream bos = new ByteArrayOutputStream(1024);
    DataOutputStream outputStream = new DataOutputStream(bos);
    ImmutableBytesWritable outputKey = null;
    if (!lkv.isEmpty()) {
      for (Cell cell : lkv) {
        if (outputKey == null
            || Bytes.compareTo(outputKey.get(), outputKey.getOffset(), outputKey.getLength(),
                cell.getRowArray(), cell.getRowOffset(), cell.getRowLength()) != 0) {
          if (outputKey != null) {
            ImmutableBytesWritable aggregatedArray = new ImmutableBytesWritable(bos.toByteArray());
            outputStream.close();
            context.write(new TableRowkeyPair(tableName, outputKey), aggregatedArray);
          }
          outputKey = new ImmutableBytesWritable(cell.getRowArray(), cell.getRowOffset(),
              cell.getRowLength());
          bos = new ByteArrayOutputStream(1024);
          outputStream = new DataOutputStream(bos);
        }
        int i = findIndex(cell);
        if (i == -1) {
          continue;
        }
        outputStream.writeByte(cell.getType().getCode());
        WritableUtils.writeVLong(outputStream, cell.getTimestamp());
        WritableUtils.writeVInt(outputStream, i);
        WritableUtils.writeVInt(outputStream, cell.getValueLength());
        outputStream.write(cell.getValueArray(), cell.getValueOffset(), cell.getValueLength());
      }
      ImmutableBytesWritable aggregatedArray = new ImmutableBytesWritable(bos.toByteArray());
      outputStream.close();
      context.write(new TableRowkeyPair(tableName, outputKey), aggregatedArray);
    }
  }

  private void writeBadRecord(String record, String errorMessage) {
    if (badRecordsWriter != null) {
      String sanitizedError = errorMessage != null
          ? errorMessage.replace('\n', ' ').replace('\t', ' ')
          : "unknown";
      badRecordsWriter.println(sanitizedError + "\t" + record);
    }
  }

  @Override
  protected void cleanup(Context context) throws IOException, InterruptedException {
    try {
      if (badRecordsWriter != null) {
        badRecordsWriter.close();
      }
      if (conn != null) {
        conn.close();
      }
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }
}
