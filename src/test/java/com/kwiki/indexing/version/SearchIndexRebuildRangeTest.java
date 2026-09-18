package com.kwiki.indexing.version;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SearchIndexRebuildRangeTest {

    @Test
    void cursorAndCountersAdvanceWithinTheFrozenRange() {
        SearchIndexRebuildRange range = new SearchIndexRebuildRange(9, "PAGE", 10, 20);

        range.recordBatch(14, 5, 0, 0, 0);
        range.recordBatch(20, 3, 0, 0, 0);
        range.setTargetResults(7, 1);

        assertThat(range.getLastSeenId()).isEqualTo(20);
        assertThat(range.baselineComplete()).isTrue();
        assertThat(range.getItemsScanned()).isEqualTo(8);
        assertThat(range.getItemsSucceeded()).isEqualTo(7);
        assertThat(range.getItemsFailed()).isEqualTo(1);
    }

    @Test
    void cursorCannotEscapeOrMoveBackwards() {
        SearchIndexRebuildRange range = new SearchIndexRebuildRange(9, "PAGE", 10, 20);
        range.recordBatch(15, 6, 0, 0, 0);

        assertThatThrownBy(() -> range.recordBatch(14, 0, 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> range.recordBatch(21, 0, 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tailCursorStartsAtBaselineEndAndResumesWithinCapturedUpperBound() {
        SearchIndexRebuildRange range = new SearchIndexRebuildRange(9, "PAGE", 10, 20);
        range.captureTailUpperBound(35);

        range.recordTailBatch(27, 7);
        range.recordTailBatch(35, 8);

        assertThat(range.getTailLastSeenId()).isEqualTo(35);
        assertThat(range.getTailMaxId()).isEqualTo(35);
        assertThat(range.tailComplete()).isTrue();
        assertThatThrownBy(() -> range.recordTailBatch(34, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
