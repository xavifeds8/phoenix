# Testing REGEXP_LIKE with SQLLine

## Prerequisites
- Java 8 or later
- Maven
- HBase (or use Phoenix's standalone mode)

## Step 1: Build Phoenix with the New Function

```bash
cd /Users/xfernand/code/os/phoenix

# Build the entire project (this will take several minutes)
mvn clean install -DskipTests

# Or build just the client and assembly
mvn clean install -DskipTests -pl phoenix-core-client,phoenix-assembly -am
```

## Step 2: Start SQLLine

### Option A: Using Standalone Mode (Easiest)
```bash
# Navigate to the bin directory
cd /Users/xfernand/code/os/phoenix/bin

# Start SQLLine in standalone mode (no HBase required)
./sqlline.py
```

### Option B: With HBase Running
```bash
# Make sure HBase is running first
# Then connect to it
./sqlline.py localhost
```

## Step 3: Create Test Data

Once SQLLine is running, create a test table and insert data:

```sql
-- Create a test table
CREATE TABLE test_users (
    id INTEGER PRIMARY KEY,
    name VARCHAR,
    email VARCHAR,
    phone VARCHAR
);

-- Insert test data
UPSERT INTO test_users VALUES (1, 'John Doe', 'john.doe@example.com', '555-1234');
UPSERT INTO test_users VALUES (2, 'Jane Smith', 'jane.smith@test.org', '555-5678');
UPSERT INTO test_users VALUES (3, 'Bob Johnson', 'bob@company.net', '555-9012');
UPSERT INTO test_users VALUES (4, 'Alice Brown', 'alice.brown@example.com', '555-3456');
UPSERT INTO test_users VALUES (5, 'Charlie Wilson', 'charlie@test.org', '555-7890');

-- Commit the changes
!commit
```

## Step 4: Test REGEXP_LIKE Function

### Test 1: Basic Pattern Matching
```sql
-- Find emails with pattern firstname.lastname@domain
SELECT id, name, email 
FROM test_users 
WHERE REGEXP_LIKE(email, '^[a-z]+\.[a-z]+@.*$');
```

Expected output:
```
+-----+--------------+---------------------------+
| ID  | NAME         | EMAIL                     |
+-----+--------------+---------------------------+
| 1   | John Doe     | john.doe@example.com      |
| 2   | Jane Smith   | jane.smith@test.org       |
| 4   | Alice Brown  | alice.brown@example.com   |
+-----+--------------+---------------------------+
```

### Test 2: Phone Number Pattern
```sql
-- Find phone numbers starting with 555-1 or 555-5
SELECT id, name, phone 
FROM test_users 
WHERE REGEXP_LIKE(phone, '^555-[15].*');
```

Expected output:
```
+-----+-------------+-----------+
| ID  | NAME        | PHONE     |
+-----+-------------+-----------+
| 1   | John Doe    | 555-1234  |
| 2   | Jane Smith  | 555-5678  |
+-----+-------------+-----------+
```

### Test 3: Boolean Column
```sql
-- Create a boolean column showing if email is from example.com
SELECT id, 
       name, 
       email,
       REGEXP_LIKE(email, '@example\.com$') as IS_EXAMPLE
FROM test_users;
```

Expected output:
```
+-----+-----------------+---------------------------+--------------+
| ID  | NAME            | EMAIL                     | IS_EXAMPLE   |
+-----+-----------------+---------------------------+--------------+
| 1   | John Doe        | john.doe@example.com      | true         |
| 2   | Jane Smith      | jane.smith@test.org       | false        |
| 3   | Bob Johnson     | bob@company.net           | false        |
| 4   | Alice Brown     | alice.brown@example.com   | true         |
| 5   | Charlie Wilson  | charlie@test.org          | false        |
+-----+-----------------+---------------------------+--------------+
```

### Test 4: Negative Matching
```sql
-- Find users NOT from example.com
SELECT id, name, email 
FROM test_users 
WHERE NOT REGEXP_LIKE(email, '@example\.com$');
```

Expected output:
```
+-----+-----------------+------------------------+
| ID  | NAME            | EMAIL                  |
+-----+-----------------+------------------------+
| 2   | Jane Smith      | jane.smith@test.org    |
| 3   | Bob Johnson     | bob@company.net        |
| 5   | Charlie Wilson  | charlie@test.org       |
+-----+-----------------+------------------------+
```

### Test 5: Case-Insensitive Matching
```sql
-- Find names containing "john" (case-insensitive)
SELECT id, name 
FROM test_users 
WHERE REGEXP_LIKE(name, '(?i)john');
```

Expected output:
```
+-----+--------------+
| ID  | NAME         |
+-----+--------------+
| 1   | John Doe     |
| 3   | Bob Johnson  |
+-----+--------------+
```

### Test 6: Complex Email Validation
```sql
-- Validate email format
SELECT id, 
       email,
       REGEXP_LIKE(email, '^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$') as IS_VALID_EMAIL
FROM test_users;
```

All emails should return `true` for IS_VALID_EMAIL.

### Test 7: Count Matching Records
```sql
-- Count how many users have example.com emails
SELECT COUNT(*) as EXAMPLE_COUNT
FROM test_users 
WHERE REGEXP_LIKE(email, '@example\.com$');
```

Expected output:
```
+-----------------+
| EXAMPLE_COUNT   |
+-----------------+
| 2               |
+-----------------+
```

## Step 5: Clean Up

```sql
-- Drop the test table when done
DROP TABLE test_users;

-- Exit SQLLine
!quit
```

## Troubleshooting

### If REGEXP_LIKE is not recognized:
1. Make sure the build completed successfully
2. Verify you're using the newly built Phoenix binaries
3. Check that the function classes are in the classpath

### To verify the function is available:
```sql
-- This should not throw an error
SELECT REGEXP_LIKE('test', 't.*') FROM SYSTEM.CATALOG LIMIT 1;
```

### Check Phoenix version:
```sql
SELECT * FROM SYSTEM.CATALOG WHERE TABLE_NAME = 'CATALOG' LIMIT 1;
```

## Alternative: Quick Test Without Building

If you want to test the logic before building, you can use existing functions:

```sql
-- REGEXP_SUBSTR returns the matched string or null
-- We can check if it's not null to simulate REGEXP_LIKE
SELECT id, name, 
       CASE WHEN REGEXP_SUBSTR(email, '@example\.com$') IS NOT NULL 
            THEN true 
            ELSE false 
       END as matches
FROM test_users;
```

## Running Integration Tests

To run the integration tests we created:

```bash
cd /Users/xfernand/code/os/phoenix

# Run just the REGEXP_LIKE tests
mvn test -Dtest=RegexpLikeFunctionIT -pl phoenix-core

# Or run all regex function tests
mvn test -Dtest=Regexp*IT -pl phoenix-core
```

## Performance Testing

For performance testing with large datasets:

```sql
-- Create a larger test table
CREATE TABLE large_test (id INTEGER PRIMARY KEY, email VARCHAR);

-- Insert many rows (you can use UPSERT SELECT for bulk inserts)
-- Then test performance
EXPLAIN SELECT COUNT(*) FROM large_test WHERE REGEXP_LIKE(email, '@example\.com$');
```

The EXPLAIN will show you the query plan and help identify if indexes could improve performance.
