package me.cortex.voxy.client.core.rendering.section.backend.mdic;


import me.cortex.voxy.client.RenderStatistics;
import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.gl.Capabilities;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.GlVertexArray;
import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.gl.shader.ShaderLoader;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.model.ModelStore;
import me.cortex.voxy.client.core.rendering.section.backend.AbstractSectionRenderer;
import me.cortex.voxy.client.core.rendering.section.geometry.BasicSectionGeometryData;
import me.cortex.voxy.client.core.rendering.util.DownloadStream;
import me.cortex.voxy.client.core.rendering.util.LightMapHelper;
import me.cortex.voxy.client.core.rendering.util.SharedIndexBuffer;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.client.core.util.GPUTiming;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.WorldEngine;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

import java.util.List;

import static org.lwjgl.opengl.ARBIndirectParameters.GL_PARAMETER_BUFFER_ARB;
import static org.lwjgl.opengl.ARBIndirectParameters.glMultiDrawElementsIndirectCountARB;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.GL_ELEMENT_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15.glBindBuffer;
import static org.lwjgl.opengl.GL30.glBindBufferBase;
import static org.lwjgl.opengl.GL30.glBindVertexArray;
import static org.lwjgl.opengl.GL31.GL_UNIFORM_BUFFER;
import static org.lwjgl.opengl.GL33.glBindSampler;
import static org.lwjgl.opengl.GL40C.GL_DRAW_INDIRECT_BUFFER;
import static org.lwjgl.opengl.GL42.GL_BUFFER_UPDATE_BARRIER_BIT;
import static org.lwjgl.opengl.GL42.glMemoryBarrier;
import static org.lwjgl.opengl.GL43.*;
import static org.lwjgl.opengl.GL45.glBindTextureUnit;
import static org.lwjgl.opengl.NVRepresentativeFragmentTest.GL_REPRESENTATIVE_FRAGMENT_TEST_NV;

//Uses MDIC to render the sections
public class MDICSectionRenderer extends AbstractSectionRenderer<MDICViewport, BasicSectionGeometryData> {
    public static final Factory<MDICViewport, BasicSectionGeometryData> FACTORY = AbstractSectionRenderer.Factory.create(MDICSectionRenderer.class);

    public static final int OPAQUE_DRAW_COUNT = 400_000;//in draw calls
    public static final int TRANSLUCENT_DRAW_COUNT = 100_000;//in draw calls
    public static final int TEMPORAL_DRAW_COUNT = 100_000;//in draw calls
    private static final int TRANSLUCENT_OFFSET = OPAQUE_DRAW_COUNT;//in draw calls
    private static final int TEMPORAL_OFFSET = TRANSLUCENT_OFFSET+TRANSLUCENT_DRAW_COUNT;//in draw calls
    private static final int STATISTICS_BUFFER_BINDING = 8;
    private static final boolean MORPH_DEBUG = Boolean.parseBoolean(System.getProperty("voxy.debugMdicMorph", "false"));
    private static final boolean VERTEX_BOUNDS_DEBUG = Boolean.parseBoolean(System.getProperty("voxy.debugVertexBounds", "false"));
    private static final boolean COLOR_LOD_DEBUG = Boolean.parseBoolean(System.getProperty("voxy.debugColorLod", "false"));
    private static final boolean DISABLE_TEMPORAL_RENDER = Boolean.parseBoolean(System.getProperty("voxy.debugDisableTemporalRender", "false"));
    private static final boolean RENDER_DEBUG_BUFFER = MORPH_DEBUG || VERTEX_BOUNDS_DEBUG;
    private static final int RENDER_DEBUG_BUFFER_SIZE = 128;
    private static final int RENDER_DEBUG_BUFFER_BINDING = 9;
    private final Shader terrainShader;
    private final Shader translucentTerrainShader;
    private final Shader morphDebugValidateShader = MORPH_DEBUG ? Shader.make()
            .define("MDIC_MORPH_DEBUG_MAX_SECTIONS", 1 << 20)
            .define("MDIC_MORPH_DEBUG_MAX_LOD_LAYER", WorldEngine.MAX_LOD_LAYER)
            .add(ShaderType.COMPUTE, "voxy:lod/gl46/debug/mdic_morph_validate.comp")
            .compile() : null;

