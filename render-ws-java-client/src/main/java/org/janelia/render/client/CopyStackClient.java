package org.janelia.render.client;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.io.IOException;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import mpicbg.trakem2.transform.AffineModel2D;

import org.janelia.alignment.spec.LayoutData;
import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.SectionData;
import org.janelia.alignment.spec.TileBounds;
import org.janelia.alignment.spec.TileBoundsRTree;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.TransformSpec;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.spec.stack.StackMetaData.StackState;
import org.janelia.alignment.util.ProcessTimer;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.LayerBoundsParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java client for copying tiles from one stack to another.
 *
 * @author Eric Trautman
 */
public class CopyStackClient {

    public static class Parameters extends CommandLineParameters {

        @ParametersDelegate
        public RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();

        @Parameter(
                names = "--fromStack",
                description = "Name of source stack",
                required = true)
        public String fromStack;

        @Parameter(
                names = "--toOwner",
                description = "Name of target stack owner (default is same as source stack owner)",
                required = false)
        private String toOwner;

        @Parameter(
                names = "--toProject",
                description = "Name of target stack project (default is same as source stack project)",
                required = false)
        private String toProject;

        @Parameter(
                names = "--toStack",
                description = "Name of target stack",
                required = true)
        private String toStack;

        @Parameter(
                names = "--z",
                description = "Z value of section to be copied",
                required = true)
        public List<Double> zValues;

        @ParametersDelegate
        public LayerBoundsParameters layerBounds = new LayerBoundsParameters();

        @Parameter(
                names = "--keepExisting",
                description = "Keep any existing target stack tiles with the specified z (default is to remove them)",
                required = false,
                arity = 0)
        public boolean keepExisting = false;

        @Parameter(
                names = "--completeToStackAfterCopy",
                description = "Complete the to stack after copying all layers",
                required = false,
                arity = 0)
        public boolean completeToStackAfterCopy = false;

        @Parameter(
                names = "--replaceLastTransformWithStage",
                description = "Replace the last transform in each tile space with a 'stage identity' transform",
                required = false,
                arity = 0)
        public boolean replaceLastTransformWithStage = false;

        @Parameter(
                names = "--splitMergedSections",
                description = "Reset z values for tiles so that original sections are separated",
                required = false,
                arity = 0)
        public boolean splitMergedSections = false;

        public String getToOwner() {
            if (toOwner == null) {
                toOwner = renderWeb.owner;
            }
            return toOwner;
        }

