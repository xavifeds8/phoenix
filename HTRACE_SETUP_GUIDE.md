# Phoenix HTrace Setup and Usage Guide

This guide documents how to set up and use HTrace (distributed tracing) with Apache Phoenix.

## Prerequisites

- Apache Phoenix built from source
- Apache HBase running
- Python 3 installed
- ZooKeeper running

## Step 1: Build the Phoenix Project

```bash
mvn clean package -DskipTests
```

This builds all Phoenix modules including the tracing webapp.

## Step 2: Deploy Phoenix Server JAR to HBase

Copy the Phoenix server JAR to HBase's lib directory:

```bash
cp phoenix-server/target/phoenix-server-hbase-<version>-<hbase-version>.jar $HBASE_HOME/lib/
```

Replace `<version>` and `<hbase-version>` with your actual versions (e.g., `phoenix-server-hbase-2.5-5.3.0.jar`).

## Step 3: Configure HBase for Tracing

Add the following properties to `$HBASE_HOME/conf/hbase-site.xml`:

```xml
<property>
  <name>phoenix.trace.frequency</name>
  <value>always</value>
  <description>Frequency of tracing: always, never, or probability (0.0-1.0)</description>
</property>

<property>
  <name>phoenix.trace.statsTableName</name>
  <value>SYSTEM.TRACING_STATS</value>
  <description>Table name where trace statistics are stored</description>
</property>

<property>
  <name>phoenix.trace.enabled</name>
  <value>true</value>
  <description>Enable Phoenix tracing</description>
</property>
```

### Configuration Options:

- **phoenix.trace.frequency**:
  - `always` - Trace every query
  - `never` - Disable tracing
  - `0.0` to `1.0` - Probability of tracing (e.g., `0.1` = 10% of queries)

- **phoenix.trace.statsTableName**: 
  - Default: `SYSTEM.TRACING_STATS`
  - Custom table name for storing trace data

- **phoenix.trace.enabled**:
  - `true` - Enable tracing
  - `false` - Disable tracing

## Step 4: Restart HBase

After adding the configuration, restart HBase:

```bash
$HBASE_HOME/bin/stop-hbase.sh
$HBASE_HOME/bin/start-hbase.sh
```

## Step 5: Create the Tracing Table

Connect to Phoenix using sqlline and create the tracing stats table:

```bash
python3 bin/sqlline.py localhost
```

Then execute:

```sql
CREATE TABLE SYSTEM.TRACING_STATS (
  trace_id BIGINT NOT NULL,
  parent_id BIGINT NOT NULL,
  span_id BIGINT NOT NULL,
  description VARCHAR,
  start_time BIGINT,
  end_time BIGINT,
  hostname VARCHAR,
  tags.count SMALLINT,
  annotations.count SMALLINT,
  CONSTRAINT pk PRIMARY KEY (trace_id, parent_id, span_id)
);
```

## Step 6: Start the Trace Server

Start the Phoenix trace server to visualize traces:

```bash
python3 bin/traceserver.py
```

The trace server will start on `http://localhost:8864` by default.

## Step 7: Generate Trace Data

### Option 1: Enable Tracing in sqlline

In sqlline, enable tracing for your session:

```sql
-- Enable tracing for this connection
TRACE ON;

-- Run some queries to generate trace data
CREATE TABLE t (a BIGINT NOT NULL PRIMARY KEY, b BIGINT);
UPSERT INTO t(a,b) VALUES(1,1);
SELECT * FROM t;

-- Disable tracing
TRACE OFF;
```

### Option 2: Verify Traces Were Captured

Check the tracing stats table:

```sql
SELECT * FROM SYSTEM.TRACING_STATS;
```

You should see trace records with trace_id, parent_id, span_id, description, timing information, etc.

## Step 8: View Traces in the UI

1. Open your browser and navigate to: `http://localhost:8864`

2. The Phoenix Tracing Web UI provides several views:
   - **Trace List**: View all captured traces
   - **Trace Details**: Click on a trace to see detailed span information
   - **Timeline View**: Visualize trace spans over time
   - **Distribution**: See trace distribution across hosts

3. Use the browser's developer tools (F12) to inspect API calls:
   - `/trace?action=getall&limit=25` - Get all traces
   - `/trace?action=getCount` - Get trace counts
   - `/trace?action=searchTrace&traceid=<id>` - Search specific trace

