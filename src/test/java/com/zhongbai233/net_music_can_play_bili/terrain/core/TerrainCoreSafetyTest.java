package com.zhongbai233.net_music_can_play_bili.terrain.core;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerrainCoreSafetyTest {
    @Test
    void samplingMeterStopsAtEveryHardLimit() {
        AtomicLong clock = new AtomicLong(100L);
        TerrainSamplingBudget.Meter meter = new TerrainSamplingBudget(2, 1, 50L).start(clock::get);
        assertTrue(meter.tryAcquireSection());
        assertFalse(meter.tryAcquireSection());
        assertTrue(meter.tryAcquireCell());
        assertTrue(meter.tryAcquireCell());
        assertFalse(meter.tryAcquireCell());

        TerrainSamplingBudget.Meter timed = new TerrainSamplingBudget(100, 10, 50L).start(clock::get);
        clock.addAndGet(50L);
        assertFalse(timed.tryAcquireCell());
        assertTrue(timed.exhausted());
    }

    @Test
    void dirtyTrackerInvalidatesBoundaryNeighborsAtNegativeCoordinates() {
        TerrainDirtyTracker tracker = new TerrainDirtyTracker(16);
        tracker.markBlockAndBoundaryNeighbors(-16, 31, 0);
        var dirty = tracker.drain(16);
        assertTrue(dirty.contains(new TerrainSectionKey(-1, 1, 0)));
        assertTrue(dirty.contains(new TerrainSectionKey(-2, 1, 0)));
        assertTrue(dirty.contains(new TerrainSectionKey(-1, 2, 0)));
        assertTrue(dirty.contains(new TerrainSectionKey(-1, 1, -1)));
        assertEquals(4, dirty.size());

        TerrainDirtyTracker bounded = new TerrainDirtyTracker(2);
        bounded.markSection(new TerrainSectionKey(0, 0, 0));
        bounded.markSection(new TerrainSectionKey(1, 0, 0));
        bounded.markSection(new TerrainSectionKey(2, 0, 0));
        assertEquals(2, bounded.size());
        assertFalse(bounded.drain(2).contains(new TerrainSectionKey(0, 0, 0)));
    }

    @Test
    void weightedCacheNeverExceedsByteBudgetAndUsesLruOrder() {
        WeightedLruCache<String, byte[]> cache = new WeightedLruCache<>(10L, bytes -> bytes.length);
        assertTrue(cache.put("a", new byte[4]));
        assertTrue(cache.put("b", new byte[4]));
        cache.get("a");
        assertTrue(cache.put("c", new byte[4]));
        assertTrue(cache.get("a").isPresent());
        assertTrue(cache.get("b").isEmpty());
        assertTrue(cache.get("c").isPresent());
        assertEquals(8L, cache.totalWeight());

        assertFalse(cache.put("huge", new byte[11]));
        assertEquals(8L, cache.totalWeight());
        assertTrue(cache.put("a", new byte[11]) == false);
        assertEquals(4, cache.get("a").orElseThrow().length);
    }

    @Test
    void boundsUseInclusiveLongVolumeAndSectionIntersection() {
        TerrainBounds bounds = new TerrainBounds(-64, -32, -64, 63, 31, 63);
        assertEquals(1_048_576L, bounds.volume());
        assertTrue(bounds.intersects(new TerrainSectionKey(-4, -2, -4)));
        assertTrue(bounds.intersects(new TerrainSectionKey(3, 1, 3)));
        assertFalse(bounds.intersects(new TerrainSectionKey(4, 1, 3)));
        assertEquals(8_589_934_592L,
            new TerrainBounds(Integer.MIN_VALUE, 0, 0, Integer.MAX_VALUE, 1, 0).volume());
        org.junit.jupiter.api.Assertions.assertThrows(ArithmeticException.class,
            () -> new TerrainBounds(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE,
                Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE).volume());
    }

    @Test
    void coverageCursorIsBudgetedNearestFirstAndDoesNotPreallocateRange() {
        TerrainBounds bounds = new TerrainBounds(-4096, -64, -4096, 4096, 319, 4096);
        TerrainCoverageCursor cursor = new TerrainCoverageCursor(bounds, new TerrainSectionKey(0, 4, 0));

        var first = cursor.next(7);
        assertEquals(7, first.size());
        assertTrue(first.stream().allMatch(key -> key.x() == 0 && key.z() == 0));
        assertEquals(new TerrainSectionKey(0, -4, 0), first.get(0));
        assertFalse(cursor.exhausted());

        TerrainCoverageCursor small = new TerrainCoverageCursor(
            new TerrainBounds(-16, 0, -16, 15, 31, 15), new TerrainSectionKey(0, 0, 0));
        var all = new java.util.ArrayList<TerrainSectionKey>();
        while (!small.exhausted()) {
            all.addAll(small.next(3));
        }
        assertEquals(8, all.size());
        assertEquals(all.size(), new HashSet<>(all).size());
    }

    @Test
    void sectionCaptureResumesAcrossTicksAndPublishesOnlyWhenComplete() {
        TerrainSectionCaptureJob job = new TerrainSectionCaptureJob(new TerrainSectionKey(1, -1, 2), 7L,
                (x, y, z) -> TerrainCellSample.air());
        int ticks = 0;
        while (!job.done()) {
            var result = job.step(new TerrainSamplingBudget(512, 1, Long.MAX_VALUE).start());
            assertTrue(result.sampledCells() <= 512);
            if (!result.completed()) {
                assertTrue(job.completedSnapshot().isEmpty());
            }
            ticks++;
        }
        assertEquals(8, ticks);
        TerrainSectionSnapshot snapshot = job.completedSnapshot().orElseThrow();
        assertEquals(7L, snapshot.generation());
        assertEquals(TerrainCellSample.RenderCategory.AIR,
                snapshot.cell(15, 15, 15).renderCategory());
    }
}
