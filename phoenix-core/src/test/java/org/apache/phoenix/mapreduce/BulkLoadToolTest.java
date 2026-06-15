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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collection;
import org.apache.phoenix.util.SchemaUtil;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import org.apache.phoenix.thirdparty.org.apache.commons.cli.CommandLine;

@RunWith(Parameterized.class)
public class BulkLoadToolTest {

  @Parameterized.Parameters
  public static Collection<Object[]> params() {
    return Arrays.asList(new Object[][] { { new CsvBulkLoadTool() }, { new JsonBulkLoadTool() }, });
  }

  @Parameterized.Parameter(value = 0)
  public AbstractBulkLoadTool bulkLoadTool;

  @Test
  public void testParseOptions() {
    CommandLine cmdLine =
      bulkLoadTool.parseOptions(new String[] { "--input", "/input", "--table", "mytable" });

    assertEquals("mytable", cmdLine.getOptionValue(CsvBulkLoadTool.TABLE_NAME_OPT.getOpt()));
    assertEquals("/input", cmdLine.getOptionValue(CsvBulkLoadTool.INPUT_PATH_OPT.getOpt()));
  }

  @Test(expected = IllegalStateException.class)
  public void testParseOptions_ExtraArguments() {
    bulkLoadTool.parseOptions(new String[] { "--input", "/input", "--table", "mytable", "these",
      "shouldnt", "be", "here" });
  }

  @Test(expected = IllegalStateException.class)
  public void testParseOptions_NoInput() {
    bulkLoadTool.parseOptions(new String[] { "--table", "mytable" });
  }

  @Test(expected = IllegalStateException.class)
  public void testParseOptions_NoTable() {
    bulkLoadTool.parseOptions(new String[] { "--input", "/input" });
  }

  @Test
  public void testParseOptions_BadRecordsPath() {
    CommandLine cmdLine = bulkLoadTool.parseOptions(new String[] { "--input", "/input", "--table",
      "mytable", "--bad-records-path", "/tmp/bad-records" });
    assertEquals("/tmp/bad-records",
      cmdLine.getOptionValue(AbstractBulkLoadTool.BAD_RECORDS_PATH_OPT.getOpt()));
  }

  @Test
  public void testParseOptions_MaxErrors() {
    CommandLine cmdLine = bulkLoadTool.parseOptions(new String[] { "--input", "/input", "--table",
      "mytable", "--max-errors", "100" });
    assertEquals("100",
      cmdLine.getOptionValue(AbstractBulkLoadTool.MAX_ERRORS_OPT.getOpt()));
  }

  @Test
  public void testParseOptions_ErrorThresholdPct() {
    CommandLine cmdLine = bulkLoadTool.parseOptions(new String[] { "--input", "/input", "--table",
      "mytable", "--error-threshold-pct", "5.5" });
    assertEquals("5.5",
      cmdLine.getOptionValue(AbstractBulkLoadTool.ERROR_THRESHOLD_PCT_OPT.getOpt()));
  }

  @Test
  public void testParseOptions_AllErrorHandlingOptions() {
    CommandLine cmdLine = bulkLoadTool.parseOptions(new String[] { "--input", "/input", "--table",
      "mytable", "--bad-records-path", "/tmp/bad", "--max-errors", "50",
      "--error-threshold-pct", "10" });
    assertEquals("/tmp/bad",
      cmdLine.getOptionValue(AbstractBulkLoadTool.BAD_RECORDS_PATH_OPT.getOpt()));
    assertEquals("50",
      cmdLine.getOptionValue(AbstractBulkLoadTool.MAX_ERRORS_OPT.getOpt()));
    assertEquals("10",
      cmdLine.getOptionValue(AbstractBulkLoadTool.ERROR_THRESHOLD_PCT_OPT.getOpt()));
    // These options imply --ignore-errors, but that's checked at runtime in loadData()
    assertTrue(true);
  }

  @Test
  public void testGetQualifiedTableName() {
    assertEquals("MYSCHEMA.MYTABLE", SchemaUtil.getQualifiedTableName("mySchema", "myTable"));
  }

  @Test
  public void testGetQualifiedTableName_NullSchema() {
    assertEquals("MYTABLE", SchemaUtil.getQualifiedTableName(null, "myTable"));
  }
}
