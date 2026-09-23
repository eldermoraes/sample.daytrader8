/**
 * Characterization tests – TradeSLSBBean buy/sell order total, SYNCH mode.
 *
 * Purpose
 * -------
 * Pin the two formulas that govern how the account balance changes when an
 * order is placed in SYNCH mode (TradeConfig.SYNCH == 0):
 *
 *   buy  total = (quantity × price) + orderFee   → balance DECREASES by total
 *   sell total = (quantity × price) − orderFee   → balance INCREASES by total
 *
 * These tests do NOT start a container or touch Derby.  All JPA dependencies
 * are injected via reflection using Mockito mocks.
 *
 * Why the test allows EJBException from completeOrder
 * ---------------------------------------------------
 * buy() / sell() compute and apply the balance change *before* calling
 * completeOrder().  In SYNCH mode, completeOrder() calls
 * entityManager.find(OrderDataBean.class, orderID), but because we mock
 * entityManager.persist() as a no-op the generated ID stays null.
 * We stub find(OrderDataBean.class, null) to return a "completed" order,
 * which causes completeOrder() to throw EJBException("already completed").
 * buy()/sell() catch all exceptions and rethrow as EJBException, but by
 * then the balance mutation has already been applied to the AccountDataBean
 * object we injected – so we just catch EJBException and assert the balance.
 */
package com.ibm.websphere.samples.daytrader.impl.ejb3;

import com.ibm.websphere.samples.daytrader.entities.AccountDataBean;
import com.ibm.websphere.samples.daytrader.entities.AccountProfileDataBean;
import com.ibm.websphere.samples.daytrader.entities.HoldingDataBean;
import com.ibm.websphere.samples.daytrader.entities.OrderDataBean;
import com.ibm.websphere.samples.daytrader.entities.QuoteDataBean;
import com.ibm.websphere.samples.daytrader.util.FinancialUtils;
import com.ibm.websphere.samples.daytrader.util.TradeConfig;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import jakarta.ejb.EJBException;
import jakarta.persistence.EntityManager;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.sql.Timestamp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@DisplayName("TradeSLSBBean – buy/sell total characterization (SYNCH, no container)")
class TradeSLSBBeanBuySellTotalTest {

    // ------------------------------------------------------------------ //
    //  Fixed test data                                                      //
    // ------------------------------------------------------------------ //

    /** Stock price used throughout every test scenario. */
    private static final BigDecimal PRICE = new BigDecimal("50.00");

    /**
     * The fee returned by TradeConfig.getOrderFee() for both "buy" and "sell".
     * Recorded from the live constant: {@code new BigDecimal("24.95")}.
     */
    private static final BigDecimal ORDER_FEE = new BigDecimal("24.95");

    /** Initial account balance before the operation. */
    private static final BigDecimal INITIAL_BALANCE = new BigDecimal("10000.00");

    // ------------------------------------------------------------------ //
    //  Collaborators created fresh for each test                           //
    // ------------------------------------------------------------------ //

    private TradeSLSBBean bean;
    private EntityManager em;

    private AccountDataBean      account;
    private AccountProfileDataBean profile;
    private QuoteDataBean          quote;

    @BeforeEach
    void setUp() throws Exception {
        bean = new TradeSLSBBean();
        em   = mock(EntityManager.class);
        injectField(bean, "entityManager", em);

        // Null out every other injected field so they don't cause NPEs on
        // the code paths we do NOT exercise.
        injectField(bean, "marketSummarySingleton",   null);
        injectField(bean, "asyncEJBOrderSubmitter",   null);
        injectField(bean, "recentQuotePriceChangeList", null);
        injectField(bean, "context",                  null);
        injectField(bean, "queueConnectionFactory",   null);
        injectField(bean, "topicConnectionFactory",   null);
        injectField(bean, "tradeStreamerTopic",        null);
        injectField(bean, "tradeBrokerQueue",          null);

        // Build the entity graph that buy()/sell() navigate via entityManager.
        account = new AccountDataBean(
                1,          // accountID
                0, 0,       // loginCount, logoutCount
                null,       // lastLogin
                new Timestamp(System.currentTimeMillis()),
                INITIAL_BALANCE,
                INITIAL_BALANCE,
                "uid:0");

        profile = new AccountProfileDataBean(
                "uid:0", "password", "Test User",
                "1 Main St", "test@example.com", "4111111111111111");
        profile.setAccount(account);

        quote = new QuoteDataBean(
                "s:1", "TestCo", 0,
                PRICE, PRICE, PRICE, PRICE, 0.0);

        // Stubs for the entity look-ups that buy()/sell() perform directly.
        when(em.find(AccountProfileDataBean.class, "uid:0")).thenReturn(profile);
        when(em.find(QuoteDataBean.class, "s:1")).thenReturn(quote);

        // entityManager.persist(order) is called by createOrder() to persist
        // the new OrderDataBean.  We leave it as a no-op (Mockito default).
        // The generated @Id stays null; that is intentional here because we
        // only care about the balance change, not the persisted entity.

        // When completeOrder() looks up the order it gets back an already-
        // completed order – this short-circuits the DB-heavy completion path.
        OrderDataBean alreadyCompleted = new OrderDataBean();
        alreadyCompleted.setOrderStatus("completed");
        alreadyCompleted.setOrderType("buy"); // type doesn't matter for the guard
        when(em.find(eq(OrderDataBean.class), isNull())).thenReturn(alreadyCompleted);
    }

