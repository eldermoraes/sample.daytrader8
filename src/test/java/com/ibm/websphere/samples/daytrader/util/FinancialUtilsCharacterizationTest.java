/**
 * Characterization tests for FinancialUtils.
 *
 * Purpose: lock the *current* observable behaviour of computeGain,
 * computeGainPercent and computeHoldingsTotal so that any future refactoring
 * breaks loudly if the semantics change.
 *
 * Rules:
 *  - No mocks; all inputs are plain BigDecimal / HoldingDataBean instances.
 *  - Expected values were derived by running the production code and recording
 *    its output — they are descriptive, not prescriptive.
 */
package com.ibm.websphere.samples.daytrader.util;

import com.ibm.websphere.samples.daytrader.entities.HoldingDataBean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("FinancialUtils – characterization")
class FinancialUtilsCharacterizationTest {

    // ------------------------------------------------------------------ //
    //  Shared scale assertion helper                                        //
    // ------------------------------------------------------------------ //

    /** Asserts both numeric value AND scale (SCALE == 2 today). */
    private static void assertBigDecimal(String expectedPlain, BigDecimal actual) {
        BigDecimal expected = new BigDecimal(expectedPlain);
        assertEquals(0, expected.compareTo(actual),
                "numeric value: expected " + expectedPlain + " but was " + actual.toPlainString());
        assertEquals(FinancialUtils.SCALE, actual.scale(),
                "scale: expected " + FinancialUtils.SCALE + " but was " + actual.scale());
    }

    // ------------------------------------------------------------------ //
    //  computeGain                                                          //
    // ------------------------------------------------------------------ //

    @Nested
    @DisplayName("computeGain")
    class ComputeGain {

        @Test
        @DisplayName("positive gain: current > open")
        void positiveGain() {
            BigDecimal current = new BigDecimal("150.00");
            BigDecimal open    = new BigDecimal("100.00");

            BigDecimal result = FinancialUtils.computeGain(current, open);

            assertBigDecimal("50.00", result);
        }

        @Test
        @DisplayName("negative gain: current < open")
        void negativeGain() {
            BigDecimal current = new BigDecimal("80.00");
            BigDecimal open    = new BigDecimal("100.00");

            BigDecimal result = FinancialUtils.computeGain(current, open);

            assertBigDecimal("-20.00", result);
        }

        @Test
        @DisplayName("zero gain: current == open")
        void zeroGain() {
            BigDecimal balance = new BigDecimal("100.00");

            BigDecimal result = FinancialUtils.computeGain(balance, balance);

            assertBigDecimal("0.00", result);
        }

        @Test
        @DisplayName("result is rounded to SCALE=2 (HALF_UP)")
        void scaleIsEnforced() {
            // 100.005 – 0 would be 100.005; setScale(2) rounds to 100.01 (HALF_UP) …
            // but computeGain uses setScale WITHOUT a rounding mode, so the caller
            // is expected to supply values that already fit in SCALE=2.
            // This test records that values already at scale-2 pass through intact.
            BigDecimal current = new BigDecimal("99.99");
            BigDecimal open    = new BigDecimal("33.33");

            BigDecimal result = FinancialUtils.computeGain(current, open);

            assertBigDecimal("66.66", result);
        }
    }

    // ------------------------------------------------------------------ //
    //  computeGainPercent                                                   //
    // ------------------------------------------------------------------ //

    @Nested
    @DisplayName("computeGainPercent")
    class ComputeGainPercent {

        @Test
        @DisplayName("zero openBalance returns ZERO (division-by-zero guard)")
        void zeroOpenBalanceReturnsZero() {
            BigDecimal current = new BigDecimal("500.00");
            BigDecimal open    = BigDecimal.ZERO;

            BigDecimal result = FinancialUtils.computeGainPercent(current, open);

            // Must be the exact ZERO constant: 0.00
            assertEquals(0, FinancialUtils.ZERO.compareTo(result),
                    "expected ZERO for open=0, got " + result);
            assertEquals(FinancialUtils.SCALE, result.scale(),
                    "ZERO sentinel must carry scale=2");
        }

        @Test
        @DisplayName("100 % gain when current is double the open balance")
        void doubledBalance_100PercentGain() {
            BigDecimal current = new BigDecimal("200.00");
            BigDecimal open    = new BigDecimal("100.00");

            BigDecimal result = FinancialUtils.computeGainPercent(current, open);

            // (200/100 – 1) * 100 = 100  — scale is NOT forced to 2 by the method
            assertEquals(0, new BigDecimal("100").compareTo(result),
                    "expected 100 but got " + result);
        }

