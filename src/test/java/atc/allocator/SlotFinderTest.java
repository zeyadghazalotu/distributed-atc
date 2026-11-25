package atc.allocator;

import org.junit.jupiter.api.Test;

import java.util.List;

import static atc.allocator.TestFixtures.SEC;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SlotFinderTest {
    private static final long SLOT = 60 * SEC;
    private static final long BUF = 30 * SEC;

    /** Padded busy interval of a slot starting at {@code start}. */
    private static Interval slotAt(long start) {
        return new Interval(start - BUF, start + SLOT + BUF);
    }

    @Test
    void emptyRunwayStartsNow() {
        assertEquals(0, SlotFinder.earliestStart(List.of(), 0, SLOT, BUF));
    }

    @Test
    void nextSlotLeavesBothBuffersBetweenSlots() {
        // Busy slot [0,60) padded [-30,90): our leading buffer may start at 90 -> start 120.
        assertEquals(120 * SEC, SlotFinder.earliestStart(List.of(slotAt(0)), 0, SLOT, BUF));
    }

    @Test
    void fitsIntoGapBetweenSlotsWhenLargeEnough() {
        // Gap between padded [-30,90) and [970,1090) is wide enough for [90,210).
        assertEquals(120 * SEC, SlotFinder.earliestStart(List.of(slotAt(1000 * SEC), slotAt(0)), 0, SLOT, BUF));
    }

    @Test
    void skipsGapThatIsTooSmall() {
        // Next slot at 200 (padded [170,290)) leaves no room after the first; go after it.
        assertEquals(320 * SEC, SlotFinder.earliestStart(List.of(slotAt(0), slotAt(200 * SEC)), 0, SLOT, BUF));
    }

    @Test
    void exactlyTouchingGapIsAccepted() {
        // Second busy slot padded starts at exactly 210 = end of a slot at 120 padded.
        assertEquals(120 * SEC, SlotFinder.earliestStart(List.of(slotAt(0), slotAt(240 * SEC)), 0, SLOT, BUF));
    }

    @Test
    void neverStartsBeforeNotBefore() {
        assertEquals(500 * SEC, SlotFinder.earliestStart(List.of(slotAt(0)), 500 * SEC, SLOT, BUF));
    }
}
