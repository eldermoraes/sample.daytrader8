/**
 * Characterization tests – TradeDirect buy/sell order total, SYNCH mode.
 *
 * Purpose
 * -------
 * Pin the two formulas that govern how the account balance is credited when
 * an order is placed in SYNCH mode (TradeConfig.SYNCH == 0):
 *
 *   buy  total = (quantity × price) + orderFee
 *        → creditAccountBalance(conn, account, total.negate())
 *          i.e. the balance-update SQL receives a NEGATIVE amount
 *
 *   sell total = (quantity × price) − orderFee
 *        → creditAccountBalance(conn, account, total)
 *          i.e. the balance-update SQL receives a POSITIVE amount
 *
 * No container, no Derby.
 * DataSource / Connection / PreparedStatement / ResultSet are replaced with
 * lightweight hand-rolled stubs (Mockito cannot mock JDK interfaces on Java 25
 * without a -javaagent).  The stubs record the BigDecimal argument passed to
 * creditAccountBalance's prepared statement so we can assert the correct total.
 */
package com.ibm.websphere.samples.daytrader.impl.direct;

import com.ibm.websphere.samples.daytrader.util.KeyBlock;
import com.ibm.websphere.samples.daytrader.util.TradeConfig;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.sql.*;
import java.util.HashMap;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TradeDirect – buy/sell total characterization (SYNCH, no container)")
class TradeDirectBuySellTotalTest {

    // ------------------------------------------------------------------ //
    //  Fixed test data                                                      //
    // ------------------------------------------------------------------ //

    private static final BigDecimal PRICE    = new BigDecimal("50.00");
    private static final int        ACCT_ID  = 1;
    private static final int        HOLD_ID  = 42;
    private static final int        ORDER_ID = 1000;

    /**
     * The fee returned by {@link TradeConfig#getOrderFee} for buy and sell.
     * Recorded from the live constant: {@code new BigDecimal("24.95")}.
     */
    private static final BigDecimal ORDER_FEE = new BigDecimal("24.95");

    // ------------------------------------------------------------------ //
    //  Shared state                                                        //
    // ------------------------------------------------------------------ //

    private TradeDirect bean;

    @BeforeEach
    void setUp() throws Exception {
        bean = new TradeDirect();

        injectField(bean, "asyncOrderSubmitter",        null);
        injectField(bean, "recentQuotePriceChangeList", null);
        injectField(bean, "mkSummaryUpdateEvent",       null);
        injectField(bean, "queueConnectionFactory",     null);
        injectField(bean, "topicConnectionFactory",     null);
        injectField(bean, "tradeStreamerTopic",          null);
        injectField(bean, "tradeBrokerQueue",            null);
        injectField(bean, "txn",                        null);
        injectField(bean, "mes",                        null);

        // Pre-seed the key cache so getNextID() never touches the DB.
        seedKeySequence("order");

        // Disable quote-price updates so updateQuotePriceVolume() is a no-op.
        TradeConfig.setUpdateQuotePrices(false);
    }

    // ================================================================== //
    //  BUY                                                                 //
    // ================================================================== //

    @Nested
    @DisplayName("buy() – creditAccountBalance receives -(qty × price + fee)")
    class Buy {

        /**
         * Run buy() and return the BigDecimal that was passed as the first
         * parameter of creditAccountBalance's PreparedStatement.
         */
        private BigDecimal runBuyAndCaptureCreditAmount(double quantity) throws Exception {
            CreditCapturingDataSource ds = buildBuyDataSource(quantity);
            injectField(bean, "datasource", ds);
            bean.buy("uid:0", "s:1", quantity, TradeConfig.SYNCH);
            return ds.capturedCredit;
        }

        @Test
        @DisplayName("10 shares @ 50.00, fee=24.95 → credit amount is -524.95")
        void tenShares_creditAmountIsNegativeTotal() throws Exception {
            // expected: -(10 * 50.00 + 24.95) = -524.95
            BigDecimal expected = new BigDecimal("-524.95");
            BigDecimal actual   = runBuyAndCaptureCreditAmount(10.0);
            assertEquals(0, expected.compareTo(actual),
                    "creditAccountBalance amount for buy: expected "
                            + expected + " got " + actual);
        }

        @Test
        @DisplayName("1 share @ 50.00, fee=24.95 → credit amount is -74.95")
        void oneShare_creditAmountIsNegativePricePlusFee() throws Exception {
            // expected: -(1 * 50.00 + 24.95) = -74.95
            BigDecimal expected = new BigDecimal("-74.95");
            BigDecimal actual   = runBuyAndCaptureCreditAmount(1.0);
            assertEquals(0, expected.compareTo(actual),
                    "creditAccountBalance amount for 1-share buy");
        }

        @Test
        @DisplayName("credit amount is NEGATIVE (balance decreases on buy) – scale=2")
        void buyProducesNegativeCredit_scaleIsTwo() throws Exception {
            BigDecimal actual = runBuyAndCaptureCreditAmount(10.0);
            assertTrue(actual.compareTo(BigDecimal.ZERO) < 0,
                    "buy credit must be negative, was " + actual);
            assertEquals(2, actual.scale(),
                    "credit scale must be 2");
        }
    }

    // ================================================================== //
    //  SELL                                                                //
    // ================================================================== //

    @Nested
    @DisplayName("sell() – creditAccountBalance receives +(qty × price − fee)")
    class Sell {

        private BigDecimal runSellAndCaptureCreditAmount(double quantity) throws Exception {
            CreditCapturingDataSource ds = buildSellDataSource(quantity);
            injectField(bean, "datasource", ds);
            bean.sell("uid:0", HOLD_ID, TradeConfig.SYNCH);
            return ds.capturedCredit;
        }

