package me.cortex.voxy.client.core;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.gl.GlFramebuffer;
import me.cortex.voxy.client.core.gl.GlTexture;
import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser;
import me.cortex.voxy.client.core.rendering.hierachical.NodeCleaner;
import me.cortex.voxy.client.core.rendering.post.FullscreenBlit;
import me.cortex.voxy.client.core.rendering.util.DepthFramebuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.util.function.BooleanSupplier;

import static org.lwjgl.opengl.ARBComputeShader.glDispatchCompute;
import static org.lwjgl.opengl.ARBShaderImageLoadStore.glBindImageTexture;
import static org.lwjgl.opengl.GL11.GL_BLEND;
import static org.lwjgl.opengl.GL11.GL_ONE;
import static org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.GL_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.glBlendFunc;
import static org.lwjgl.opengl.GL11.glDepthMask;
import static org.lwjgl.opengl.GL11.glDisable;
import static org.lwjgl.opengl.GL11.glEnable;
import static org.lwjgl.opengl.GL11C.GL_NEAREST;
import static org.lwjgl.opengl.GL11C.GL_RGBA8;
import static org.lwjgl.opengl.GL14.glBlendFuncSeparate;
import static org.lwjgl.opengl.GL15.GL_READ_WRITE;
import static org.lwjgl.opengl.GL20C.nglUniform3fv;
import static org.lwjgl.opengl.GL20C.nglUniform4fv;
import static org.lwjgl.opengl.GL20C.nglUniformMatrix4fv;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.GL43.GL_DEPTH_STENCIL_TEXTURE_MODE;
import static org.lwjgl.opengl.GL45.glGetNamedFramebufferAttachmentParameteri;
import static org.lwjgl.opengl.GL45C.glBindTextureUnit;
import static org.lwjgl.opengl.GL45C.glTextureParameterf;

public class NormalRenderPipeline extends AbstractRenderPipeline {
    private GlTexture colourTex;
    private GlTexture colourSSAOTex;
    private final GlFramebuffer fbSSAO = new GlFramebuffer();
    private final DepthFramebuffer fb = new DepthFramebuffer(GL_DEPTH24_STENCIL8);

    private FullscreenBlit finalBlit;
    private FullscreenBlit unifiedFogBlit;
    private boolean lastAtmosphericFog;
    private boolean lastEnvironmentalFog;
    private boolean lastRenderVanillaFog;

    private final Shader ssaoCompute = Shader.make()
            .add(ShaderType.COMPUTE, "voxy:post/ssao.comp")
            .compile();

    protected NormalRenderPipeline(AsyncNodeManager nodeManager, NodeCleaner nodeCleaner, HierarchicalOcclusionTraverser traversal, BooleanSupplier frexSupplier) {
        super(nodeManager, nodeCleaner, traversal, frexSupplier);
        this.rebuildFinalBlit();
    }

    private void rebuildFinalBlit() {
        if (this.finalBlit != null) {
            this.finalBlit.delete();
        }
        if (this.unifiedFogBlit != null) {
            this.unifiedFogBlit.delete();
        }
        this.lastAtmosphericFog = VoxyConfig.CONFIG.atmosphericFog;
        this.lastEnvironmentalFog = VoxyConfig.CONFIG.environmentalFog;
        this.lastRenderVanillaFog = VoxyConfig.CONFIG.renderVanillaFog;
        
        // finalBlit now only handles LOD blitting without fog
        this.finalBlit = new FullscreenBlit("voxy:post/blit_texture_depth_cutout.frag",
                a->a.define("EMIT_COLOUR"));

        // unifiedFogBlit handles fog for both vanilla and LODs
        this.unifiedFogBlit = new FullscreenBlit("voxy:post/unified_fog.frag",
                a->a.defineIf("USE_ATMOSPHERIC_FOG", this.lastAtmosphericFog)
                    .defineIf("USE_ENV_FOG", this.lastEnvironmentalFog && this.lastRenderVanillaFog));
    }