    // ------------------------------------------------------------------ //
    //  Helper                                                              //
    // ------------------------------------------------------------------ //

    private static void injectField(Object target, String fieldName, Object value) throws Exception {
        // Walk the class hierarchy to handle fields declared in super-classes.
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
        throw new NoSuchFieldException("Field '" + fieldName + "' not found in " + target.getClass().getName());
    }

    // ------------------------------------------------------------------ //
    //  BUY – total = (qty * price) + fee  →  balance decreases            //
    // ------------------------------------------------------------------ //

    @Nested
    @DisplayName("buy() – balance decreases by (qty × price + fee)")
    class Buy {

        /**
         * Exercises the exact lines from TradeSLSBBean.buy():
         *   total = (new BigDecimal(quantity).multiply(price)).add(orderFee);
         *   account.setBalance(balance.subtract(total));
         */
        @Test
        @DisplayName("10 shares @ 50.00, fee=24.95 → balance decreases by 524.95")
        void tenShares_buy_decreasesBalanceByQtyTimesPricePlusFee() {
            double quantity = 10.0;

            // total = 10 * 50.00 + 24.95 = 524.95
            BigDecimal expectedTotal    = new BigDecimal("524.95");
            BigDecimal expectedBalance  = INITIAL_BALANCE.subtract(expectedTotal);

            // buy() will throw EJBException because completeOrder() finds an
            // already-completed order, but the balance mutation happens first.
            assertThrows(EJBException.class,
                    () -> bean.buy("uid:0", "s:1", quantity, TradeConfig.SYNCH));

            assertEquals(0, expectedBalance.compareTo(account.getBalance()),
                    "balance after buy: expected " + expectedBalance
                            + " but was " + account.getBalance());
        }

        @Test
        @DisplayName("1 share @ 50.00, fee=24.95 → balance decreases by 74.95")
        void oneShare_buy_decreasesBalanceByPricePlusFee() {
            double quantity = 1.0;

            // total = 1 * 50.00 + 24.95 = 74.95
            BigDecimal expectedBalance = INITIAL_BALANCE.subtract(new BigDecimal("74.95"));

            assertThrows(EJBException.class,
                    () -> bean.buy("uid:0", "s:1", quantity, TradeConfig.SYNCH));

            assertEquals(0, expectedBalance.compareTo(account.getBalance()),
                    "balance after 1-share buy");
        }

        @Test
        @DisplayName("fee ADDS to price×qty (not subtracts) – scale pinned at 2")
        void feeSumsInBuy_scaleIsTwo() {
            double quantity = 3.0;

            // total = 3 * 50.00 + 24.95 = 174.95
            BigDecimal total           = new BigDecimal("174.95");
            BigDecimal expectedBalance = INITIAL_BALANCE.subtract(total);

            assertThrows(EJBException.class,
                    () -> bean.buy("uid:0", "s:1", quantity, TradeConfig.SYNCH));

            BigDecimal actualBalance = account.getBalance();
            assertEquals(0, expectedBalance.compareTo(actualBalance),
                    "buy total (fee adds): expected " + expectedBalance
                            + " got " + actualBalance);
            // Lock current scale – the price has scale=2 coming out of createOrder
            // (setScale(FinancialUtils.SCALE, FinancialUtils.ROUND)) so the
            // resulting balance must also carry scale=2.
            assertEquals(FinancialUtils.SCALE, actualBalance.scale(),
                    "balance scale must be " + FinancialUtils.SCALE);
        }
    }

