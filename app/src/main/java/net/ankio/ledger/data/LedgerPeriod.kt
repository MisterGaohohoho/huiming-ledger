package net.ankio.ledger.data

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** One time window shared by the ledger, statistics and drill-down queries. End is exclusive. */
data class LedgerPeriod(val start:Long,val end:Long,val previous:Long,val mode:String) {
    companion object {
        fun millis(date:LocalDate)=date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        fun parse(value:String):LedgerPeriod {
            val start:LocalDate;val end:LocalDate;val previous:LocalDate;val mode:String
            when {
                value=="all" -> {start=LocalDate.of(1970,1,1);end=LocalDate.of(2101,1,1);previous=start;mode="all"}
                Regex("(?:20[0-9]{2}|2100)").matches(value) -> {
                    start=LocalDate.of(value.toInt(),1,1);end=start.plusYears(1);previous=start.minusYears(1);mode="year"
                }
                Regex("(?:20[0-9]{2}|2100)-(?:0[1-9]|1[0-2])").matches(value) -> {
                    start=YearMonth.parse(value).atDay(1);end=start.plusMonths(1);previous=start.minusMonths(1);mode="month"
                }
                Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}\\.\\.[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(value) -> {
                    start=LocalDate.parse(value.substringBefore(".."));end=LocalDate.parse(value.substringAfter("..")).plusDays(1)
                    require(start.year in 1970..2100 && end<=LocalDate.of(2101,1,1) && start<end){"请选择有效的起止日期"}
                    previous=start;mode="custom"
                }
                else -> error("日期范围无效")
            }
            return LedgerPeriod(millis(start),millis(end),millis(previous),mode)
        }
    }
}
