package com.abyssredemption.accounts

import org.junit.Assert.assertEquals
import org.junit.Test

class BalanceReconciliationTest {
    @Test
    fun recordsOnlyTheUnaccountedBalanceDrop() {
        assertEquals(15.0, BalanceReconciliation.missingExpense(100.0, -20.0, 65.0), 0.001)
        assertEquals(0.0, BalanceReconciliation.missingExpense(100.0, -20.0, 80.0), 0.001)
        assertEquals(0.0, BalanceReconciliation.missingExpense(100.0, 10.0, 120.0), 0.001)
    }
}