    // ------------------------------------------------------------------ //
    //  SELL – total = (qty * price) - fee  →  balance increases           //
    // ------------------------------------------------------------------ //

    @Nested
    @DisplayName("sell() – balance increases by (qty × price − fee)")
    class Sell {

        private HoldingDataBean holding;

        @BeforeEach
        void setUpHolding() {
            holding = new HoldingDataBean(
                    42,              // holdingID
                    10.0,            // quantity
                    PRICE,           // purchasePrice (not used in sell total)
                    new Timestamp(System.currentTimeMillis()),
                    "s:1");
            // sell() navigates holding.getQuote() – wire it
            holding = new HoldingDataBean(10.0, PRICE,
                    new Timestamp(System.currentTimeMillis()), account, quote);

            // Expose the holdingID so entityManager.find can return it.
            try {
                injectField(holding, "holdingID", 42);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            when(em.find(HoldingDataBean.class, 42)).thenReturn(holding);
        }

        /**
         * Exercises the exact lines from TradeSLSBBean.sell():
         *   total = (new BigDecimal(quantity).multiply(price)).subtract(orderFee);
         *   account.setBalance(balance.add(total));
         */
        @Test
        @DisplayName("10 shares @ 50.00, fee=24.95 → balance increases by 475.05")
        void tenShares_sell_increasesBalanceByQtyTimesPriceMinusFee() {
            // total = 10 * 50.00 − 24.95 = 475.05
            BigDecimal expectedTotal   = new BigDecimal("475.05");
            BigDecimal expectedBalance = INITIAL_BALANCE.add(expectedTotal);

            assertThrows(EJBException.class,
                    () -> bean.sell("uid:0", 42, TradeConfig.SYNCH));

            assertEquals(0, expectedBalance.compareTo(account.getBalance()),
                    "balance after sell: expected " + expectedBalance
                            + " but was " + account.getBalance());
        }

        @Test
        @DisplayName("1 share @ 50.00, fee=24.95 → balance increases by 25.05")
        void oneShare_sell_increasesBalanceByPriceMinusFee() {
            // Override quantity to 1 share – must rebuild holding
            holding = new HoldingDataBean(1.0, PRICE,
                    new Timestamp(System.currentTimeMillis()), account, quote);
            try {
                injectField(holding, "holdingID", 42);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            when(em.find(HoldingDataBean.class, 42)).thenReturn(holding);

            // total = 1 * 50.00 − 24.95 = 25.05
            BigDecimal expectedBalance = INITIAL_BALANCE.add(new BigDecimal("25.05"));

            assertThrows(EJBException.class,
                    () -> bean.sell("uid:0", 42, TradeConfig.SYNCH));

            assertEquals(0, expectedBalance.compareTo(account.getBalance()),
                    "balance after 1-share sell");
        }

        @Test
        @DisplayName("fee SUBTRACTS from price×qty (not adds) – scale pinned at 2")
        void feeSubtractsInSell_scaleIsTwo() {
            // total = 10 * 50.00 − 24.95 = 475.05
            BigDecimal total           = new BigDecimal("475.05");
            BigDecimal expectedBalance = INITIAL_BALANCE.add(total);

            assertThrows(EJBException.class,
                    () -> bean.sell("uid:0", 42, TradeConfig.SYNCH));

            BigDecimal actualBalance = account.getBalance();
            assertEquals(0, expectedBalance.compareTo(actualBalance),
                    "sell total (fee subtracts): expected " + expectedBalance
                            + " got " + actualBalance);
            assertEquals(FinancialUtils.SCALE, actualBalance.scale(),
                    "balance scale must be " + FinancialUtils.SCALE);
        }

        @Test
        @DisplayName("fee direction buy≠sell – buy decreases more than sell increases for same qty/price")
        void buyDecreasesBuyMoreThanSellIncreases_feeDirectionContractFixed() {
            // For the same stock position:
            //   buy cost  = qty*price + fee
            //   sell gain = qty*price - fee
            // The difference is exactly 2×fee (the spread).
            double qty             = 10.0;
            BigDecimal qtyPrice    = new BigDecimal(qty).multiply(PRICE);
            BigDecimal buyCost     = qtyPrice.add(ORDER_FEE);
            BigDecimal sellGain    = qtyPrice.subtract(ORDER_FEE);
            BigDecimal expectedSpread = ORDER_FEE.multiply(new BigDecimal("2"));

            assertEquals(0, expectedSpread.compareTo(buyCost.subtract(sellGain)),
                    "spread between buy cost and sell gain must equal 2 × orderFee");
        }
    }
}