    private final Shader commandGenShader = Shader.make()
            .define("TRANSLUCENT_WRITE_BASE", 1024)
            .define("TEMPORAL_OFFSET", TEMPORAL_OFFSET)

            .define("TRANSLUCENT_DISTANCE_BUFFER_BINDING", 7)

            .defineIf("HAS_STATISTICS", RenderStatistics.enabled)
            .defineIf("STATISTICS_BUFFER_BINDING", RenderStatistics.enabled, STATISTICS_BUFFER_BINDING)

            .add(ShaderType.COMPUTE, "voxy:lod/gl46/cmdgen.comp")
            .compile();

    private final Shader prepShader = Shader.make()
            .add(ShaderType.COMPUTE, "voxy:lod/gl46/prep.comp")
            .compile();

    private final Shader cullShader;

    private final Shader prefixSumShader = Shader.make()
            //Use subgroup prefix sum if possible otherwise use dodgy... slow prefix sum
            .add(ShaderType.COMPUTE, Capabilities.INSTANCE.subgroup?"voxy:util/prefixsum/inital3.comp":"voxy:util/prefixsum/simple.comp")
            .define("IO_BUFFER", 0)
            .compile();

    private final Shader translucentGenShader = Shader.make()
            .add(ShaderType.COMPUTE, "voxy:lod/gl46/buildtranslucents.comp")
            .define("TRANSLUCENT_WRITE_BASE", 1024)//The size of the prefix sum array
            .define("TRANSLUCENT_DISTANCE_BUFFER_BINDING", 5)
            .define("TRANSLUCENT_OFFSET", TRANSLUCENT_OFFSET)

            .compile();

    private final GlBuffer uniform = new GlBuffer(1024).zero();//TODO move to viewport?

    //TODO: needs to be in the viewport, since it contains the compute indirect call/values
    private final GlBuffer distanceCountBuffer = new GlBuffer(1024*4+TRANSLUCENT_DRAW_COUNT*4).zero();//TODO move to viewport?

    //Statistics
    private final GlBuffer statisticsBuffer = new GlBuffer(1024).zero();
    private final GlBuffer morphDebugBuffer = RENDER_DEBUG_BUFFER ? new GlBuffer(RENDER_DEBUG_BUFFER_SIZE).zero() : null;
    private int morphDebugLogCount = 0;
    private boolean temporalDisableLogged = false;

    private final AbstractRenderPipeline pipeline;
    public MDICSectionRenderer(AbstractRenderPipeline pipeline, ModelStore modelStore, BasicSectionGeometryData geometryData) {
        super(modelStore, geometryData);
        this.pipeline = pipeline;
        //The pipeline can be used to transform the renderer in abstract ways

        String vertex = ShaderLoader.parse("voxy:lod/gl46/quads3.vert");
        String taa = pipeline.taaFunction("taaShift");
        if (taa != null) {
            vertex += "\n"+taa;//inject it at the end
        }
        var builder = Shader.make()
                .defineIf("TAA_PATCH", taa != null)
                .defineIf("DEBUG_RENDER", COLOR_LOD_DEBUG)
                .defineIf("VOXY_DEBUG_COLOR_LOD", COLOR_LOD_DEBUG)
                .defineIf("VOXY_MDIC_MORPH_DEBUG", MORPH_DEBUG)
                .defineIf("VOXY_VERTEX_BOUNDS_DEBUG", VERTEX_BOUNDS_DEBUG)
                .defineIf("VOXY_RENDER_DEBUG_BUFFER_BINDING", RENDER_DEBUG_BUFFER, RENDER_DEBUG_BUFFER_BINDING)
                .defineIf("MDIC_MORPH_DEBUG_MAX_SECTIONS", RENDER_DEBUG_BUFFER, geometryData.getMaxSectionCount())

                //.defineIf("USE_NV_JANK", Capabilities.INSTANCE.isNvidia)//TODO: fix use capability to try compile the jank thing to see if it can be and use that

                //.defineIf("USE_NV_BARRY", Capabilities.INSTANCE.nvBarryCoords)

                .addSource(ShaderType.VERTEX, vertex);

        //Apply per face tinting
        addDirectionalFaceTint(builder, Minecraft.getInstance().level);

        String frag = ShaderLoader.parse("voxy:lod/gl46/quads.frag");

        String opaqueFrag = pipeline.patchOpaqueShader(this, frag);
        opaqueFrag = opaqueFrag==null?frag:opaqueFrag;

        //TODO: find a more robust/nicer way todo this
        this.terrainShader = tryCompilePatchedOrNormal(builder, opaqueFrag, frag);

        String translucentFrag = pipeline.patchTranslucentShader(this, frag);
        translucentFrag = translucentFrag==null?frag:translucentFrag;

        this.translucentTerrainShader = tryCompilePatchedOrNormal(builder.define("TRANSLUCENT"), translucentFrag, frag);

        if (this.pipeline.hasTAA()) {
            this.cullShader = Shader.make()
                    .addSource(ShaderType.VERTEX, ShaderLoader.parse("voxy:lod/gl46/cull/raster.vert")+"\n\n\n\n"+pipeline.taaFunction("getTAA"))
                    .define("TAA")
                    .add(ShaderType.FRAGMENT, "voxy:lod/gl46/cull/raster.frag")
                    .compile();
        } else {
            this.cullShader = Shader.make()
                    .add(ShaderType.VERTEX, "voxy:lod/gl46/cull/raster.vert")
                    .add(ShaderType.FRAGMENT, "voxy:lod/gl46/cull/raster.frag")
                    .compile();
        }
    }