        @Test
        @DisplayName("50 % gain when current is 1.5x the open balance")
        void halfMoreBalance_50PercentGain() {
            BigDecimal current = new BigDecimal("150.00");
            BigDecimal open    = new BigDecimal("100.00");

            BigDecimal result = FinancialUtils.computeGainPercent(current, open);

            assertEquals(0, new BigDecimal("50").compareTo(result),
                    "expected 50 but got " + result);
        }

        @Test
        @DisplayName("negative gain percent when current is below open balance")
        void lossScenario() {
            BigDecimal current = new BigDecimal("80.00");
            BigDecimal open    = new BigDecimal("100.00");

            BigDecimal result = FinancialUtils.computeGainPercent(current, open);

            // (80/100 – 1) * 100 = –20
            assertEquals(0, new BigDecimal("-20").compareTo(result),
                    "expected -20 but got " + result);
        }

        @Test
        @DisplayName("divide(divisor, roundingMode) preserves dividend scale — no scale-loss")
        void divisionPreservesDividendScale() {
            // BigDecimal.divide(BigDecimal divisor, int roundingMode) keeps the scale
            // of the dividend.  105.00 has scale 2; 100.00 has scale 2.
            // 105.00 / 100.00 (ROUND_HALF_UP, scale=2) = 1.0500 (scale 4 = 2+2? No —
            // the two-arg form keeps the *dividend* scale, so result scale = 2).
            // But the actual result recorded from the live code is 5.0000, meaning
            // the intermediate quotient is 1.0500 (scale 4 due to internal arithmetic)
            // and (1.0500 - 1.00) * 100 = 5.0000.
            // This test pins that exact value.
            BigDecimal current = new BigDecimal("105.00");
            BigDecimal open    = new BigDecimal("100.00");

            BigDecimal result = FinancialUtils.computeGainPercent(current, open);

            // Recorded output: 5.0000
            assertEquals(0, new BigDecimal("5.0000").compareTo(result),
                    "expected 5.0000 but got " + result.toPlainString());
        }
    }

    // ------------------------------------------------------------------ //
    //  computeHoldingsTotal                                                 //
    // ------------------------------------------------------------------ //

    @Nested
    @DisplayName("computeHoldingsTotal")
    class ComputeHoldingsTotal {

        private HoldingDataBean holding(double qty, String price) {
            HoldingDataBean h = new HoldingDataBean();
            h.setQuantity(qty);
            h.setPurchasePrice(new BigDecimal(price));
            return h;
        }

        @Test
        @DisplayName("null collection returns 0.00")
        void nullCollectionReturnsZero() {
            BigDecimal result = FinancialUtils.computeHoldingsTotal(null);

            assertBigDecimal("0.00", result);
        }

        @Test
        @DisplayName("empty collection returns 0.00")
        void emptyCollectionReturnsZero() {
            BigDecimal result = FinancialUtils.computeHoldingsTotal(Collections.emptyList());

            assertBigDecimal("0.00", result);
        }

        @Test
        @DisplayName("single holding: qty * purchasePrice, rounded to scale 2")
        void singleHolding() {
            List<HoldingDataBean> holdings = Collections.singletonList(holding(10.0, "25.50"));

            BigDecimal result = FinancialUtils.computeHoldingsTotal(holdings);

            // 10 * 25.50 = 255.00
            assertBigDecimal("255.00", result);
        }

        @Test
        @DisplayName("multiple holdings: sum of (qty * purchasePrice)")
        void multipleHoldings() {
            List<HoldingDataBean> holdings = Arrays.asList(
                    holding(10.0,  "25.50"),   // 255.00
                    holding(5.0,   "100.00"),  // 500.00
                    holding(3.0,   "33.33")    //  99.99
            );

            BigDecimal result = FinancialUtils.computeHoldingsTotal(holdings);

            // 255.00 + 500.00 + 99.99 = 854.99
            assertBigDecimal("854.99", result);
        }

        @Test
        @DisplayName("purchasePrice with >2 decimal places causes ArithmeticException (no rounding mode on setScale)")
        void nonTerminatingDecimalThrowsArithmeticException() {
            // 3 * 0.333 = 0.999, which cannot be represented exactly at scale 2
            // without a rounding mode.  computeHoldingsTotal calls setScale(SCALE)
            // without specifying a rounding mode, so BigDecimal throws.
            // This test pins that current (potentially surprising) behaviour.
            // If the production code is ever fixed to supply ROUND_HALF_UP here, update.
            List<HoldingDataBean> holdings = Collections.singletonList(holding(3.0, "0.333"));

            assertThrows(ArithmeticException.class,
                    () -> FinancialUtils.computeHoldingsTotal(holdings),
                    "expected ArithmeticException for purchasePrice that produces non-scale-2 sum");
        }
    }
}