        public String getToProject() {
            if (toProject == null) {
                toProject = renderWeb.project;
            }
            return toProject;
        }

    }

    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);
                parameters.layerBounds.validate();

                LOG.info("runClient: entry, parameters={}", parameters);

                final CopyStackClient client = new CopyStackClient(parameters);

                client.setUpDerivedStack();

                for (final Double z : parameters.zValues) {
                    client.copyLayer(z);
                }

                if (parameters.completeToStackAfterCopy) {
                    client.completeToStack();
                }
            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;
    private final RenderDataClient fromDataClient;
    private final RenderDataClient toDataClient;
    private final Map<String, Integer> sectionIdToZMap;

    public CopyStackClient(final Parameters parameters) throws Exception {

        this.parameters = parameters;

        this.fromDataClient = parameters.renderWeb.getDataClient();

        this.toDataClient = new RenderDataClient(parameters.renderWeb.baseDataUrl,
                                                 parameters.getToOwner(),
                                                 parameters.getToProject());

        if (parameters.splitMergedSections) {
            this.sectionIdToZMap = getSectionIdToIntegralZMap();
        } else {
            this.sectionIdToZMap = null;
        }
    }

    public void setUpDerivedStack() throws Exception {
        final StackMetaData fromStackMetaData = fromDataClient.getStackMetaData(parameters.fromStack);
        toDataClient.setupDerivedStack(fromStackMetaData, parameters.toStack);
    }

    public void completeToStack() throws Exception {
        toDataClient.setStackState(parameters.toStack, StackState.COMPLETE);
    }

    public void copyLayer(final Double z) throws Exception {

        final ResolvedTileSpecCollection sourceCollection =
                fromDataClient.getResolvedTiles(parameters.fromStack, z);

        if (parameters.layerBounds.minX != null) {
            final Set<String> tileIdsToKeep = getIdsForTilesInBox(z);
            sourceCollection.removeDifferentTileSpecs(tileIdsToKeep);
        }

        if (parameters.replaceLastTransformWithStage) {
            replaceLastTransformWithStage(sourceCollection);
        }

        final Set<Double> toStackZValues = new LinkedHashSet<>();
        if (parameters.splitMergedSections) {
            for (final TileSpec tileSpec : sourceCollection.getTileSpecs()) {
                final Double zValue = new Double(getContrivedZ(tileSpec.getLayout().getSectionId(), tileSpec.getZ()));
                toStackZValues.add(zValue);
                tileSpec.setZ(zValue);
            }

            LOG.info("copyLayer: updated z values for {} tiles",
                     sourceCollection.getTileCount());
        } else {
            toStackZValues.add(z);
        }

        sourceCollection.removeUnreferencedTransforms();

        if (! parameters.keepExisting) {
            for (final Double zValue : toStackZValues) {
                toDataClient.deleteStack(parameters.toStack, zValue);
            }
        }

        toDataClient.saveResolvedTiles(sourceCollection, parameters.toStack, null);
    }

    private Set<String> getIdsForTilesInBox(final Double z) throws Exception {

        final List<TileBounds> tileBoundsList = fromDataClient.getTileBounds(parameters.fromStack, z);
        final TileBoundsRTree tree = new TileBoundsRTree(z, tileBoundsList);

        final Set<String> tileIdsToKeep = new HashSet<>(tileBoundsList.size());

        tileIdsToKeep.addAll(
                tree.findTilesInBox(parameters.layerBounds.minX,
                                    parameters.layerBounds.minY,
                                    parameters.layerBounds.maxX,
                                    parameters.layerBounds.maxY).stream().map(
                        TileBounds::getTileId).collect(Collectors.toList()));

        if (tileBoundsList.size() > tileIdsToKeep.size()) {
            LOG.info("getIdsForTilesInBox: removed {} tiles outside of bounding box",
                     (tileBoundsList.size() - tileIdsToKeep.size()));
        }

        return tileIdsToKeep;
    }

    private void replaceLastTransformWithStage(final ResolvedTileSpecCollection sourceCollection) {

        final ProcessTimer timer = new ProcessTimer();

        int tileSpecCount = 0;

        for (final TileSpec tileSpec : sourceCollection.getTileSpecs()) {

            final LayoutData layoutData = tileSpec.getLayout();
            final String dataString = "1 0 0 1 " + layoutData.getStageX() + " " + layoutData.getStageY();
            final TransformSpec transformSpec = new LeafTransformSpec(AffineModel2D.class.getName(),
                                                                      dataString);

            sourceCollection.addTransformSpecToTile(tileSpec.getTileId(),
                                                    transformSpec,
                                                    true);

            tileSpecCount++;

            if (timer.hasIntervalPassed()) {
                LOG.info("replaceLastTransformWithStage: updated transforms for {} out of {} tiles",
                         tileSpecCount, sourceCollection.getTileCount());
            }
        }

        LOG.info("replaceLastTransformWithStage: exit, updated transforms for {} tiles",
                 tileSpecCount);
    }

    private Map<String, Integer> getSectionIdToIntegralZMap()
            throws IOException {

        final Comparator<SectionData> sectionComparator = (o1, o2) -> {
            int result = o1.getZ().compareTo(o2.getZ());
            if (result == 0) {
                result = o1.getSectionId().compareTo(o2.getSectionId());
            }
            return result;
        };

        final List<SectionData> orderedSectionDataList =
                fromDataClient.getStackSectionData(parameters.fromStack, null, null);

        Collections.sort(orderedSectionDataList,
                         sectionComparator);

        final Map<String, Integer> sectionIdToZMap = new HashMap<>(orderedSectionDataList.size());

        final int firstContrivedZ;
        if (orderedSectionDataList.size() > 0) {

            // highlight contrived z values by making them abnormally large and ensuring no overlap with real z values
            final Double lastZ = orderedSectionDataList.get(orderedSectionDataList.size() - 1).getZ();
            if (lastZ < 50000) {
                firstContrivedZ = 100000;
            } else {
                firstContrivedZ = lastZ.intValue() + 50000;
            }

            SectionData sectionData;
            for (int i = 0; i < orderedSectionDataList.size(); i++) {
                sectionData = orderedSectionDataList.get(i);
                sectionIdToZMap.put(getSectionWithZKey(sectionData.getSectionId(), sectionData.getZ()),
                                    i + firstContrivedZ);
            }

        } else {
            firstContrivedZ = 0;
        }

        final int lastContrivedZ = firstContrivedZ + sectionIdToZMap.size() - 1;

        LOG.info("getSectionIdToIntegralZMap: exit, mapped {} sections to z values {} - {}",
                 sectionIdToZMap.size(), firstContrivedZ, lastContrivedZ);

        return sectionIdToZMap;
    }

    private String getSectionWithZKey(final String sectionId,
                                      final Double z) {
        return sectionId + "::" + z;
    }

    private Integer getContrivedZ(final String sectionId,
                                  final Double z) {
        return sectionIdToZMap.get(getSectionWithZKey(sectionId, z));
    }

    private static final Logger LOG = LoggerFactory.getLogger(CopyStackClient.class);
}
