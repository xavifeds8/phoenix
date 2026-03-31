# REGEXP_LIKE Function Implementation

## Overview
This document describes the implementation of the REGEXP_LIKE function for Apache Phoenix, addressing JIRA issue PHOENIX-7276.

## What is REGEXP_LIKE?
REGEXP_LIKE is a SQL function that compares a string to a regular expression pattern and returns a boolean value:
- Returns `TRUE` if the string matches the pattern
- Returns `FALSE` if the string does not match the pattern

## Syntax
```sql
REGEXP_LIKE(source_string, pattern)
```

### Parameters
- `source_string`: The string to be tested (VARCHAR)
- `pattern`: A Java-compatible regular expression pattern (VARCHAR)

### Return Type
BOOLEAN

## Implementation Details

### Files Created

1. **RegexpLikeFunction.java** (`phoenix-core-client/src/main/java/org/apache/phoenix/expression/function/`)
   - Abstract base class for the REGEXP_LIKE function
   - Handles pattern compilation and caching for constant patterns
   - Evaluates the match operation using the pattern's `matches()` method
   - Returns BOOLEAN (PBoolean) data type

2. **ByteBasedRegexpLikeFunction.java** (`phoenix-core-client/src/main/java/org/apache/phoenix/expression/function/`)
   - Concrete implementation using JONI (byte-based) pattern matching
   - Used when `phoenix.query.useByteBasedRegex` is set to `true` (default)
   - More efficient for byte-level operations

3. **StringBasedRegexpLikeFunction.java** (`phoenix-core-client/src/main/java/org/apache/phoenix/expression/function/`)
   - Concrete implementation using Java Pattern (string-based) matching
   - Used when `phoenix.query.useByteBasedRegex` is set to `false`
   - Uses standard Java regex engine

4. **RegexpLikeParseNode.java** (`phoenix-core-client/src/main/java/org/apache/phoenix/parse/`)
   - Parse node factory that creates the appropriate function implementation
   - Selects between byte-based and string-based implementations based on configuration

5. **RegexpLikeFunctionIT.java** (`phoenix-core/src/it/java/org/apache/phoenix/end2end/`)
   - Comprehensive integration tests covering various use cases
   - Tests pattern matching, boolean results, edge cases, and prepared statements

## Architecture

The implementation follows the same pattern as existing regex functions in Phoenix (REGEXP_SUBSTR, REGEXP_REPLACE):

```
RegexpLikeFunction (abstract)
    ├── ByteBasedRegexpLikeFunction (uses JONIPattern)
    └── StringBasedRegexpLikeFunction (uses JavaPattern)
```

Both pattern implementations (JONIPattern and JavaPattern) already had the `matches()` method implemented in the AbstractBasePattern interface, which made this implementation straightforward.

## Usage Examples

### Basic Pattern Matching
```sql
-- Find emails with specific pattern
SELECT name, email 
FROM users 
WHERE REGEXP_LIKE(email, '^[a-z]+\.[a-z]+@.*$');
```

### Phone Number Validation
```sql
-- Find phone numbers starting with 555-1 or 555-5
SELECT id, phone 
FROM contacts 
WHERE REGEXP_LIKE(phone, '^555-[15].*');
```

### Boolean Column
```sql
-- Create a boolean column indicating if email is from example.com
SELECT id, 
       email,
       REGEXP_LIKE(email, '@example\.com$') as is_example_email
FROM users;
```

### With NOT Operator
```sql
-- Find records that don't match the pattern
SELECT * 
FROM users 
WHERE NOT REGEXP_LIKE(email, '@example\.com$');
```

### Case-Insensitive Matching
```sql
-- Use (?i) flag for case-insensitive matching
SELECT * 
FROM users 
WHERE REGEXP_LIKE(name, '(?i)john');
```

### With Prepared Statements
```java
String sql = "SELECT * FROM users WHERE REGEXP_LIKE(name, ?)";
PreparedStatement stmt = conn.prepareStatement(sql);
stmt.setString(1, "^[JA].*");
ResultSet rs = stmt.executeQuery();
```

## Testing

The implementation includes comprehensive integration tests that cover:

1. **Basic Pattern Matching**: Email patterns, phone patterns, name patterns
2. **Boolean Results**: Verifying TRUE/FALSE return values
3. **Complex Patterns**: Email validation with full regex patterns
4. **Negative Matching**: Using NOT REGEXP_LIKE
5. **Prepared Statements**: Dynamic pattern binding
6. **Edge Cases**: Empty strings, NULL values
7. **Case Sensitivity**: Default case-sensitive and case-insensitive with (?i) flag

## Performance Considerations

1. **Pattern Caching**: Constant patterns are compiled once and cached for reuse
2. **Byte vs String**: The byte-based implementation (JONI) is generally faster for byte-level operations
3. **Configuration**: Users can choose between implementations via `phoenix.query.useByteBasedRegex` property

## Comparison with Other Functions

| Function | Purpose | Return Type | Arguments |
|----------|---------|-------------|-----------|
| REGEXP_LIKE | Test if string matches pattern | BOOLEAN | (string, pattern) |
| REGEXP_SUBSTR | Extract matching substring | VARCHAR | (string, pattern, offset) |
| REGEXP_REPLACE | Replace matching substrings | VARCHAR | (string, pattern, replacement) |

## Build Status

✅ Successfully compiled with Maven:
```bash
mvn clean compile -DskipTests -pl phoenix-core-client -am
```

Build completed successfully with no errors.

## Next Steps

To complete the implementation:

1. ✅ Implement function classes
2. ✅ Create parse node
3. ✅ Write integration tests
4. ✅ Verify compilation
5. ⏳ Run integration tests
6. ⏳ Update Phoenix documentation
7. ⏳ Submit pull request to Apache Phoenix

## References

- JIRA Issue: [PHOENIX-7276](https://issues.apache.org/jira/browse/PHOENIX-7276)
- Phoenix Functions Documentation: https://phoenix.apache.org/language/functions.html
- Related Functions: REGEXP_SUBSTR, REGEXP_REPLACE, REGEXP_SPLIT
