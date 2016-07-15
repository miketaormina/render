package org.janelia.alignment.spec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests the {@link TileBoundsRTree} class.
 *
 * @author Eric Trautman
 */
public class TileBoundsRTreeTest {

    private double z;
    private List<TileBounds> tileBoundsList;
    private TileBoundsRTree tree;

    @Before
    public void setup() throws Exception {
        z = 1.0;
        tileBoundsList = buildListForZ(z);
        tree = new TileBoundsRTree(tileBoundsList);
    }

    @Test
    public void testFindTiles()
            throws Exception {

        Set<String> expectedTileIds = new HashSet<>(Arrays.asList(getTileId(0, z),
                                                                  getTileId(1, z),
                                                                  getTileId(3, z),
                                                                  getTileId(4, z)));

        final List<TileBounds> tilesInBox = tree.findTilesInBox(5.0, 5.0, 15.0, 15.0);
        validateSearchResults("four tile box search", tilesInBox, expectedTileIds);

        final List<TileBounds> tileSpecsInCircle1 = tree.findTilesInCircle(10.0, 10.0, 5.0);
        validateSearchResults("four tile circle search", tileSpecsInCircle1, expectedTileIds);

        expectedTileIds = new HashSet<>(tileBoundsList.size() * 2);
        for (final TileBounds tileSpec : tileBoundsList) {
            expectedTileIds.add(tileSpec.getTileId());
        }

        final List<TileBounds> tileSpecsInCircle2 = tree.findTilesInCircle(15.0, 15.0, 11.0);
        validateSearchResults("all tile circle search", tileSpecsInCircle2, expectedTileIds);

    }

    @Test
    public void testGetCircleNeighborTileIdPairs()
            throws Exception {

        final TileBoundsRTree treeForZ1 = new TileBoundsRTree(Arrays.asList(getTileBounds(0, z, 3, 10),
                                                                            getTileBounds(1, z, 3, 10)));
        final TileBoundsRTree treeForZ2 = new TileBoundsRTree(buildListForZ(2.0));
        final TileBoundsRTree treeForZ3 = new TileBoundsRTree(buildListForZ(3.0));
        final List<TileBoundsRTree> neighborTrees = Arrays.asList(treeForZ2, treeForZ3);

        final Set<TileIdPair> tileIdPairs = treeForZ1.getCircleNeighborTileIdPairs(neighborTrees, 1.1);

        // these are short-hand names for the pairs to clarify how many pairs are expected
        final String[] expectedPairs = {
                        "z1-0,z1-1",
                        "z1-0,z2-0", "z1-0,z2-1", "z1-0,z2-3", "z1-0,z2-4",
                        "z1-0,z3-0", "z1-0,z3-1", "z1-0,z3-3", "z1-0,z3-4",
                        "z1-1,z2-0", "z1-1,z2-1", "z1-1,z2-2", "z1-1,z2-3", "z1-1,z2-4", "z1-1,z2-5",
                        "z1-1,z3-0", "z1-1,z3-1", "z1-1,z3-2", "z1-1,z3-3", "z1-1,z3-4", "z1-1,z3-5"
        };

        Assert.assertEquals("invalid number of pairs found, pairs are " + new TreeSet<>(tileIdPairs),
                            expectedPairs.length, tileIdPairs.size());

    }

    private void validateSearchResults(final String context,
                                       final List<TileBounds> searchResults,
                                       final Set<String> expectedTileIds)
            throws Exception {

        Assert.assertEquals("invalid number of tiles returned for " + context,
                            expectedTileIds.size(), searchResults.size());

        final String tileContext = " missing from " + context + " results: " + searchResults;
        for (final TileBounds tileBounds : searchResults) {
            Assert.assertTrue("tileId " + tileBounds.getTileId() + tileContext,
                              expectedTileIds.contains(tileBounds.getTileId()));
        }

    }

    private String getTileId(final int tileIndex,
                             final double z) {
        return "tile-" + z + "-" + tileIndex;
    }

    private TileBounds getTileBounds(final int tileIndex,
                                     final double z,
                                     final int tilesPerRow,
                                     final double tileSize) {
        final int row = tileIndex / tilesPerRow;
        final int col = tileIndex % tilesPerRow;
        final Double minX = col * (tileSize - 1.0);
        final Double maxX = minX + tileSize;
        final Double minY = row * (tileSize - 1.0);
        final Double maxY = minY + tileSize;
        return new TileBounds(getTileId(tileIndex, z), minX, minY, z, maxX, maxY, z) ;
    }

    private List<TileBounds> buildListForZ(final double z) {

        // Setup 3x3 grid of overlapping tile specs.
        // Each tile is 10x10 and overlaps 1 pixel with adjacent tiles.
        //
        //        0-10    9-19    18-28
        //  0-10  tile-0  tile-1  tile-2
        //  9-19  tile-3  tile-4  tile-5
        // 18-28  tile-6  tile-7  tile-8

        final List<TileBounds> list = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            list.add(getTileBounds(i, z, 3, 10));
        }

        return list;
    }
}