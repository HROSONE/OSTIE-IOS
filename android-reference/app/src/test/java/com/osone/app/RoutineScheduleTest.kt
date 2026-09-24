package com.osone.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class RoutineScheduleTest {
    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int) = Calendar.getInstance().apply {
        clear(); set(year, month - 1, day, hour, minute)
    }.timeInMillis

    @Test fun dailyRoutineRunsLaterTodayOrTomorrow() {
        val now = at(2026, 9, 24, 7, 30) // quinta-feira
        assertEquals(at(2026, 9, 24, 8, 0), RoutineSchedule.next(now, 8, 0, emptySet()))
        assertEquals(at(2026, 9, 25, 7, 0), RoutineSchedule.next(now, 7, 0, emptySet()))
        // Exatamente no horário: agenda a próxima ocorrência, não repete agora.
        assertEquals(at(2026, 9, 25, 7, 30), RoutineSchedule.next(now, 7, 30, emptySet()))
    }

    @Test fun weekdaysSkipTheWeekend() {
        val friday = at(2026, 9, 25, 9, 0)
        assertEquals(at(2026, 9, 28, 8, 0), RoutineSchedule.next(friday, 8, 0, setOf(2, 3, 4, 5, 6)))
        assertEquals(at(2026, 9, 26, 10, 0), RoutineSchedule.next(friday, 10, 0, setOf(1, 7)))
    }

    @Test fun portugueseDayNamesAreParsed() {
        assertEquals(emptySet<Int>(), RoutineSchedule.parseDays("todos"))
        assertEquals(setOf(2, 3, 4, 5, 6), RoutineSchedule.parseDays("úteis"))
        assertEquals(setOf(1, 7), RoutineSchedule.parseDays("fim_de_semana"))
        assertEquals(setOf(2, 4, 6), RoutineSchedule.parseDays("seg,qua,sex"))
        assertTrue(RoutineSchedule.daysLabel(setOf(2, 3, 4, 5, 6)) == "dias úteis")
    }
}