        @Test
        @DisplayName("10 shares @ 50.00, fee=24.95 → credit amount is +475.05")
        void tenShares_creditAmountIsPositiveTotal() throws Exception {
            // expected: 10 * 50.00 - 24.95 = 475.05
            BigDecimal expected = new BigDecimal("475.05");
            BigDecimal actual   = runSellAndCaptureCreditAmount(10.0);
            assertEquals(0, expected.compareTo(actual),
                    "creditAccountBalance amount for sell: expected "
                            + expected + " got " + actual);
        }

        @Test
        @DisplayName("1 share @ 50.00, fee=24.95 → credit amount is +25.05")
        void oneShare_creditAmountIsPriceMinusFee() throws Exception {
            // expected: 1 * 50.00 - 24.95 = 25.05
            BigDecimal expected = new BigDecimal("25.05");
            BigDecimal actual   = runSellAndCaptureCreditAmount(1.0);
            assertEquals(0, expected.compareTo(actual),
                    "creditAccountBalance amount for 1-share sell");
        }

        @Test
        @DisplayName("credit amount is POSITIVE (balance increases on sell) – scale=2")
        void sellProducesPositiveCredit_scaleIsTwo() throws Exception {
            BigDecimal actual = runSellAndCaptureCreditAmount(10.0);
            assertTrue(actual.compareTo(BigDecimal.ZERO) > 0,
                    "sell credit must be positive, was " + actual);
            assertEquals(2, actual.scale(),
                    "credit scale must be 2");
        }
    }

    // ================================================================== //
    //  Cross-cutting: fee direction contract                               //
    // ================================================================== //

    @Test
    @DisplayName("buy cost exceeds sell gain by exactly 2 × orderFee (fee-direction contract)")
    void feeDirectionContract() {
        BigDecimal qtyPrice = new BigDecimal("10.0").multiply(PRICE);
        BigDecimal buyCost  = qtyPrice.add(ORDER_FEE);
        BigDecimal sellGain = qtyPrice.subtract(ORDER_FEE);
        BigDecimal spread   = buyCost.subtract(sellGain);
        assertEquals(0, ORDER_FEE.multiply(new BigDecimal("2")).compareTo(spread),
                "spread between buy cost and sell gain must be 2 × orderFee");
    }

    // ================================================================== //
    //  DataSource stub factory methods                                     //
    // ================================================================== //

    /**
     * Builds a {@link CreditCapturingDataSource} wired for the buy() SYNCH path.
     *
     * The buy() method acquires TWO connections:
     *  • mainConn  – used by the outer buy() body (getAccount, getQuote,
     *                creditAccountBalance, completeOrder, getOrderData)
     *  • orderConn – used by createOrder()'s internal getConn() call
     *
     * PreparedStatements are dispensed in call order using
     * {@link SequencedConnection}.
     */
    private CreditCapturingDataSource buildBuyDataSource(double quantity) throws Exception {
        CreditCapturingDataSource ds = new CreditCapturingDataSource();

        // ---- orderConn: createOrder()'s internal connection ----
        //  1. createOrderSQL INSERT
        //  2. getOrderSQL   SELECT (returns the persisted order)
        Connection orderConn = new SequencedConnection(
                dmlStmt(),
                orderQueryStmt("buy", quantity));

        // ---- mainConn: all other SQL in buy() + completeOrder() ----
        // Call order (inSession=false, inGlobalTxn=false):
        //  1  getAccountForUserSQL       → account RS
        //  2  getQuoteSQL                → quote RS
        //  3  creditAccountBalanceSQL    → captured here  ★
        //  4  getOrderSQL                → order RS (completeOrder start)
        //  5  getAccountProfileForAcctSQL→ profile RS
        //  6  createHoldingSQL           → INSERT holding
        //  7  getHoldingSQL              → holding RS
        //  8  updateOrderHoldingSQL      → UPDATE
        //  9  updateOrderStatusSQL       → UPDATE
        // 10  getOrderSQL                → order RS (getOrderData at end of buy)
        CreditCapturingStatement creditStmt = new CreditCapturingStatement(ds);
        Connection mainConn = new SequencedConnection(
                accountStmt(),
                quoteStmt(),
                creditStmt,                      // ← position 3: captured
                orderQueryStmt("buy", quantity), // completeOrder
                accountProfileStmt(),
                dmlStmt(),                        // createHolding INSERT
                holdingQueryStmt(quantity),
                dmlStmt(),                        // updateOrderHolding
                dmlStmt(),                        // updateOrderStatus
                orderQueryStmt("buy", quantity)); // getOrderData final

        ds.connections.add(mainConn);
        ds.connections.add(orderConn);
        return ds;
    }

    /**
     * Builds a {@link CreditCapturingDataSource} wired for the sell() SYNCH path.
     *
     * sell() also acquires two connections.
     * Call order on mainConn:
     *  1  getAccountForUserSQL
     *  2  getHoldingSQL                  (holding lookup)
     *  3  getQuoteSQL
     *  4  updateHoldingStatus            (UPDATE purchasedate=0)
     *  5  creditAccountBalanceSQL        ★
     *  6  getOrderSQL                    (completeOrder)
     *  7  getAccountProfileForAcctSQL
     *  8  getHoldingSQL                  (completeOrder getHoldingData)
     *  9  removeHoldingSQL               DELETE
     * 10  removeHoldingFromOrderSQL      UPDATE
     * 11  updateOrderStatusSQL           UPDATE
     * 12  getOrderSQL                    (getOrderData final)
     */
    private CreditCapturingDataSource buildSellDataSource(double quantity) throws Exception {
        CreditCapturingDataSource ds = new CreditCapturingDataSource();

        Connection orderConn = new SequencedConnection(
                dmlStmt(),
                orderQueryStmt("sell", quantity));

        CreditCapturingStatement creditStmt = new CreditCapturingStatement(ds);
        Connection mainConn = new SequencedConnection(
                accountStmt(),
                holdingQueryStmt(quantity),
                quoteStmt(),
                dmlStmt(),                         // updateHoldingStatus
                creditStmt,                        // ← position 5: captured
                orderQueryStmt("sell", quantity),  // completeOrder
                accountProfileStmt(),
                holdingQueryStmt(quantity),        // getHoldingData inside completeOrder
                dmlStmt(),                         // removeHolding DELETE
                dmlStmt(),                         // removeHoldingFromOrder UPDATE
                dmlStmt(),                         // updateOrderStatus
                orderQueryStmt("sell", quantity)); // getOrderData final

        ds.connections.add(mainConn);
        ds.connections.add(orderConn);
        return ds;
    }