## Troubleshooting

### Issue: NoSuchMethodError for Jackson

**Error:**
```
java.lang.NoSuchMethodError: 'com.fasterxml.jackson.databind.ObjectWriter 
org.apache.phoenix.util.JacksonUtil.getObjectWriter()'
```

**Solution:**
This was fixed in the changes. Ensure you've rebuilt the tracing webapp:
```bash
cd phoenix-tracing-webapp
mvn clean package -DskipTests
```

### Issue: Connection Refused to ZooKeeper

**Error:**
```
java.net.ConnectException: Connection refused
```

**Solution:**
Ensure ZooKeeper and HBase are running:
```bash
# Check if HBase is running
jps | grep HMaster

# If not running, start HBase
$HBASE_HOME/bin/start-hbase.sh
```

### Issue: Tracing Table Not Found

**Error:**
```
Table SYSTEM.TRACING_STATS not found
```

**Solution:**
Create the tracing table using the SQL in Step 5.

### Issue: No Traces Appearing

**Possible causes:**
1. Tracing not enabled in hbase-site.xml
2. TRACE ON not executed in sqlline
3. No queries executed after enabling tracing

**Solution:**
- Verify hbase-site.xml has `phoenix.trace.enabled=true`
- Execute `TRACE ON;` in sqlline before running queries
- Run some queries to generate trace data

## Understanding Trace Data

### Trace Hierarchy:
```
Trace (trace_id)
  └─ Span (span_id, parent_id)
       ├─ Start time
       ├─ End time
       ├─ Description (operation name)
       ├─ Hostname
       └─ Annotations/Tags
```

### Common Trace Descriptions:
- `Executing query` - Query execution
- `Compiling query` - Query compilation
- `Getting connection` - Connection acquisition
- `Scanning table` - Table scan operations
- `Committing mutations` - Write operations

## API Endpoints

The trace server exposes the following REST endpoints:

### Get All Traces
```
GET /trace?action=getall&limit=25
```
Returns the most recent traces (default limit: 25)

### Get Trace Count by Description
```
GET /trace?action=getCount
```
Returns count of traces grouped by description

### Get Trace Distribution by Hostname
```
GET /trace?action=getDistribution
```
Returns count of traces grouped by hostname

### Search Specific Trace
```
GET /trace?action=searchTrace&traceid=<trace_id>&parentid=<parent_id>
```
Search for specific trace by ID

## Advanced Configuration

### Custom Trace Server Port

Set the port via environment variable:

```bash
export PHOENIX_TRACESERVER_OPTS="-Dphoenix.traceserver.http.port=9090"
python3 bin/traceserver.py
```

### Trace Sampling

To trace only a percentage of queries, set in hbase-site.xml:

```xml
<property>
  <name>phoenix.trace.frequency</name>
  <value>0.1</value>  <!-- Trace 10% of queries -->
</property>
```

### Custom Tracing Table

To use a different table for traces:

```xml
<property>
  <name>phoenix.trace.statsTableName</name>
  <value>MY_SCHEMA.MY_TRACES</value>
</property>
```

Then create the table with the same schema as shown in Step 5.

## Code Changes Made to Fix Tracing

### Problem
The tracing webapp had Jackson dependency issues causing `NoSuchMethodError`.

### Solution
1. **TraceServlet.java**: Changed from direct ObjectMapper to JacksonUtil
2. **trace-server-runnable.xml**: Added phoenix-core-client and Jackson dependencies
3. **pom.xml**: Added explicit Jackson and phoenix-core-client dependencies

### Key Insight
- Hadoop/HBase dependencies should NOT be in the runnable JAR
- They're provided by phoenix-client-embedded-jar in the classpath
- Including them causes dependency conflicts

## References

- Apache Phoenix Documentation: https://phoenix.apache.org/
- HTrace Documentation: https://htrace.incubator.apache.org/
- Phoenix Tracing Webapp: `phoenix-tracing-webapp/README.md`

## Notes

- This guide is based on Phoenix 5.3.0 with HBase 2.5/2.6
- Tracing has performance overhead - use sampling in production
- The trace server is a standalone web application, not part of HBase
- Traces are stored in a Phoenix table and can be queried like any other data
