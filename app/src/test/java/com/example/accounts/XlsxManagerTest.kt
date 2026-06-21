package com.example.accounts

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class XlsxManagerTest {
    @Test
    fun exportThenImportPreservesTransactions() {
        val source = listOf(
            PaymentRecord(1, "早餐店", 12.5, "手动记录", 1_750_000_000_000, "expense", "餐饮", "豆浆"),
            PaymentRecord(2, "工资", 6800.0, "XLSX 导入", 1_750_003_600_000, "income", "工资", "六月")
        )
        val output = ByteArrayOutputStream()

        XlsxManager.export(output, source)
        val imported = XlsxManager.import(ByteArrayInputStream(output.toByteArray()))

        assertEquals(2, imported.size)
        assertEquals("早餐店", imported[0].merchant)
        assertEquals(12.5, imported[0].amount, 0.001)
        assertEquals("expense", imported[0].kind)
        assertEquals("工资", imported[1].merchant)
        assertEquals(6800.0, imported[1].amount, 0.001)
        assertEquals("income", imported[1].kind)
    }
}