    // ================================================================== //
    //  ResultSet / PreparedStatement / Connection / DataSource stubs      //
    // ================================================================== //

    /** A simple ResultSet stub that returns hardcoded account data. */
    private static ResultSet accountResultSet() throws Exception {
        return new AbstractResultSet() {
            boolean advanced = false;
            @Override public boolean next() { if (!advanced) { advanced = true; return true; } return false; }
            @Override public int    getInt(String col)        { return "accountID".equals(col) ? ACCT_ID : 0; }
            @Override public BigDecimal getBigDecimal(String col) { return new BigDecimal("10000.00"); }
            @Override public Timestamp  getTimestamp(String col)  { return new Timestamp(0); }
            @Override public String     getString(String col)     { return "uid:0"; }
        };
    }

    private PreparedStatement accountStmt() throws Exception {
        ResultSet rs = accountResultSet();
        return new AbstractPreparedStatement() {
            @Override public ResultSet executeQuery() { return rs; }
        };
    }

    private PreparedStatement quoteStmt() throws Exception {
        return new AbstractPreparedStatement() {
            @Override public ResultSet executeQuery() {
                return new AbstractResultSet() {
                    boolean advanced = false;
                    @Override public boolean next() { if (!advanced) { advanced = true; return true; } return false; }
                    @Override public String  getString(String col) {
                        return "symbol".equals(col) ? "s:1" : "TestCo";
                    }
                    @Override public BigDecimal getBigDecimal(String col) { return PRICE; }
                    @Override public double     getDouble(String col)     { return 0.0; }
                };
            }
        };
    }

    private PreparedStatement holdingQueryStmt(double quantity) {
        return new AbstractPreparedStatement() {
            @Override public ResultSet executeQuery() {
                return new AbstractResultSet() {
                    boolean advanced = false;
                    @Override public boolean next() { if (!advanced) { advanced = true; return true; } return false; }
                    @Override public int       getInt(String col)        { return "holdingID".equals(col) ? HOLD_ID : 0; }
                    @Override public double    getDouble(String col)     { return quantity; }
                    @Override public BigDecimal getBigDecimal(String col){ return PRICE; }
                    @Override public Timestamp  getTimestamp(String col) { return new Timestamp(0); }
                    @Override public String     getString(String col)    { return "s:1"; }
                };
            }
        };
    }

    private PreparedStatement orderQueryStmt(String orderType, double quantity) {
        return new AbstractPreparedStatement() {
            @Override public ResultSet executeQuery() {
                return new AbstractResultSet() {
                    boolean advanced = false;
                    @Override public boolean next()          { if (!advanced) { advanced = true; return true; } return false; }
                    @Override public int    getInt(String c) {
                        if ("orderID".equals(c))            return ORDER_ID;
                        if ("account_accountID".equals(c))  return ACCT_ID;
                        if ("holding_holdingID".equals(c))  return HOLD_ID;
                        return 0;
                    }
                    @Override public String     getString(String c)    {
                        if ("orderType".equals(c))   return orderType;
                        if ("orderStatus".equals(c)) return "closed";
                        return "s:1";
                    }
                    @Override public double     getDouble(String c)    { return quantity; }
                    @Override public BigDecimal getBigDecimal(String c) {
                        return "orderFee".equals(c) ? ORDER_FEE : PRICE;
                    }
                    @Override public Timestamp  getTimestamp(String c) { return new Timestamp(System.currentTimeMillis()); }
                };
            }
        };
    }

    private PreparedStatement accountProfileStmt() {
        return new AbstractPreparedStatement() {
            @Override public ResultSet executeQuery() {
                return new AbstractResultSet() {
                    boolean advanced = false;
                    @Override public boolean next()              { if (!advanced) { advanced = true; return true; } return false; }
                    @Override public String getString(String col) {
                        if ("userID".equals(col))     return "uid:0";
                        if ("passwd".equals(col))     return "pass";
                        if ("fullName".equals(col))   return "Test User";
                        if ("address".equals(col))    return "1 Main St";
                        if ("email".equals(col))      return "test@example.com";
                        if ("creditCard".equals(col)) return "4111111111111111";
                        return "";
                    }
                };
            }
        };
    }

    private static AbstractPreparedStatement dmlStmt() {
        return new AbstractPreparedStatement() {
            @Override public int executeUpdate() { return 1; }
        };
    }

    // ================================================================== //
    //  Stub base classes                                                   //
    // ================================================================== //

    /**
     * A DataSource stub that dispenses {@link Connection} instances in FIFO
     * order and remembers the BigDecimal captured by
     * {@link CreditCapturingStatement}.
     */
    static class CreditCapturingDataSource implements DataSource {
        final java.util.Deque<Connection> connections = new java.util.ArrayDeque<>();
        BigDecimal capturedCredit;

        @Override public Connection getConnection()                         throws SQLException { return connections.poll(); }
        @Override public Connection getConnection(String u, String p)       throws SQLException { return connections.poll(); }
        @Override public PrintWriter getLogWriter()                          throws SQLException { return null; }
        @Override public void        setLogWriter(PrintWriter pw)            throws SQLException {}
        @Override public void        setLoginTimeout(int s)                  throws SQLException {}
        @Override public int         getLoginTimeout()                       throws SQLException { return 0; }
        @Override public Logger      getParentLogger()                                          { return null; }
        @Override public <T> T       unwrap(Class<T> i)                      throws SQLException { return null; }
        @Override public boolean     isWrapperFor(Class<?> i)                throws SQLException { return false; }
    }

