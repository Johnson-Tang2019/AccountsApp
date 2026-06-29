package com.abyssredemption.accounts

object BalanceReconciliation {
    fun missingExpense(previousBalance: Double, recordedNetChange: Double, currentBalance: Double): Double {
        val difference = previousBalance + recordedNetChange - currentBalance
        return if (difference >= 0.005) difference else 0.0
    }
}
