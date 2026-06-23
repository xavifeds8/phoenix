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

import java.sql.SQLException;
import java.util.List;
import org.apache.avro.Schema;
import org.apache.avro.file.DataFileStream;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.mapreduce.AvroJob;
import org.apache.avro.mapreduce.AvroKeyInputFormat;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.ConnectionFactory;
import org.apache.hadoop.hbase.client.RegionLocator;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.util.ToolRunner;
import org.apache.phoenix.mapreduce.bulkload.TableRowkeyPair;
import org.apache.phoenix.mapreduce.bulkload.TargetTableRef;
import org.apache.phoenix.mapreduce.bulkload.TargetTableRefFunctions;
import org.apache.phoenix.util.ColumnInfo;
import org.apache.phoenix.util.IndexUtil;

import org.apache.phoenix.thirdparty.org.apache.commons.cli.CommandLine;

/**
 * A tool for running MapReduce-based ingests of Avro data files into Phoenix tables. The Avro
 * schema is read from the input data files. Avro field names are matched to Phoenix column names
 * case-insensitively.
 *
 * <p>Usage:
 * <pre>
 * hadoop jar phoenix-server.jar org.apache.phoenix.mapreduce.AvroBulkLoadTool \
 *   --input /path/to/avro/files --table MY_TABLE --zookeeper host:2181
 * </pre>
 */
public class AvroBulkLoadTool extends AbstractBulkLoadTool {

  @Override
  protected void configureOptions(CommandLine cmdLine, List<ColumnInfo> importColumns,
      Configuration conf) throws SQLException {
    // No additional options needed — schema is read from the Avro file
  }

  @Override
  protected void setupJob(Job job) {
    if (job.getJar() == null) {
      job.setJarByClass(AvroToKeyValueMapper.class);
    }
    job.setMapperClass(AvroToKeyValueMapper.class);
  }

  @Override
  public int submitJob(final Configuration conf, final String qualifiedTableName,
      final String inputPaths, final Path outputPath, List<TargetTableRef> tablesToBeLoaded,
      boolean hasLocalIndexes) throws Exception {

    Job job = Job.getInstance(conf, "Phoenix Avro MapReduce import for " + qualifiedTableName);
    FileInputFormat.addInputPaths(job, inputPaths);
    FileOutputFormat.setOutputPath(job, outputPath);

    // Use AvroKeyInputFormat instead of PhoenixTextInputFormat
    job.setInputFormatClass(AvroKeyInputFormat.class);

    // Read the Avro schema from the first input file
    Schema avroSchema = getAvroSchemaFromInput(conf, inputPaths);
    AvroJob.setInputKeySchema(job, avroSchema);

    job.setMapOutputKeyClass(TableRowkeyPair.class);
    job.setMapOutputValueClass(ImmutableBytesWritable.class);
    job.setOutputKeyClass(TableRowkeyPair.class);
    job.setOutputValueClass(KeyValue.class);
    job.setReducerClass(FormatToKeyValueReducer.class);
    byte[][] splitKeysBeforeJob = null;
    try (org.apache.hadoop.hbase.client.Connection hbaseConn =
        ConnectionFactory.createConnection(job.getConfiguration())) {
      RegionLocator regionLocator = null;
      if (hasLocalIndexes) {
        try {
          regionLocator = hbaseConn.getRegionLocator(TableName.valueOf(qualifiedTableName));
          splitKeysBeforeJob = regionLocator.getStartKeys();
        } finally {
          if (regionLocator != null) regionLocator.close();
        }
      }
      MultiHfileOutputFormat.configureIncrementalLoad(job, tablesToBeLoaded);

      final String tableNamesAsJson =
          TargetTableRefFunctions.NAMES_TO_JSON.apply(tablesToBeLoaded);
      final String logicalNamesAsJson =
          TargetTableRefFunctions.LOGICAL_NAMES_TO_JSON.apply(tablesToBeLoaded);

      job.getConfiguration().set(FormatToBytesWritableMapper.TABLE_NAMES_CONFKEY,
          tableNamesAsJson);
      job.getConfiguration().set(FormatToBytesWritableMapper.LOGICAL_NAMES_CONFKEY,
          logicalNamesAsJson);

      // give subclasses their hook
      setupJob(job);

      LOGGER.info("Running Avro MapReduce import job from {} to {}", inputPaths, outputPath);
      boolean success = job.waitForCompletion(true);

      if (success) {
        if (hasLocalIndexes) {
          try {
            regionLocator = hbaseConn.getRegionLocator(TableName.valueOf(qualifiedTableName));
            if (!IndexUtil.matchingSplitKeys(splitKeysBeforeJob, regionLocator.getStartKeys())) {
              LOGGER.error("The table " + qualifiedTableName + " has local indexes and"
                  + " there is split key mismatch before and after running"
                  + " bulkload job. Please rerun the job otherwise there may be"
                  + " inconsistencies between actual data and index data.");
              return -1;
            }
          } finally {
            if (regionLocator != null) regionLocator.close();
          }
        }
        LOGGER.info("Loading HFiles from {}", outputPath);
        completeBulkLoad(conf, outputPath, tablesToBeLoaded);
        LOGGER.info("Removing output directory {}", outputPath);
        if (!outputPath.getFileSystem(conf).delete(outputPath, true)) {
          LOGGER.error("Failed to delete the output directory {}", outputPath);
        }
        return 0;
      } else {
        return -1;
      }
    }
  }

  /**
   * Read the Avro schema from the first file found in the input paths.
   */
  private Schema getAvroSchemaFromInput(Configuration conf, String inputPaths) throws Exception {
    String firstPath = inputPaths.split(",")[0].trim();
    Path path = new Path(firstPath);
    FileSystem fs = path.getFileSystem(conf);

    // If path is a directory, find the first .avro file
    if (fs.isDirectory(path)) {
      FileStatus[] statuses = fs.listStatus(path);
      for (FileStatus status : statuses) {
        if (!status.isDirectory() && status.getPath().getName().endsWith(".avro")) {
          path = status.getPath();
          break;
        }
      }
    }

    try (DataFileStream<GenericRecord> reader =
        new DataFileStream<>(fs.open(path), new GenericDatumReader<>())) {
      return reader.getSchema();
    }
  }

  private void completeBulkLoad(Configuration conf, Path outputPath,
      List<TargetTableRef> tablesToBeLoaded) throws Exception {
    java.util.Set<String> tableNames = new java.util.HashSet<>(tablesToBeLoaded.size());
    for (TargetTableRef table : tablesToBeLoaded) {
      if (tableNames.contains(table.getPhysicalName())) {
        continue;
      }
      tableNames.add(table.getPhysicalName());
      org.apache.hadoop.hbase.tool.BulkLoadHFiles loader =
          org.apache.hadoop.hbase.tool.BulkLoadHFiles.create(conf);
      String tableName = table.getPhysicalName();
      Path tableOutputPath = CsvBulkImportUtil.getOutputPath(outputPath, tableName);
      LOGGER.info("Loading HFiles for {} from {}", tableName, tableOutputPath);
      loader.bulkLoad(TableName.valueOf(tableName), tableOutputPath);
      LOGGER.info("Incremental load complete for table=" + tableName);
    }
  }

  public static void main(String[] args) throws Exception {
    int exitStatus = ToolRunner.run(new AvroBulkLoadTool(), args);
    System.exit(exitStatus);
  }
}