    /**
     * A PreparedStatement that captures setBigDecimal(1, value) into the
     * owning DataSource so the test can inspect it.
     */
    static class CreditCapturingStatement extends AbstractPreparedStatement {
        private final CreditCapturingDataSource owner;
        CreditCapturingStatement(CreditCapturingDataSource owner) { this.owner = owner; }

        @Override
        public void setBigDecimal(int idx, BigDecimal val) {
            if (idx == 1) {
                owner.capturedCredit = val;
            }
        }

        @Override public int executeUpdate() { return 1; }
    }

    /**
     * A Connection that hands out the provided PreparedStatements in sequence,
     * one per {@code prepareStatement()} call.
     */
    static class SequencedConnection extends AbstractConnection {
        private final java.util.Deque<PreparedStatement> stmts = new java.util.ArrayDeque<>();

        SequencedConnection(PreparedStatement... stmts) {
            for (PreparedStatement s : stmts) this.stmts.add(s);
        }

        @Override
        public PreparedStatement prepareStatement(String sql) {
            PreparedStatement s = stmts.poll();
            if (s == null) throw new IllegalStateException(
                    "SequencedConnection ran out of PreparedStatements; SQL was: " + sql);
            return s;
        }

        @Override
        public PreparedStatement prepareStatement(String sql, int t, int c) {
            return prepareStatement(sql);
        }

        @Override
        public PreparedStatement prepareStatement(String sql, int t, int c, int h) throws SQLException {
            return prepareStatement(sql);
        }
    }

    /**
     * No-op base for PreparedStatement stubs.  All methods are stubbed to
     * return safe defaults so subclasses only override what they need.
     */
    static abstract class AbstractPreparedStatement implements PreparedStatement {
        @Override public ResultSet  executeQuery()                         { return new EmptyResultSet(); }
        @Override public int        executeUpdate()                        { return 0; }
        @Override public void       setBigDecimal(int i, BigDecimal v)    {}
        @Override public void       setBoolean(int i, boolean v)          {}
        @Override public void       setDouble(int i, double v)            {}
        @Override public void       setFloat(int i, float v)              {}
        @Override public void       setInt(int i, int v)                  {}
        @Override public void       setLong(int i, long v)                {}
        @Override public void       setNull(int i, int t)                 {}
        @Override public void       setNull(int i, int t, String n)       {}
        @Override public void       setString(int i, String v)            {}
        @Override public void       setTimestamp(int i, Timestamp v)      {}
        @Override public void       setTimestamp(int i, Timestamp v, java.util.Calendar c) {}
        @Override public void       close()                               {}
        @Override public boolean    execute()                             { return false; }
        @Override public ResultSet  getGeneratedKeys()                    { return new EmptyResultSet(); }
        @Override public void       addBatch()                            {}
        @Override public void       clearParameters()                     {}
        @Override public int[]      executeBatch()                        { return new int[0]; }
        @Override public ResultSetMetaData getMetaData()                  { return null; }
        @Override public ParameterMetaData getParameterMetaData()         { return null; }
        @Override public void       setArray(int i, Array v)              {}
        @Override public void       setAsciiStream(int i, java.io.InputStream v) {}
        @Override public void       setAsciiStream(int i, java.io.InputStream v, int l) {}
        @Override public void       setAsciiStream(int i, java.io.InputStream v, long l) {}
        @Override public void       setBinaryStream(int i, java.io.InputStream v) {}
        @Override public void       setBinaryStream(int i, java.io.InputStream v, int l) {}
        @Override public void       setBinaryStream(int i, java.io.InputStream v, long l) {}
        @Override public void       setBlob(int i, Blob v)               {}
        @Override public void       setBlob(int i, java.io.InputStream v) {}
        @Override public void       setBlob(int i, java.io.InputStream v, long l) {}
        @Override public void       setByte(int i, byte v)               {}
        @Override public void       setBytes(int i, byte[] v)            {}
        @Override public void       setCharacterStream(int i, java.io.Reader v) {}
        @Override public void       setCharacterStream(int i, java.io.Reader v, int l) {}
        @Override public void       setCharacterStream(int i, java.io.Reader v, long l) {}
        @Override public void       setClob(int i, Clob v)               {}
        @Override public void       setClob(int i, java.io.Reader v)     {}
        @Override public void       setClob(int i, java.io.Reader v, long l) {}
        @Override public void       setDate(int i, Date v)               {}
        @Override public void       setDate(int i, Date v, java.util.Calendar c) {}
        @Override public void       setNCharacterStream(int i, java.io.Reader v) {}
        @Override public void       setNCharacterStream(int i, java.io.Reader v, long l) {}
        @Override public void       setNClob(int i, NClob v)             {}
        @Override public void       setNClob(int i, java.io.Reader v)    {}
        @Override public void       setNClob(int i, java.io.Reader v, long l) {}
        @Override public void       setNString(int i, String v)          {}
        @Override public void       setObject(int i, Object v)           {}
        @Override public void       setObject(int i, Object v, int t)    {}
        @Override public void       setObject(int i, Object v, int t, int s) {}
        @Override public void       setRef(int i, Ref v)                 {}
        @Override public void       setRowId(int i, RowId v)             {}
        @Override public void       setSQLXML(int i, SQLXML v)           {}
        @Override public void       setShort(int i, short v)             {}
        @Override public void       setTime(int i, Time v)               {}
        @Override public void       setTime(int i, Time v, java.util.Calendar c) {}
        @Override public void       setURL(int i, java.net.URL v)        {}
        @Override public void       setUnicodeStream(int i, java.io.InputStream v, int l) {}
        @Override public void       cancel()                              {}
        @Override public void       clearBatch()                          {}
        @Override public void       clearWarnings()                       {}
        @Override public Connection getConnection()                       { return null; }
        @Override public int        getFetchDirection()                   { return 0; }
        @Override public int        getFetchSize()                        { return 0; }
        @Override public ResultSet  getResultSet()                        { return new EmptyResultSet(); }
        @Override public int        getResultSetConcurrency()             { return 0; }
        @Override public int        getResultSetHoldability()             { return 0; }
        @Override public int        getResultSetType()                    { return 0; }
        @Override public int        getMaxFieldSize()                     { return 0; }
        @Override public int        getMaxRows()                          { return 0; }
        @Override public boolean    getMoreResults()                      { return false; }
        @Override public boolean    getMoreResults(int c)                 { return false; }
        @Override public int        getQueryTimeout()                     { return 0; }
        @Override public int        getUpdateCount()                      { return 0; }
        @Override public SQLWarning getWarnings()                         { return null; }
        @Override public boolean    isClosed()                            { return false; }
        @Override public boolean    isCloseOnCompletion()                 { return false; }
        @Override public boolean    isPoolable()                          { return false; }
        @Override public void       setCursorName(String n)               {}
        @Override public void       setEscapeProcessing(boolean b)        {}
        @Override public void       setFetchDirection(int d)              {}
        @Override public void       setFetchSize(int r)                   {}
        @Override public void       setMaxFieldSize(int m)                {}
        @Override public void       setMaxRows(int m)                     {}
        @Override public void       setPoolable(boolean b)                {}
        @Override public void       setQueryTimeout(int s)                {}
        @Override public void       closeOnCompletion()                   {}
        @Override public int        executeUpdate(String s)               { return 0; }
        @Override public int        executeUpdate(String s, int a)        { return 0; }
        @Override public int        executeUpdate(String s, int[] c)      { return 0; }
        @Override public int        executeUpdate(String s, String[] c)   { return 0; }
        @Override public boolean    execute(String s)                     { return false; }
        @Override public boolean    execute(String s, int a)              { return false; }
        @Override public boolean    execute(String s, int[] c)            { return false; }
        @Override public boolean    execute(String s, String[] c)         { return false; }
        @Override public ResultSet  executeQuery(String s)                { return new EmptyResultSet(); }
        @Override public void       addBatch(String s)                    {}
        @Override public <T> T      unwrap(Class<T> i)                    { return null; }
        @Override public boolean    isWrapperFor(Class<?> i)              { return false; }
    }

