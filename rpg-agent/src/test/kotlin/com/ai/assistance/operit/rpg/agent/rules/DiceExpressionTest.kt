package com.ai.assistance.operit.rpg.agent.rules

import com.ai.assistance.operit.rpg.agent.contract.DiceSource
import kotlin.test.*

class DiceExpressionTest {
    private fun sequence(vararg values:Int,seen:MutableList<Int> = mutableListOf()):DiceSource { val iter=values.iterator(); return DiceSource { sides -> seen+=sides; iter.next() } }
    private fun invalid(expression:String) = assertEquals("INVALID_DICE",assertFailsWith<IllegalArgumentException> { DiceExpression.roll(expression,DiceSource { _ -> 1 }) }.message,expression)
    @Test fun rollsSequenceWithModifiers() {
        val seen=mutableListOf<Int>()
        val plus=DiceExpression.roll("3d6+2",sequence(1,4,6,seen=seen))
        assertEquals(DiceOutcome("3d6+2",listOf(1,4,6),2,13),plus)
        assertEquals(listOf(6,6,6),seen)
        assertEquals(DiceOutcome("2d10-3",listOf(5,7),-3,9),DiceExpression.roll("2d10-3",sequence(5,7)))
        assertEquals(DiceOutcome("1d20",listOf(20),0,20),DiceExpression.roll("1d20",sequence(20)))
    }
    @Test fun constantsNeedNoDice() {
        assertEquals(DiceOutcome("7",emptyList(),7,7),DiceExpression.roll("7",DiceSource { _ -> error("unused") }))
        assertEquals(DiceOutcome("-50",emptyList(),-50,-50),DiceExpression.roll("-50",DiceSource { _ -> error("unused") }))
        assertEquals(50,DiceExpression.roll("50",DiceSource { _ -> error("unused") }).total)
    }
    @Test fun boundariesAccepted() {
        listOf(4,6,8,10,12,20,100).forEach { s -> DiceExpression.roll("1d$s",DiceSource { _ -> 1 }) }
        assertEquals(20,DiceExpression.roll("20d4+50",DiceSource { _ -> 1 }).faces.size)
        assertEquals(-48,DiceExpression.roll("1d4-50",DiceSource { _ -> 2 }).total)
    }
    @Test fun invalidExpressionsRejected() {
        listOf("","d6","0d6","21d6","1d3","1d0","1d6+51","1d6-51","51","-51","1d6+","1d6*2","1d6+1d4","+5","1d6 ","1D6","abc","1d6+-1","99999999999d6","1d6+99999999999").forEach(::invalid)
    }
    @Test fun faceOutsideRangeRejected() {
        assertFailsWith<IllegalArgumentException> { DiceExpression.roll("1d6",DiceSource { _ -> 7 }) }
    }
}