    @Override
    protected int setup(Viewport<?> viewport, int sourceFB, int srcWidth, int srcHeight) {
        if (this.colourTex == null || this.colourTex.getHeight() != viewport.height || this.colourTex.getWidth() != viewport.width) {
            if (this.colourTex != null) {
                this.colourTex.free();
                this.colourSSAOTex.free();
            }
            this.fb.resize(viewport.width, viewport.height);

            this.colourTex = new GlTexture().store(GL_RGBA8, 1, viewport.width, viewport.height);
            this.colourSSAOTex = new GlTexture().store(GL_RGBA8, 1, viewport.width, viewport.height);

            this.fb.framebuffer.bind(GL_COLOR_ATTACHMENT0, this.colourTex).verify();
            this.fbSSAO.bind(GL_DEPTH_STENCIL_ATTACHMENT, this.fb.getDepthTex()).bind(GL_COLOR_ATTACHMENT0, this.colourSSAOTex).verify();


            glTextureParameterf(this.colourTex.id, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTextureParameterf(this.colourTex.id, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTextureParameterf(this.colourSSAOTex.id, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTextureParameterf(this.colourSSAOTex.id, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTextureParameterf(this.fb.getDepthTex().id, GL_DEPTH_STENCIL_TEXTURE_MODE, GL_DEPTH_COMPONENT);
        }

        this.initDepthStencil(sourceFB, this.fb.framebuffer.id, viewport.width, viewport.height, viewport.width, viewport.height);

        return this.fb.getDepthTex().id;
    }

    @Override
    protected void postOpaquePreTranslucent(Viewport<?> viewport) {
        this.ssaoCompute.bind();
        try (var stack = MemoryStack.stackPush()) {
            long ptr = stack.nmalloc(4*4*4);
            viewport.MVP.getToAddress(ptr);
            nglUniformMatrix4fv(3, 1, false, ptr);//MVP
            viewport.MVP.invert(new Matrix4f()).getToAddress(ptr);
            nglUniformMatrix4fv(4, 1, false, ptr);//invMVP
        }


        glBindImageTexture(0, this.colourSSAOTex.id, 0, false,0, GL_READ_WRITE, GL_RGBA8);
        glBindTextureUnit(1, this.fb.getDepthTex().id);
        glBindTextureUnit(2, this.colourTex.id);

        glDispatchCompute((viewport.width+31)/32, (viewport.height+31)/32, 1);

        glBindFramebuffer(GL_FRAMEBUFFER, this.fbSSAO.id);
    }

    @Override
    protected void finish(Viewport<?> viewport, int sourceFrameBuffer, int srcWidth, int srcHeight) {
        if (this.lastAtmosphericFog != VoxyConfig.CONFIG.atmosphericFog || 
            this.lastEnvironmentalFog != VoxyConfig.CONFIG.environmentalFog ||
            this.lastRenderVanillaFog != VoxyConfig.CONFIG.renderVanillaFog) {
            this.rebuildFinalBlit();
        }

        // 1. Blit LODs into vanilla framebuffer (WITHOUT FOG)
        this.finalBlit.bind();
        glBindTextureUnit(3, this.colourSSAOTex.id);
        
        glEnable(GL_BLEND);
        glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
        AbstractRenderPipeline.transformBlitDepth(this.finalBlit, this.fb.getDepthTex().id, sourceFrameBuffer, viewport, new Matrix4f(viewport.vanillaProjection).mul(viewport.modelView));
        glDisable(GL_BLEND);

        // 2. Apply Unified Fog to the entire scene
        if (this.lastAtmosphericFog || (this.lastEnvironmentalFog && this.lastRenderVanillaFog)) {
            this.unifiedFogBlit.bind();
            
            // Get the depth texture from the source framebuffer (vanilla + LODs)
            int depthTexture = glGetNamedFramebufferAttachmentParameteri(sourceFrameBuffer, GL_DEPTH_ATTACHMENT, GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
            glBindTextureUnit(0, depthTexture);

            try (var stack = MemoryStack.stackPush()) {
                // invProjMat
                long ptr = stack.nmalloc(4*4*4);
                new Matrix4f(viewport.vanillaProjection).invert().getToAddress(ptr);
                nglUniformMatrix4fv(1, 1, false, ptr);

                if (this.lastEnvironmentalFog && this.lastRenderVanillaFog) {
                    float start = RenderSystem.getShaderFogStart();
                    float end = RenderSystem.getShaderFogEnd();
                    float invDiff = 1.0f / (end - start);
                    var params = stack.floats(end, invDiff, -start * invDiff);
                    nglUniform3fv(4, 1, MemoryUtil.memAddress(params));

                    var color = RenderSystem.getShaderFogColor();
                    var colorParams = stack.floats(color[0], color[1], color[2]);
                    nglUniform3fv(5, 1, MemoryUtil.memAddress(colorParams));
                }

                if (this.lastAtmosphericFog) {
                    var params = stack.floats(0.005f, 1.2f, 32.0f, 0.0f);
                    nglUniform4fv(6, 1, MemoryUtil.memAddress(params));

                    ClientLevel level = Minecraft.getInstance().level;
                    if (level != null) {
                        float partialTicks = Minecraft.getInstance().getFrameTime();
                        float celestialAngle = level.getSunAngle(partialTicks);
                        float sunY = Mth.cos(celestialAngle * ((float)Math.PI * 2F));
                        float sunZ = Mth.sin(celestialAngle * ((float)Math.PI * 2F));
                        float dayFactor = Mth.clamp(sunY * 0.5f + 0.5f, 0.0f, 1.0f);

                        float sunColorR = Mth.lerp(dayFactor, 1.0f, 1.0f);
                        float sunColorG = Mth.lerp(dayFactor, 0.6f, 0.95f);
                        float sunColorB = Mth.lerp(dayFactor, 0.3f, 0.85f);

                        float ambientR = Mth.lerp(dayFactor, 0.02f, 0.5f);
                        float ambientG = Mth.lerp(dayFactor, 0.03f, 0.6f);
                        float ambientB = Mth.lerp(dayFactor, 0.05f, 0.8f);

                        var sunColorParams = stack.floats(sunColorR, sunColorG, sunColorB);
                        nglUniform3fv(7, 1, MemoryUtil.memAddress(sunColorParams));

                        var ambientColorParams = stack.floats(ambientR, ambientG, ambientB);
                        nglUniform3fv(8, 1, MemoryUtil.memAddress(ambientColorParams));

                        Vector3f sunDirView = new Matrix4f(viewport.modelView).transformDirection(new Vector3f(0.0f, sunY, sunZ)).normalize();
                        var sunDirParams = stack.floats(sunDirView.x, sunDirView.y, sunDirView.z);
                        nglUniform3fv(9, 1, MemoryUtil.memAddress(sunDirParams));
                    }
                }
            }

            // Blend the fog over the existing scene
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
            glDepthMask(false);
            glDisable(GL_DEPTH_TEST);
            
            this.unifiedFogBlit.blit();
            
            glDepthMask(true);
            glDisable(GL_BLEND);
        }
    }

    @Override
    public void setupAndBindOpaque(Viewport<?> viewport) {
        this.fb.bind();
    }

    @Override
    public void setupAndBindTranslucent(Viewport<?> viewport) {
        glBindFramebuffer(GL_FRAMEBUFFER, this.fbSSAO.id);
    }

    @Override
    public void free() {
        this.finalBlit.delete();
        if (this.unifiedFogBlit != null) {
            this.unifiedFogBlit.delete();
        }
        this.ssaoCompute.free();
        this.fb.free();
        this.fbSSAO.free();
        if (this.colourTex != null) {
            this.colourTex.free();
            this.colourSSAOTex.free();
        }
        super.free0();
    }
}