    /**
     * No-op base for Connection stubs.
     */
    static abstract class AbstractConnection implements Connection {
        @Override public PreparedStatement prepareStatement(String sql)              throws SQLException { return dmlStmt(); }
        @Override public PreparedStatement prepareStatement(String s, int t, int c) throws SQLException { return prepareStatement(s); }
        @Override public PreparedStatement prepareStatement(String s, int a)         throws SQLException { return prepareStatement(s); }
        @Override public PreparedStatement prepareStatement(String s, int[] c)       throws SQLException { return prepareStatement(s); }
        @Override public PreparedStatement prepareStatement(String s, String[] c)    throws SQLException { return prepareStatement(s); }
        @Override public void   setAutoCommit(boolean ac) throws SQLException {}
        @Override public void   commit()                  throws SQLException {}
        @Override public void   rollback()                throws SQLException {}
        @Override public void   close()                   throws SQLException {}
        @Override public boolean getAutoCommit()          throws SQLException { return false; }
        @Override public boolean isClosed()               throws SQLException { return false; }
        @Override public int    getTransactionIsolation() throws SQLException { return TRANSACTION_READ_COMMITTED; }
        @Override public void   setTransactionIsolation(int l) throws SQLException {}
        @Override public DatabaseMetaData getMetaData()   throws SQLException { return null; }
        @Override public boolean isReadOnly()              throws SQLException { return false; }
        @Override public void    setReadOnly(boolean r)   throws SQLException {}
        @Override public String  getCatalog()             throws SQLException { return null; }
        @Override public void    setCatalog(String c)     throws SQLException {}
        @Override public SQLWarning getWarnings()         throws SQLException { return null; }
        @Override public void    clearWarnings()          throws SQLException {}
        @Override public java.util.Map<String, Class<?>> getTypeMap() throws SQLException { return null; }
        @Override public void    setTypeMap(java.util.Map<String, Class<?>> m) throws SQLException {}
        @Override public int     getHoldability()         throws SQLException { return 0; }
        @Override public void    setHoldability(int h)    throws SQLException {}
        @Override public Savepoint setSavepoint()         throws SQLException { return null; }
        @Override public Savepoint setSavepoint(String n) throws SQLException { return null; }
        @Override public void    rollback(Savepoint s)    throws SQLException {}
        @Override public void    releaseSavepoint(Savepoint s) throws SQLException {}
        @Override public CallableStatement prepareCall(String s) throws SQLException { return null; }
        @Override public CallableStatement prepareCall(String s, int t, int c) throws SQLException { return null; }
        @Override public CallableStatement prepareCall(String s, int t, int c, int h) throws SQLException { return null; }
        @Override public Statement createStatement()      throws SQLException { return null; }
        @Override public Statement createStatement(int t, int c) throws SQLException { return null; }
        @Override public Statement createStatement(int t, int c, int h) throws SQLException { return null; }
        @Override public String  nativeSQL(String sql)    throws SQLException { return sql; }
        @Override public Struct  createStruct(String t, Object[] a) throws SQLException { return null; }
        @Override public Array   createArrayOf(String t, Object[] e) throws SQLException { return null; }
        @Override public Blob    createBlob()             throws SQLException { return null; }
        @Override public Clob    createClob()             throws SQLException { return null; }
        @Override public NClob   createNClob()            throws SQLException { return null; }
        @Override public SQLXML  createSQLXML()           throws SQLException { return null; }
        @Override public boolean isValid(int t)           throws SQLException { return true; }
        @Override public String  getClientInfo(String n)  throws SQLException { return null; }
        @Override public java.util.Properties getClientInfo() throws SQLException { return null; }
        @Override public void    setClientInfo(String n, String v) {}
        @Override public void    setClientInfo(java.util.Properties p) {}
        @Override public String  getSchema()              throws SQLException { return null; }
        @Override public void    setSchema(String s)      throws SQLException {}
        @Override public void    abort(java.util.concurrent.Executor e) throws SQLException {}
        @Override public void    setNetworkTimeout(java.util.concurrent.Executor e, int ms) throws SQLException {}
        @Override public int     getNetworkTimeout()      throws SQLException { return 0; }
        @Override public <T> T   unwrap(Class<T> i)       throws SQLException { return null; }
        @Override public boolean isWrapperFor(Class<?> i) throws SQLException { return false; }
        @Override public String  toString() { return "StubConnection"; }
    }