    private void uploadUniformBuffer(MDICViewport viewport) {
        long ptr = UploadStream.INSTANCE.upload(this.uniform, 0, 1024);
        
        var mat = new Matrix4f(viewport.MVP);
        mat.translate(-viewport.innerTranslation.x, -viewport.innerTranslation.y, -viewport.innerTranslation.z);
        mat.getToAddress(ptr); ptr += 4*4*4;

        viewport.section.getToAddress(ptr); ptr += 4*3;

        if (viewport.frameId<0) {
            Logger.error("Frame ID negative, this will cause things to break, wrapping around");
            viewport.frameId &= 0x7fffffff;
        }
        MemoryUtil.memPutInt(ptr, viewport.frameId&0x7fffffff); ptr += 4;
        viewport.innerTranslation.getToAddress(ptr); ptr += 4*3;

        UploadStream.INSTANCE.commit();
    }


    private void bindRenderingBuffers(MDICViewport viewport) {
        glBindBufferBase(GL_UNIFORM_BUFFER, 0, this.uniform.id);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, this.geometryManager.getGeometryBuffer().id);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, this.geometryManager.getMetadataBuffer().id);
        this.modelStore.bind(3, 4, 0);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 5, viewport.positionScratchBuffer.id);
        if (RENDER_DEBUG_BUFFER) {
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 6, viewport.indirectLookupBuffer.id);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, RENDER_DEBUG_BUFFER_BINDING, this.morphDebugBuffer.id);
        }
        LightMapHelper.bind(1);
        glBindTextureUnit(2, viewport.depthBoundingBuffer.getDepthTex().id);

        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, SharedIndexBuffer.INSTANCE.id());
        glBindBuffer(GL_DRAW_INDIRECT_BUFFER, viewport.drawCallBuffer.id);
        glBindBuffer(GL_PARAMETER_BUFFER_ARB, viewport.drawCountCallBuffer.id);
    }

    private void renderTerrain(MDICViewport viewport, long indirectOffset, long drawCountOffset, int maxDrawCount, int debugPass) {
        //RenderLayer.getCutoutMipped().startDrawing();


        glDisable(GL_CULL_FACE);
        glDisable(GL_BLEND);
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LEQUAL);
        this.terrainShader.bind();
        glBindVertexArray(GlVertexArray.STATIC_VAO);//Needs to be before binding
        this.pipeline.setupAndBindOpaque(viewport);
        this.bindRenderingBuffers(viewport);

        glMemoryBarrier(GL_COMMAND_BARRIER_BIT|GL_SHADER_STORAGE_BARRIER_BIT);//Barrier everything is needed
        this.clearMorphDebugBuffer();
        glProvokingVertex(GL_FIRST_VERTEX_CONVENTION);

        if (VoxyClient.getOcclusionDebugState()==3) {
            glPolygonMode(GL_FRONT_AND_BACK, GL_LINE);
        }
        glMultiDrawElementsIndirectCountARB(GL_TRIANGLES, GL_UNSIGNED_SHORT, indirectOffset, drawCountOffset, maxDrawCount, 0);
        this.downloadMorphDebugResult("vertex", debugPass);
        if (VoxyClient.getOcclusionDebugState()==3) {
            glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);
        }

        glEnable(GL_CULL_FACE);
        glBindVertexArray(0);
        glBindSampler(0, 0);
        glBindTextureUnit(0, 0);
        glBindSampler(1, 0);
        glBindTextureUnit(1, 0);

        //RenderLayer.getCutoutMipped().endDrawing();
    }

    @Override
    public void renderOpaque(MDICViewport viewport) {
        if (this.geometryManager.getSectionCount() == 0) return;

        this.uploadUniformBuffer(viewport);

        this.renderTerrain(viewport, 0, 4*3, Math.min((int)(this.geometryManager.getSectionCount()*4.4+128), OPAQUE_DRAW_COUNT), 1);
    }

    @Override
    public void renderTranslucent(MDICViewport viewport) {
        if (this.geometryManager.getSectionCount() == 0) return;

        glEnable(GL_BLEND);
        glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);

        glDisable(GL_CULL_FACE);
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LEQUAL);
        this.translucentTerrainShader.bind();
        glBindVertexArray(GlVertexArray.STATIC_VAO);//Needs to be before binding
        this.pipeline.setupAndBindTranslucent(viewport);
        this.bindRenderingBuffers(viewport);

        glMemoryBarrier(GL_COMMAND_BARRIER_BIT|GL_SHADER_STORAGE_BARRIER_BIT);//Barrier everything is needed
        this.clearMorphDebugBuffer();
        glProvokingVertex(GL_FIRST_VERTEX_CONVENTION);
        glMultiDrawElementsIndirectCountARB(GL_TRIANGLES, GL_UNSIGNED_SHORT, TRANSLUCENT_OFFSET*5*4, 4*4, Math.min(this.geometryManager.getSectionCount(), TRANSLUCENT_DRAW_COUNT), 0);
        this.downloadMorphDebugResult("vertex", 2);

        glEnable(GL_CULL_FACE);
        glBindVertexArray(0);
        glBindSampler(0, 0);
        glBindTextureUnit(0, 0);
        glBindSampler(1, 0);
        glBindTextureUnit(1, 0);

        glDisable(GL_BLEND);
    }

    @Override
    public void buildDrawCalls(MDICViewport viewport) {
        if (this.geometryManager.getSectionCount() == 0) return;
        this.uploadUniformBuffer(viewport);
        //Can do a sneeky trick, since the sectionRenderList is a list to things to render, it invokes the culler
        // which only marks visible sections


        {//Dispatch prep
            this.prepShader.bind();
            glBindBufferBase(GL_UNIFORM_BUFFER, 0, this.uniform.id);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, viewport.drawCountCallBuffer.id);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, viewport.getRenderList().id);
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
            glDispatchCompute(1,1,1);
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
        }

        GPUTiming.INSTANCE.marker("OT");
        {//Test occlusion
            this.cullShader.bind();
            if (this.pipeline.hasTAA()) this.pipeline.bindUniforms();//Used for shader TAA
            if (Capabilities.INSTANCE.repFragTest) {
                glEnable(GL_REPRESENTATIVE_FRAGMENT_TEST_NV);
            }
            glBindVertexArray(GlVertexArray.STATIC_VAO);
            glBindBufferBase(GL_UNIFORM_BUFFER, 0, this.uniform.id);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, this.geometryManager.getMetadataBuffer().id);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, viewport.visibilityBuffer.id);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 3, viewport.indirectLookupBuffer.id);
            glBindBuffer(GL_DRAW_INDIRECT_BUFFER, viewport.drawCountCallBuffer.id);
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, SharedIndexBuffer.INSTANCE.id());
            glEnable(GL_DEPTH_TEST);
            glDepthFunc(GL_LEQUAL);
            glColorMask(false, false, false, false);
            glDepthMask(false);
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT|GL_COMMAND_BARRIER_BIT);
            glDrawElementsIndirect(GL_TRIANGLES, GL_UNSIGNED_BYTE, 6*4);
            glDepthMask(true);
            glColorMask(true, true, true, true);
            glDisable(GL_DEPTH_TEST);
            if (Capabilities.INSTANCE.repFragTest) {
                glDisable(GL_REPRESENTATIVE_FRAGMENT_TEST_NV);
            }
        }

        GPUTiming.INSTANCE.marker("CG");

        {//Generate the commands
            this.distanceCountBuffer.zeroRange(0, 1024*4);
            this.commandGenShader.bind();
            glBindBufferBase(GL_UNIFORM_BUFFER, 0, this.uniform.id);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, viewport.drawCallBuffer.id);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, viewport.drawCountCallBuffer.id);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 3, this.geometryManager.getMetadataBuffer().id);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 4, viewport.visibilityBuffer.id);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 5, viewport.indirectLookupBuffer.id);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 6, viewport.positionScratchBuffer.id);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 7, this.distanceCountBuffer.id);

            if (RenderStatistics.enabled) {
                this.statisticsBuffer.zero();
                glBindBufferBase(GL_SHADER_STORAGE_BUFFER, STATISTICS_BUFFER_BINDING, this.statisticsBuffer.id);
            }

            glBindBuffer(GL_DISPATCH_INDIRECT_BUFFER, viewport.drawCountCallBuffer.id);
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
            glDispatchComputeIndirect(0);
            glMemoryBarrier(GL_COMMAND_BARRIER_BIT|GL_SHADER_STORAGE_BARRIER_BIT);
            this.validateMorphCommandGeneration(viewport);

            if (RenderStatistics.enabled) {
                DownloadStream.INSTANCE.download(this.statisticsBuffer, down->{
                    final int LAYERS = WorldEngine.MAX_LOD_LAYER+1;
                    for (int i = 0; i < LAYERS; i++) {
                        RenderStatistics.visibleSections[i] = MemoryUtil.memGetInt(down.address+i*4L);
                    }

                    for (int i = 0; i < LAYERS; i++) {
                        RenderStatistics.quadCount[i] = MemoryUtil.memGetInt(down.address+LAYERS*4L+i*4L);
                    }
                });
            }
        }

        GPUTiming.INSTANCE.marker("TS");
        {//Do translucency sorting
            this.prefixSumShader.bind();
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, this.distanceCountBuffer.id);
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);//Am unsure if is needed
            glDispatchCompute(1,1,1);
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);

            this.translucentGenShader.bind();
            glBindBufferBase(GL_UNIFORM_BUFFER, 0, this.uniform.id);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, viewport.drawCallBuffer.id);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, viewport.drawCountCallBuffer.id);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 3, this.geometryManager.getMetadataBuffer().id);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 4, viewport.indirectLookupBuffer.id);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 5, this.distanceCountBuffer.id);

            glBindBuffer(GL_DISPATCH_INDIRECT_BUFFER, viewport.drawCountCallBuffer.id);//This isnt great but its a nice trick to bound it, even if its inefficent ;-;
            glMemoryBarrier(GL_COMMAND_BARRIER_BIT|GL_SHADER_STORAGE_BARRIER_BIT|GL_UNIFORM_BARRIER_BIT);
            glDispatchComputeIndirect(0);
            glMemoryBarrier(GL_COMMAND_BARRIER_BIT|GL_SHADER_STORAGE_BARRIER_BIT);
        }

    }

    @Override
    public void renderTemporal(MDICViewport viewport) {
        if (this.geometryManager.getSectionCount() == 0) return;
        if (DISABLE_TEMPORAL_RENDER) {
            if (!this.temporalDisableLogged) {
                Logger.warn("Voxy temporal render pass disabled by voxy.debugDisableTemporalRender");
                this.temporalDisableLogged = true;
            }
            return;
        }
        //Render temporal
        this.renderTerrain(viewport, TEMPORAL_OFFSET*5*4, 4*5, Math.min(this.geometryManager.getSectionCount(), TEMPORAL_DRAW_COUNT), 3);
    }

    @Override
    public void addDebug(List<String> lines) {
        super.addDebug(lines);
        //lines.add("SC/GS: " + this.geometryManager.getSectionCount() + "/" + (this.geometryManager.getGeometryUsed()/(1024*1024)));//section count/geometry size (MB)
    }

    @Override
    public MDICViewport createViewport() {
        return new MDICViewport(this.geometryManager.getMaxSectionCount());
    }

    @Override
    public void free() {
        this.uniform.free();
        this.distanceCountBuffer.free();
        this.translucentTerrainShader.free();
        this.terrainShader.free();
        this.commandGenShader.free();
        this.cullShader.free();
        this.prepShader.free();
        this.translucentGenShader.free();
        this.prefixSumShader.free();
        this.statisticsBuffer.free();
        if (this.morphDebugValidateShader != null) this.morphDebugValidateShader.free();
        if (this.morphDebugBuffer != null) this.morphDebugBuffer.free();
    }

    private void validateMorphCommandGeneration(MDICViewport viewport) {
        if (!MORPH_DEBUG) {
            return;
        }
        this.clearMorphDebugBuffer();
        this.morphDebugValidateShader.bind();
        glBindBufferBase(GL_UNIFORM_BUFFER, 0, this.uniform.id);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, this.geometryManager.getMetadataBuffer().id);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, viewport.indirectLookupBuffer.id);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 3, viewport.positionScratchBuffer.id);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 4, this.morphDebugBuffer.id);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 5, viewport.visibilityBuffer.id);
        glBindBuffer(GL_DISPATCH_INDIRECT_BUFFER, viewport.drawCountCallBuffer.id);
        glMemoryBarrier(GL_COMMAND_BARRIER_BIT | GL_SHADER_STORAGE_BARRIER_BIT);
        glDispatchComputeIndirect(0);
        glMemoryBarrier(GL_COMMAND_BARRIER_BIT | GL_SHADER_STORAGE_BARRIER_BIT);
        this.downloadMorphDebugResult("cmdgen", 0);
    }

    private void clearMorphDebugBuffer() {
        if (!RENDER_DEBUG_BUFFER) {
            return;
        }
        this.morphDebugBuffer.zeroRange(0, RENDER_DEBUG_BUFFER_SIZE);
        glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT | GL_SHADER_STORAGE_BARRIER_BIT);
    }

    private void downloadMorphDebugResult(String stage, int pass) {
        if (!RENDER_DEBUG_BUFFER) {
            return;
        }
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT | GL_BUFFER_UPDATE_BARRIER_BIT);
        DownloadStream.INSTANCE.download(this.morphDebugBuffer, 0, RENDER_DEBUG_BUFFER_SIZE, (ptr, size) -> this.processMorphDebugResult(stage, pass, ptr));
    }

    private void processMorphDebugResult(String stage, int pass, long ptr) {
        int count = MemoryUtil.memGetInt(ptr);
        if (count == 0) {
            return;
        }
        int code = MemoryUtil.memGetInt(ptr + 4);
        int drawId = MemoryUtil.memGetInt(ptr + 8);
        int sectionCount = MemoryUtil.memGetInt(ptr + 12);
        int sectionId = MemoryUtil.memGetInt(ptr + 16);
        int expectedX = MemoryUtil.memGetInt(ptr + 20);
        int expectedY = MemoryUtil.memGetInt(ptr + 24);
        int gotX = MemoryUtil.memGetInt(ptr + 28);
        int gotY = MemoryUtil.memGetInt(ptr + 32);
        int extra = MemoryUtil.memGetInt(ptr + 36);
        int e1 = MemoryUtil.memGetInt(ptr + 40);
        int e2 = MemoryUtil.memGetInt(ptr + 44);
        int e3 = MemoryUtil.memGetInt(ptr + 48);
        int e4 = MemoryUtil.memGetInt(ptr + 52);
        int e5 = MemoryUtil.memGetInt(ptr + 56);
        int e6 = MemoryUtil.memGetInt(ptr + 60);
        int e7 = MemoryUtil.memGetInt(ptr + 64);

        if (this.morphDebugLogCount < 64) {
            Logger.error("MDIC render debug mismatch stage=" + stage +
                    " pass=" + pass +
                    " count=" + count +
                    " code=" + code +
                    " drawId=" + drawId +
                    " sectionCount=" + sectionCount +
                    " sectionId=" + sectionId +
                    " expectedRaw=(" + expectedX + "," + expectedY + ")" +
                    " gotRaw=(" + gotX + "," + gotY + ")" +
                    " extra=" + extra +
                    " e=(" + e1 + "," + e2 + "," + e3 + "," + e4 + "," + e5 + "," + e6 + "," + e7 + ")" +
                    " floats=(" + Float.intBitsToFloat(e5) + "," + Float.intBitsToFloat(e6) + ")");
        } else if (this.morphDebugLogCount == 64) {
            Logger.warn("MDIC render debug suppressing further mismatch logs");
        }
        this.morphDebugLogCount++;
    }
}
