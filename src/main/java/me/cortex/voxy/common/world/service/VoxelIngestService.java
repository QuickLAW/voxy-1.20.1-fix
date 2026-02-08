package me.cortex.voxy.common.world.service;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.thread.Service;
import me.cortex.voxy.common.thread.ServiceManager;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.voxelization.WorldConversionFactory;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldUpdater;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;

public class VoxelIngestService {
    private static final ConcurrentLinkedQueue<byte[]> LIGHT_DATA_POOL = new ConcurrentLinkedQueue<>();
    private static byte[] acquireLightData(byte[] source) {
        if (source == null) return null;
        byte[] data = LIGHT_DATA_POOL.poll();
        if (data == null) data = new byte[2048];
        System.arraycopy(source, 0, data, 0, 2048);
        return data;
    }
    private static void releaseLightData(byte[] data) {
        if (data != null) LIGHT_DATA_POOL.add(data);
    }

    private static final ThreadLocal<VoxelizedSection> SECTION_CACHE = ThreadLocal.withInitial(VoxelizedSection::createEmpty);
    private final Service service;
    private record IngestSection(int cx, int cy, int cz, WorldEngine world, PalettedContainer<BlockState> states, PalettedContainerRO<Holder<Biome>> biomes, boolean onlyAir, byte[] blockLight, byte[] skyLight){}
    private final ConcurrentLinkedDeque<IngestSection> ingestQueue = new ConcurrentLinkedDeque<>();

    public VoxelIngestService(ServiceManager pool) {
        this.service = pool.createServiceNoCleanup(()->this::processJob, 5000, "Ingest service");
    }

    private void processJob() {
        int processed = 0;
        while (processed < 16) {
            var task = this.ingestQueue.pollFirst();
            if (task == null) break;
            processed++;
            
            task.world.markActive();

            var vs = SECTION_CACHE.get().setPosition(task.cx, task.cy, task.cz);

            if (task.onlyAir && task.blockLight == null && task.skyLight == null) {
                WorldUpdater.insertUpdate(task.world, vs.zero());
            } else {
                VoxelizedSection csec = WorldConversionFactory.convert(
                        SECTION_CACHE.get(),
                        task.world.getMapper(),
                        task.states,
                        task.biomes,
                        task.blockLight,
                        task.skyLight
                );
                WorldConversionFactory.mipSection(csec, task.world.getMapper());
                WorldUpdater.insertUpdate(task.world, csec);
            }
            releaseLightData(task.blockLight);
            releaseLightData(task.skyLight);
        }
        if (!this.ingestQueue.isEmpty()) {
            try {
                this.service.execute();
            } catch (Exception e) {
                Logger.error("Error re-scheduling ingest job", e);
            }
        }
    }

    private static IngestSection snapshotSection(int cx, int cy, int cz, WorldEngine world, LevelChunkSection section, byte[] blockLight, byte[] skyLight) {
        boolean onlyAir = section.hasOnlyAir();
        PalettedContainer<BlockState> states = onlyAir ? null : section.getStates().copy();
        PalettedContainerRO<Holder<Biome>> biomes = section.getBiomes();
        if (biomes instanceof PalettedContainer<Holder<Biome>> container) {
            biomes = container.copy();
        } else {
            biomes = biomes.recreate();
        }
        return new IngestSection(cx, cy, cz, world, states, biomes, onlyAir, blockLight, skyLight);
    }

    private static boolean shouldIngestSection(LevelChunkSection section, int cx, int cy, int cz) {
        return true;
    }