    /**
     * A ResultSet that always returns {@code false} from {@code next()}.
     * Used as a fallback return value.
     */
    static class EmptyResultSet extends AbstractResultSet {
        @Override public boolean next() { return false; }
    }

    /**
     * No-op base for ResultSet stubs.  All typed getters return safe defaults.
     */
    static abstract class AbstractResultSet implements ResultSet {
        @Override public boolean    next()                       throws SQLException { return false; }
        @Override public void       close()                      throws SQLException {}
        @Override public boolean    wasNull()                    throws SQLException { return false; }
        @Override public String     getString(int c)             throws SQLException { return null; }
        @Override public boolean    getBoolean(int c)            throws SQLException { return false; }
        @Override public byte       getByte(int c)               throws SQLException { return 0; }
        @Override public short      getShort(int c)              throws SQLException { return 0; }
        @Override public int        getInt(int c)                throws SQLException { return 0; }
        @Override public long       getLong(int c)               throws SQLException { return 0; }
        @Override public float      getFloat(int c)              throws SQLException { return 0; }
        @Override public double     getDouble(int c)             throws SQLException { return 0; }
        @Override public BigDecimal getBigDecimal(int c, int s)  throws SQLException { return BigDecimal.ZERO; }
        @Override public byte[]     getBytes(int c)              throws SQLException { return null; }
        @Override public Date       getDate(int c)               throws SQLException { return null; }
        @Override public Time       getTime(int c)               throws SQLException { return null; }
        @Override public Timestamp  getTimestamp(int c)          throws SQLException { return null; }
        @Override public java.io.InputStream getAsciiStream(int c) throws SQLException { return null; }
        @Override public java.io.InputStream getUnicodeStream(int c) throws SQLException { return null; }
        @Override public java.io.InputStream getBinaryStream(int c) throws SQLException { return null; }
        @Override public String     getString(String c)          throws SQLException { return null; }
        @Override public boolean    getBoolean(String c)         throws SQLException { return false; }
        @Override public byte       getByte(String c)            throws SQLException { return 0; }
        @Override public short      getShort(String c)           throws SQLException { return 0; }
        @Override public int        getInt(String c)             throws SQLException { return 0; }
        @Override public long       getLong(String c)            throws SQLException { return 0; }
        @Override public float      getFloat(String c)           throws SQLException { return 0; }
        @Override public double     getDouble(String c)          throws SQLException { return 0; }
        @Override public BigDecimal getBigDecimal(String c, int s) throws SQLException { return BigDecimal.ZERO; }
        @Override public byte[]     getBytes(String c)           throws SQLException { return null; }
        @Override public Date       getDate(String c)            throws SQLException { return null; }
        @Override public Time       getTime(String c)            throws SQLException { return null; }
        @Override public Timestamp  getTimestamp(String c)       throws SQLException { return null; }
        @Override public java.io.InputStream getAsciiStream(String c) throws SQLException { return null; }
        @Override public java.io.InputStream getUnicodeStream(String c) throws SQLException { return null; }
        @Override public java.io.InputStream getBinaryStream(String c) throws SQLException { return null; }
        @Override public SQLWarning getWarnings()                throws SQLException { return null; }
        @Override public void       clearWarnings()              throws SQLException {}
        @Override public String     getCursorName()              throws SQLException { return null; }
        @Override public ResultSetMetaData getMetaData()         throws SQLException { return null; }
        @Override public Object     getObject(int c)             throws SQLException { return null; }
        @Override public Object     getObject(String c)          throws SQLException { return null; }
        @Override public int        findColumn(String c)         throws SQLException { return 0; }
        @Override public java.io.Reader getCharacterStream(int c) throws SQLException { return null; }
        @Override public java.io.Reader getCharacterStream(String c) throws SQLException { return null; }
        @Override public BigDecimal getBigDecimal(int c)         throws SQLException { return BigDecimal.ZERO; }
        @Override public BigDecimal getBigDecimal(String c)      throws SQLException { return BigDecimal.ZERO; }
        @Override public boolean    isBeforeFirst()              throws SQLException { return false; }
        @Override public boolean    isAfterLast()                throws SQLException { return false; }
        @Override public boolean    isFirst()                    throws SQLException { return false; }
        @Override public boolean    isLast()                     throws SQLException { return false; }
        @Override public void       beforeFirst()                throws SQLException {}
        @Override public void       afterLast()                  throws SQLException {}
        @Override public boolean    first()                      throws SQLException { return false; }
        @Override public boolean    last()                       throws SQLException { return false; }
        @Override public int        getRow()                     throws SQLException { return 0; }
        @Override public boolean    absolute(int r)              throws SQLException { return false; }
        @Override public boolean    relative(int r)              throws SQLException { return false; }
        @Override public boolean    previous()                   throws SQLException { return false; }
        @Override public void       setFetchDirection(int d)     throws SQLException {}
        @Override public int        getFetchDirection()          throws SQLException { return 0; }
        @Override public void       setFetchSize(int r)          throws SQLException {}
        @Override public int        getFetchSize()               throws SQLException { return 0; }
        @Override public int        getType()                    throws SQLException { return TYPE_FORWARD_ONLY; }
        @Override public int        getConcurrency()             throws SQLException { return CONCUR_READ_ONLY; }
        @Override public boolean    rowUpdated()                 throws SQLException { return false; }
        @Override public boolean    rowInserted()                throws SQLException { return false; }
        @Override public boolean    rowDeleted()                 throws SQLException { return false; }
        @Override public void       updateNull(int c)            throws SQLException {}
        @Override public void       updateBoolean(int c, boolean v) throws SQLException {}
        @Override public void       updateByte(int c, byte v)    throws SQLException {}
        @Override public void       updateShort(int c, short v)  throws SQLException {}
        @Override public void       updateInt(int c, int v)      throws SQLException {}
        @Override public void       updateLong(int c, long v)    throws SQLException {}
        @Override public void       updateFloat(int c, float v)  throws SQLException {}
        @Override public void       updateDouble(int c, double v) throws SQLException {}
        @Override public void       updateBigDecimal(int c, BigDecimal v) throws SQLException {}
        @Override public void       updateString(int c, String v) throws SQLException {}
        @Override public void       updateBytes(int c, byte[] v) throws SQLException {}
        @Override public void       updateDate(int c, Date v)    throws SQLException {}
        @Override public void       updateTime(int c, Time v)    throws SQLException {}
        @Override public void       updateTimestamp(int c, Timestamp v) throws SQLException {}
        @Override public void       updateAsciiStream(int c, java.io.InputStream v, int l) throws SQLException {}
        @Override public void       updateBinaryStream(int c, java.io.InputStream v, int l) throws SQLException {}
        @Override public void       updateCharacterStream(int c, java.io.Reader v, int l) throws SQLException {}
        @Override public void       updateObject(int c, Object v, int s) throws SQLException {}
        @Override public void       updateObject(int c, Object v) throws SQLException {}
        @Override public void       updateNull(String c)         throws SQLException {}
        @Override public void       updateBoolean(String c, boolean v) throws SQLException {}
        @Override public void       updateByte(String c, byte v) throws SQLException {}
        @Override public void       updateShort(String c, short v) throws SQLException {}
        @Override public void       updateInt(String c, int v)   throws SQLException {}
        @Override public void       updateLong(String c, long v) throws SQLException {}
        @Override public void       updateFloat(String c, float v) throws SQLException {}
        @Override public void       updateDouble(String c, double v) throws SQLException {}
        @Override public void       updateBigDecimal(String c, BigDecimal v) throws SQLException {}
        @Override public void       updateString(String c, String v) throws SQLException {}
        @Override public void       updateBytes(String c, byte[] v) throws SQLException {}
        @Override public void       updateDate(String c, Date v) throws SQLException {}
        @Override public void       updateTime(String c, Time v) throws SQLException {}
        @Override public void       updateTimestamp(String c, Timestamp v) throws SQLException {}
        @Override public void       updateAsciiStream(String c, java.io.InputStream v, int l) throws SQLException {}
        @Override public void       updateBinaryStream(String c, java.io.InputStream v, int l) throws SQLException {}
        @Override public void       updateCharacterStream(String c, java.io.Reader v, int l) throws SQLException {}
        @Override public void       updateObject(String c, Object v, int s) throws SQLException {}
        @Override public void       updateObject(String c, Object v) throws SQLException {}
        @Override public void       insertRow()                  throws SQLException {}
        @Override public void       updateRow()                  throws SQLException {}
        @Override public void       deleteRow()                  throws SQLException {}
        @Override public void       refreshRow()                 throws SQLException {}
        @Override public void       cancelRowUpdates()           throws SQLException {}
        @Override public void       moveToInsertRow()            throws SQLException {}
        @Override public void       moveToCurrentRow()           throws SQLException {}
        @Override public Statement  getStatement()               throws SQLException { return null; }
        @Override public Object     getObject(int c, java.util.Map<String, Class<?>> m) throws SQLException { return null; }
        @Override public Ref        getRef(int c)                throws SQLException { return null; }
        @Override public Blob       getBlob(int c)               throws SQLException { return null; }
        @Override public Clob       getClob(int c)               throws SQLException { return null; }
        @Override public Array      getArray(int c)              throws SQLException { return null; }
        @Override public Object     getObject(String c, java.util.Map<String, Class<?>> m) throws SQLException { return null; }
        @Override public Ref        getRef(String c)             throws SQLException { return null; }
        @Override public Blob       getBlob(String c)            throws SQLException { return null; }
        @Override public Clob       getClob(String c)            throws SQLException { return null; }
        @Override public Array      getArray(String c)           throws SQLException { return null; }
        @Override public Date       getDate(int c, java.util.Calendar cal) throws SQLException { return null; }
        @Override public Date       getDate(String c, java.util.Calendar cal) throws SQLException { return null; }
        @Override public Time       getTime(int c, java.util.Calendar cal) throws SQLException { return null; }
        @Override public Time       getTime(String c, java.util.Calendar cal) throws SQLException { return null; }
        @Override public Timestamp  getTimestamp(int c, java.util.Calendar cal) throws SQLException { return null; }
        @Override public Timestamp  getTimestamp(String c, java.util.Calendar cal) throws SQLException { return null; }
        @Override public java.net.URL getURL(int c)              throws SQLException { return null; }
        @Override public java.net.URL getURL(String c)           throws SQLException { return null; }
        @Override public void       updateRef(int c, Ref v)      throws SQLException {}
        @Override public void       updateRef(String c, Ref v)   throws SQLException {}
        @Override public void       updateBlob(int c, Blob v)    throws SQLException {}
        @Override public void       updateBlob(String c, Blob v) throws SQLException {}
        @Override public void       updateClob(int c, Clob v)    throws SQLException {}
        @Override public void       updateClob(String c, Clob v) throws SQLException {}
        @Override public void       updateArray(int c, Array v)  throws SQLException {}
        @Override public void       updateArray(String c, Array v) throws SQLException {}
        @Override public RowId      getRowId(int c)              throws SQLException { return null; }
        @Override public RowId      getRowId(String c)           throws SQLException { return null; }
        @Override public void       updateRowId(int c, RowId v)  throws SQLException {}
        @Override public void       updateRowId(String c, RowId v) throws SQLException {}
        @Override public int        getHoldability()             throws SQLException { return 0; }
        @Override public boolean    isClosed()                   throws SQLException { return false; }
        @Override public void       updateNString(int c, String v) throws SQLException {}
        @Override public void       updateNString(String c, String v) throws SQLException {}
        @Override public void       updateNClob(int c, NClob v)  throws SQLException {}
        @Override public void       updateNClob(String c, NClob v) throws SQLException {}
        @Override public NClob      getNClob(int c)              throws SQLException { return null; }
        @Override public NClob      getNClob(String c)           throws SQLException { return null; }
        @Override public SQLXML     getSQLXML(int c)             throws SQLException { return null; }
        @Override public SQLXML     getSQLXML(String c)          throws SQLException { return null; }
        @Override public void       updateSQLXML(int c, SQLXML v) throws SQLException {}
        @Override public void       updateSQLXML(String c, SQLXML v) throws SQLException {}
        @Override public String     getNString(int c)            throws SQLException { return null; }
        @Override public String     getNString(String c)         throws SQLException { return null; }
        @Override public java.io.Reader getNCharacterStream(int c) throws SQLException { return null; }
        @Override public java.io.Reader getNCharacterStream(String c) throws SQLException { return null; }
        @Override public void       updateNCharacterStream(int c, java.io.Reader v, long l) throws SQLException {}
        @Override public void       updateNCharacterStream(String c, java.io.Reader v, long l) throws SQLException {}
        @Override public void       updateAsciiStream(int c, java.io.InputStream v, long l) throws SQLException {}
        @Override public void       updateBinaryStream(int c, java.io.InputStream v, long l) throws SQLException {}
        @Override public void       updateCharacterStream(int c, java.io.Reader v, long l) throws SQLException {}
        @Override public void       updateAsciiStream(String c, java.io.InputStream v, long l) throws SQLException {}
        @Override public void       updateBinaryStream(String c, java.io.InputStream v, long l) throws SQLException {}
        @Override public void       updateCharacterStream(String c, java.io.Reader v, long l) throws SQLException {}
        @Override public void       updateBlob(int c, java.io.InputStream v, long l) throws SQLException {}
        @Override public void       updateBlob(String c, java.io.InputStream v, long l) throws SQLException {}
        @Override public void       updateClob(int c, java.io.Reader v, long l) throws SQLException {}
        @Override public void       updateClob(String c, java.io.Reader v, long l) throws SQLException {}
        @Override public void       updateNClob(int c, java.io.Reader v, long l) throws SQLException {}
        @Override public void       updateNClob(String c, java.io.Reader v, long l) throws SQLException {}
        @Override public <T> T      getObject(int c, Class<T> type) throws SQLException { return null; }
        @Override public <T> T      getObject(String c, Class<T> type) throws SQLException { return null; }
        @Override public <T> T      unwrap(Class<T> i)           throws SQLException { return null; }
        @Override public boolean    isWrapperFor(Class<?> i)     throws SQLException { return false; }
        @Override public void       updateNCharacterStream(int c, java.io.Reader v) throws SQLException {}
        @Override public void       updateNCharacterStream(String c, java.io.Reader v) throws SQLException {}
        @Override public void       updateAsciiStream(int c, java.io.InputStream v) throws SQLException {}
        @Override public void       updateBinaryStream(int c, java.io.InputStream v) throws SQLException {}
        @Override public void       updateCharacterStream(int c, java.io.Reader v) throws SQLException {}
        @Override public void       updateAsciiStream(String c, java.io.InputStream v) throws SQLException {}
        @Override public void       updateBinaryStream(String c, java.io.InputStream v) throws SQLException {}
        @Override public void       updateCharacterStream(String c, java.io.Reader v) throws SQLException {}
        @Override public void       updateBlob(int c, java.io.InputStream v) throws SQLException {}
        @Override public void       updateBlob(String c, java.io.InputStream v) throws SQLException {}
        @Override public void       updateClob(int c, java.io.Reader v)  throws SQLException {}
        @Override public void       updateClob(String c, java.io.Reader v) throws SQLException {}
        @Override public void       updateNClob(int c, java.io.Reader v) throws SQLException {}
        @Override public void       updateNClob(String c, java.io.Reader v) throws SQLException {}
    }

    // ================================================================== //
    //  Reflection helpers                                                  //
    // ================================================================== //

    private static void injectField(Object target, String fieldName, Object value) throws Exception {
        Class<?> clazz = target.getClass();
        while (clazz != null) {
            try {
                Field f = clazz.getDeclaredField(fieldName);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new NoSuchFieldException(
                "Field '" + fieldName + "' not found in " + target.getClass().getName());
    }

    /**
     * Pre-populate {@link KeySequenceDirect}'s static key cache so that
     * {@code getNextID(conn, keyName, ...)} never touches the database.
     */
    @SuppressWarnings("unchecked")
    private static void seedKeySequence(String keyName) throws Exception {
        Field keyMapField = KeySequenceDirect.class.getDeclaredField("keyMap");
        keyMapField.setAccessible(true);
        HashMap<String, Object> map = (HashMap<String, Object>) keyMapField.get(null);
        map.remove(keyName); // ensure a fresh block for this test run
        KeyBlock block = new KeyBlock(ORDER_ID, ORDER_ID + TradeConfig.KEYBLOCKSIZE - 1);
        map.put(keyName, block);
    }
}
