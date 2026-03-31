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
package org.apache.phoenix.expression.function;

import java.io.DataInput;
import java.io.IOException;
import java.util.List;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.phoenix.expression.Determinism;
import org.apache.phoenix.expression.Expression;
import org.apache.phoenix.expression.util.regex.AbstractBasePattern;
import org.apache.phoenix.parse.FunctionParseNode;
import org.apache.phoenix.parse.FunctionParseNode.Argument;
import org.apache.phoenix.parse.FunctionParseNode.BuiltInFunction;
import org.apache.phoenix.parse.RegexpLikeParseNode;
import org.apache.phoenix.schema.SortOrder;
import org.apache.phoenix.schema.tuple.Tuple;
import org.apache.phoenix.schema.types.PBoolean;
import org.apache.phoenix.schema.types.PDataType;
import org.apache.phoenix.schema.types.PVarchar;

/**
 * Implementation of the REGEXP_LIKE built-in function.
 * 
 * REGEXP_LIKE compares a string to a pattern and returns true if the string matches
 * the pattern, false otherwise. This is similar to the LIKE operator but uses
 * Java regular expressions instead of SQL patterns.
 * 
 * Usage: REGEXP_LIKE(source_string, pattern)
 * 
 * @since 5.3.0
 */
@BuiltInFunction(name = RegexpLikeFunction.NAME, nodeClass = RegexpLikeParseNode.class,
    args = { @Argument(allowedTypes = { PVarchar.class }),
      @Argument(allowedTypes = { PVarchar.class }) },
    classType = FunctionParseNode.FunctionClassType.ABSTRACT, derivedFunctions = {
      ByteBasedRegexpLikeFunction.class, StringBasedRegexpLikeFunction.class })
public abstract class RegexpLikeFunction extends ScalarFunction {
  public static final String NAME = "REGEXP_LIKE";

  private static final PDataType TYPE = PVarchar.INSTANCE;
  private AbstractBasePattern pattern;

  public RegexpLikeFunction() {
  }

  public RegexpLikeFunction(List<Expression> children) {
    super(children);
    init();
  }

  protected abstract AbstractBasePattern compilePatternSpec(String value);

  private void init() {
    ImmutableBytesWritable ptr = new ImmutableBytesWritable();
    Expression patternExpr = getPatternExpression();
    if (patternExpr.isStateless() && patternExpr.getDeterminism() == Determinism.ALWAYS
        && patternExpr.evaluate(null, ptr)) {
      String patternStr = (String) TYPE.toObject(ptr, patternExpr.getDataType(),
          patternExpr.getSortOrder());
      if (patternStr != null) {
        pattern = compilePatternSpec(patternStr);
      }
    }
  }

  @Override
  public boolean evaluate(Tuple tuple, ImmutableBytesWritable ptr) {
    // Evaluate the pattern if it wasn't constant
    AbstractBasePattern pattern = this.pattern;
    if (pattern == null) {
      Expression patternExpr = getPatternExpression();
      if (!patternExpr.evaluate(tuple, ptr)) {
        return false;
      }
      if (ptr.getLength() == 0) {
        ptr.set(PDataType.FALSE_BYTES);
        return true;
      }
      String patternStr = (String) TYPE.toObject(ptr, patternExpr.getDataType(),
          patternExpr.getSortOrder());
      if (patternStr == null) {
        return false;
      }
      pattern = compilePatternSpec(patternStr);
    }

    // Evaluate the source string
    Expression sourceExpr = getSourceExpression();
    if (!sourceExpr.evaluate(tuple, ptr)) {
      return false;
    }

    // Coerce to VARCHAR and perform the search (not full match)
    TYPE.coerceBytes(ptr, TYPE, sourceExpr.getSortOrder(), SortOrder.ASC);
    // Use search instead of matches - REGEXP_LIKE finds pattern anywhere in string
    boolean found = pattern.search(ptr);
    ptr.set(found ? PDataType.TRUE_BYTES : PDataType.FALSE_BYTES);
    return true;
  }

  private Expression getSourceExpression() {
    return children.get(0);
  }

  private Expression getPatternExpression() {
    return children.get(1);
  }

  @Override
  public PDataType getDataType() {
    return PBoolean.INSTANCE;
  }

  @Override
  public void readFields(DataInput input) throws IOException {
    super.readFields(input);
    init();
  }

  @Override
  public String getName() {
    return NAME;
  }
}