    public boolean enqueueIngest(WorldEngine engine, LevelChunk chunk) {
        if (!this.service.isLive()) {
            return false;
        }
        if (!engine.isLive()) {
            throw new IllegalStateException("Tried inserting chunk into WorldEngine that was not alive");
        }

        engine.markActive();

        var lightingProvider = chunk.getLevel().getLightEngine();
        boolean gotLighting = false;

        int i = chunk.getMinSection() - 1;
        boolean allEmpty = true;
        for (var section : chunk.getSections()) {
            i++;
            if (section == null || !shouldIngestSection(section, chunk.getPos().x, i, chunk.getPos().z)) continue;
            allEmpty&=section.hasOnlyAir();
            //if (section.isEmpty()) continue;
            var pos = SectionPos.of(chunk.getPos(), i);
            gotLighting = true;
        }

        if (allEmpty&&!gotLighting) {
            //Special case all empty chunk columns, we need to clear it out
            boolean added = false;
        i = chunk.getMinSection() - 1;
        for (var section : chunk.getSections()) {
            i++;
            if (section == null || !shouldIngestSection(section, chunk.getPos().x, i, chunk.getPos().z)) continue;
            this.ingestQueue.add(snapshotSection(chunk.getPos().x, i, chunk.getPos().z, engine, section, null, null));
            added = true;
        }
        if (added) {
            try {
                this.service.execute();
            } catch (Exception e) {
                Logger.error("Executing had an error: assume shutting down, aborting", e);
            }
        }
    }

        /*
        if (!gotLighting) {
            return false;
        }
         */

        var blp = lightingProvider.getLayerListener(LightLayer.BLOCK);
        var slp = lightingProvider.getLayerListener(LightLayer.SKY);


        boolean added = false;
        i = chunk.getMinSection() - 1;
        for (var section : chunk.getSections()) {
            i++;
            if (section == null || !shouldIngestSection(section, chunk.getPos().x, i, chunk.getPos().z)) continue;
            var pos = SectionPos.of(chunk.getPos(), i);

            var bl = blp.getDataLayerData(pos);
            byte[] blData = acquireLightData(bl != null ? bl.getData() : null);

            var sl = slp.getDataLayerData(pos);
            byte[] slData = acquireLightData(sl != null ? sl.getData() : null);

            this.ingestQueue.add(snapshotSection(chunk.getPos().x, i, chunk.getPos().z, engine, section, blData, slData));
            added = true;
        }
        if (added) {
            try {
                this.service.execute();
            } catch (Exception e) {
                Logger.error("Executing had an error: assume shutting down, aborting", e);
            }
        }
        return true;
    }

    public int getTaskCount() {
        return this.service.numJobs();
    }

    public void shutdown() {
        this.service.shutdown();
    }

    //Utility method to ingest a chunk into the given WorldIdentifier or world
    public static boolean tryIngestChunk(WorldIdentifier worldId, LevelChunk chunk) {
        if (worldId == null) return false;
        var instance = VoxyCommon.getInstance();
        if (instance == null) return false;
        if (!instance.isIngestEnabled(worldId)) return false;
        var engine = instance.getOrCreate(worldId);
        if (engine == null) return false;
        return instance.getIngestService().enqueueIngest(engine, chunk);
    }

    //Try to automatically ingest the chunk into the correct world
    public static boolean tryAutoIngestChunk(LevelChunk chunk) {
        return tryIngestChunk(WorldIdentifier.of(chunk.getLevel()), chunk);
    }

    private boolean rawIngest0(WorldEngine engine, LevelChunkSection section, int x, int y, int z, byte[] bl, byte[] sl) {
        this.ingestQueue.add(snapshotSection(x, y, z, engine, section, bl, sl));
        try {
            this.service.execute();
            return true;
        } catch (Exception e) {
            Logger.error("Executing had an error: assume shutting down, aborting", e);
            return false;
        }
    }

    public static boolean rawIngest(WorldIdentifier id, LevelChunkSection section, int x, int y, int z, DataLayer bl, DataLayer sl) {
        if (id == null) return false;
        var engine = id.getOrCreateEngine();
        if (engine == null) return false;
        return rawIngest(engine, section, x, y, z, bl, sl);
    }

    public static boolean rawIngest(WorldEngine engine, LevelChunkSection section, int x, int y, int z, DataLayer bl, DataLayer sl) {
        if (!shouldIngestSection(section, x, y, z)) return false;
        if (engine.instanceIn == null) return false;
        if (!engine.instanceIn.isIngestEnabled(null)) return false;//TODO: dont pass in null
        byte[] blData = acquireLightData(bl != null ? bl.getData() : null);
        byte[] slData = acquireLightData(sl != null ? sl.getData() : null);
        return engine.instanceIn.getIngestService().rawIngest0(engine, section, x, y, z, blData, slData);
    }
}
