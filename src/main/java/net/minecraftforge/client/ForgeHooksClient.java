/*
 * Minecraft Forge
 * Copyright (c) 2016-2019.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation version 2.1
 * of the License.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */

package net.minecraftforge.client;

import static net.minecraftforge.fml.VersionChecker.Status.BETA;
import static net.minecraftforge.fml.VersionChecker.Status.BETA_OUTDATED;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL20.*;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import javax.annotation.Nonnull;
import javax.vecmath.Matrix3f;
import javax.vecmath.Matrix4f;
import javax.vecmath.Vector3f;
import javax.vecmath.Vector4f;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.async.ThreadNameCachingStrategy;
import org.apache.logging.log4j.core.impl.ReusableLogEventFactory;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.GameSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.ItemRenderer;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.model.BakedQuad;
import net.minecraft.client.renderer.model.BlockFaceUV;
import net.minecraft.client.renderer.model.IBakedModel;
import net.minecraft.client.renderer.model.ItemCameraTransforms;
import net.minecraft.client.renderer.model.ItemTransformVec3f;
import net.minecraft.client.renderer.model.ModelRotation;
import net.minecraft.client.renderer.model.SimpleBakedModel;
import net.minecraft.client.renderer.entity.model.ModelBiped;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.client.renderer.vertex.VertexFormatElement;
import net.minecraft.client.renderer.vertex.VertexFormatElement.EnumUsage;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.IWorldReader;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraftforge.client.model.pipeline.QuadGatheringTransformer;
import net.minecraftforge.common.ForgeMod;
import net.minecraftforge.common.model.IModelPart;
import net.minecraftforge.common.model.ITransformation;
import net.minecraftforge.common.model.TRSRTransformation;
import net.minecraftforge.fml.VersionChecker;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.resource.ReloadRequirements;
import net.minecraftforge.resource.SelectiveReloadStateHandler;
import net.minecraftforge.resource.VanillaResourceType;
import net.minecraftforge.versions.forge.ForgeVersion;

public class ForgeHooksClient
{
    private static final Logger LOGGER = LogManager.getLogger();

    public static String getArmorTexture(Entity entity, ItemStack armor, String _default, EntityEquipmentSlot slot, String type)
    {
        String result = armor.getItem().getArmorTexture(armor, entity, slot, type);
        return result != null ? result : _default;
    }

    static int renderPass = -1;
    public static void setRenderPass(int pass)
    {
        renderPass = pass;
    }

    static final ThreadLocal<BlockRenderLayer> renderLayer = new ThreadLocal<BlockRenderLayer>();

    public static void setRenderLayer(BlockRenderLayer layer)
    {
        renderLayer.set(layer);
    }

    public static ModelBiped getArmorModel(EntityLivingBase entityLiving, ItemStack itemStack, EntityEquipmentSlot slot, ModelBiped _default)
    {
        ModelBiped model = itemStack.getItem().getArmorModel(entityLiving, itemStack, slot, _default);
        return model == null ? _default : model;
    }

    //This properly moves the domain, if provided, to the front of the string before concatenating
    public static String fixDomain(String base, String complex)
    {
        int idx = complex.indexOf(':');
        if (idx == -1)
        {
            return base + complex;
        }

        String name = complex.substring(idx + 1, complex.length());
        if (idx > 1)
        {
            String domain = complex.substring(0, idx);
            return domain + ':' + base + name;
        }
        else
        {
            return base + name;
        }
    }

    private static int skyX, skyZ;

    private static boolean skyInit;
    private static int skyRGBMultiplier;

    public static int getSkyBlendColour(World world, BlockPos center)
    {
        if (center.getX() == skyX && center.getZ() == skyZ && skyInit)
        {
            return skyRGBMultiplier;
        }
        skyInit = true;

        GameSettings settings = Minecraft.getInstance().gameSettings;
        int[] ranges = ForgeMod.blendRanges;
        int distance = 0;
        if (settings.fancyGraphics && ranges.length > 0)
        {
            distance = ranges[MathHelper.clamp(settings.renderDistanceChunks, 0, ranges.length-1)];
        }

        int r = 0;
        int g = 0;
        int b = 0;

        int divider = 0;
        for (int x = -distance; x <= distance; ++x)
        {
            for (int z = -distance; z <= distance; ++z)
            {
                BlockPos pos = center.add(x, 0, z);
                Biome biome = world.getBiome(pos);
                int colour = biome.getSkyColorByTemp(biome.getTemperature(pos));
                r += (colour & 0xFF0000) >> 16;
                g += (colour & 0x00FF00) >> 8;
                b += colour & 0x0000FF;
                divider++;
            }
        }

        int multiplier = (r / divider & 255) << 16 | (g / divider & 255) << 8 | b / divider & 255;

        skyX = center.getX();
        skyZ = center.getZ();
        skyRGBMultiplier = multiplier;
        return skyRGBMultiplier;
    }

    public static void renderMainMenu(GuiMainMenu gui, FontRenderer font, int width, int height)
    {
        VersionChecker.Status status = ForgeVersion.getStatus();
        if (status == BETA || status == BETA_OUTDATED)
        {
            // render a warning at the top of the screen,
            String line = I18n.format("forge.update.beta.1", TextFormatting.RED, TextFormatting.RESET);
            gui.drawString(font, line, (width - font.getStringWidth(line)) / 2, 4 + (0 * (font.FONT_HEIGHT + 1)), -1);
            line = I18n.format("forge.update.beta.2");
            gui.drawString(font, line, (width - font.getStringWidth(line)) / 2, 4 + (1 * (font.FONT_HEIGHT + 1)), -1);
        }

        String line = null;
        switch(status)
        {
            //case FAILED:        line = " Version check failed"; break;
            //case UP_TO_DATE:    line = "Forge up to date"}; break;
            //case AHEAD:         line = "Using non-recommended Forge build, issues may arise."}; break;
            case OUTDATED:
            case BETA_OUTDATED: line = I18n.format("forge.update.newversion", ForgeVersion.getTarget()); break;
            default: break;
        }

        if (line != null)
        {
            // if we have a line, render it in the bottom right, above Mojang's copyright line
            gui.drawString(font, line, width - font.getStringWidth(line) - 2, height - (2 * (font.FONT_HEIGHT + 1)), -1);
        }
    }

    @SuppressWarnings("deprecation")
    public static Matrix4f getMatrix(net.minecraft.client.renderer.model.ItemTransformVec3f transform)
    {
        javax.vecmath.Matrix4f m = new javax.vecmath.Matrix4f(), t = new javax.vecmath.Matrix4f();
        m.setIdentity();
        m.setTranslation(TRSRTransformation.toVecmath(transform.translation));
        t.setIdentity();
        t.rotY(transform.rotation.getY());
        m.mul(t);
        t.setIdentity();
        t.rotX(transform.rotation.getX());
        m.mul(t);
        t.setIdentity();
        t.rotZ(transform.rotation.getZ());
        m.mul(t);
        t.setIdentity();
        t.m00 = transform.scale.getX();
        t.m11 = transform.scale.getY();
        t.m22 = transform.scale.getZ();
        m.mul(t);
        return m;
    }

    private static final Matrix4f flipX;
    static {
        flipX = new Matrix4f();
        flipX.setIdentity();
        flipX.m00 = -1;
    }

    public static IBakedModel handleCameraTransforms(IBakedModel model, ItemCameraTransforms.TransformType cameraTransformType, boolean leftHandHackery)
    {
        Pair<? extends IBakedModel, Matrix4f> pair = model.handlePerspective(cameraTransformType);

        if (pair.getRight() != null)
        {
            Matrix4f matrix = new Matrix4f(pair.getRight());
            if (leftHandHackery)
            {
                matrix.mul(flipX, matrix);
                matrix.mul(matrix, flipX);
            }
            multiplyCurrentGlMatrix(matrix);
        }
        return pair.getLeft();
    }

    private static final FloatBuffer matrixBuf = BufferUtils.createFloatBuffer(16);

    public static void multiplyCurrentGlMatrix(Matrix4f matrix)
    {
        matrixBuf.clear();
        float[] t = new float[4];
        for(int i = 0; i < 4; i++)
        {
            matrix.getColumn(i, t);
            matrixBuf.put(t);
        }
        matrixBuf.flip();
        glMultMatrixf(matrixBuf);
    }

    // moved and expanded from WorldVertexBufferUploader.draw

    public static void preDraw(EnumUsage attrType, VertexFormat format, int element, int stride, ByteBuffer buffer)
    {
        VertexFormatElement attr = format.getElement(element);
        int count = attr.getElementCount();
        int constant = attr.getType().getGlConstant();
        buffer.position(format.getOffset(element));
        switch(attrType)
        {
            case POSITION:
                glVertexPointer(count, constant, stride, buffer);
                glEnableClientState(GL_VERTEX_ARRAY);
                break;
            case NORMAL:
                if(count != 3)
                {
                    throw new IllegalArgumentException("Normal attribute should have the size 3: " + attr);
                }
                glNormalPointer(constant, stride, buffer);
                glEnableClientState(GL_NORMAL_ARRAY);
                break;
            case COLOR:
                glColorPointer(count, constant, stride, buffer);
                glEnableClientState(GL_COLOR_ARRAY);
                break;
            case UV:
                OpenGlHelper.glClientActiveTexture(OpenGlHelper.GL_TEXTURE0 + attr.getIndex());
                glTexCoordPointer(count, constant, stride, buffer);
                glEnableClientState(GL_TEXTURE_COORD_ARRAY);
                OpenGlHelper.glClientActiveTexture(OpenGlHelper.GL_TEXTURE0);
                break;
            case PADDING:
                break;
            case GENERIC:
                glEnableVertexAttribArray(attr.getIndex());
                glVertexAttribPointer(attr.getIndex(), count, constant, false, stride, buffer);
                break;
            default:
                LOGGER.fatal("Unimplemented vanilla attribute upload: {}", attrType.getDisplayName());
        }
    }

    public static void postDraw(EnumUsage attrType, VertexFormat format, int element, int stride, ByteBuffer buffer)
    {
        VertexFormatElement attr = format.getElement(element);
        switch(attrType)
        {
            case POSITION:
                glDisableClientState(GL_VERTEX_ARRAY);
                break;
            case NORMAL:
                glDisableClientState(GL_NORMAL_ARRAY);
                break;
            case COLOR:
                glDisableClientState(GL_COLOR_ARRAY);
                // is this really needed?
                GlStateManager.resetColor();
                break;
            case UV:
                OpenGlHelper.glClientActiveTexture(OpenGlHelper.GL_TEXTURE0 + attr.getIndex());
                glDisableClientState(GL_TEXTURE_COORD_ARRAY);
                OpenGlHelper.glClientActiveTexture(OpenGlHelper.GL_TEXTURE0);
                break;
            case PADDING:
                break;
            case GENERIC:
                glDisableVertexAttribArray(attr.getIndex());
                break;
            default:
                LOGGER.fatal("Unimplemented vanilla attribute upload: {}", attrType.getDisplayName());
        }
    }

    public static void transform(net.minecraft.client.renderer.Vector3f vec, Matrix4f m)
    {
        Vector4f tmp = new Vector4f(vec.getX(), vec.getY(), vec.getZ(), 1f);
        m.transform(tmp);
        if(Math.abs(tmp.w - 1f) > 1e-5) tmp.scale(1f / tmp.w);
        vec.set(tmp.x, tmp.y, tmp.z);
    }

    public static Matrix4f getMatrix(ModelRotation modelRotation)
    {
        Matrix4f ret = new Matrix4f(TRSRTransformation.toVecmath(modelRotation.getMatrix()), new Vector3f(), 1), tmp = new Matrix4f();
        tmp.setIdentity();
        tmp.m03 = tmp.m13 = tmp.m23 = .5f;
        ret.mul(tmp, ret);
        tmp.invert();
        //tmp.m03 = tmp.m13 = tmp.m23 = -.5f;
        ret.mul(tmp);
        return ret;
    }

    public static void putQuadColor(BufferBuilder renderer, BakedQuad quad, int color)
    {
        float cb = color & 0xFF;
        float cg = (color >>> 8) & 0xFF;
        float cr = (color >>> 16) & 0xFF;
        float ca = (color >>> 24) & 0xFF;
        VertexFormat format = quad.getFormat();
        int size = format.getIntegerSize();
        int offset = format.getColorOffset() / 4; // assumes that color is aligned
        boolean hasColor = format.hasColor();
        for(int i = 0; i < 4; i++)
        {
            int vc = hasColor ? quad.getVertexData()[offset + size * i] : 0xFFFFFFFF;
            float vcr = vc & 0xFF;
            float vcg = (vc >>> 8) & 0xFF;
            float vcb = (vc >>> 16) & 0xFF;
            float vca = (vc >>> 24) & 0xFF;
            int ncr = Math.min(0xFF, (int)(cr * vcr / 0xFF));
            int ncg = Math.min(0xFF, (int)(cg * vcg / 0xFF));
            int ncb = Math.min(0xFF, (int)(cb * vcb / 0xFF));
            int nca = Math.min(0xFF, (int)(ca * vca / 0xFF));
            renderer.putColorRGBA(renderer.getColorIndex(4 - i), ncr, ncg, ncb, nca);
        }
    }

    private static class LightGatheringTransformer extends QuadGatheringTransformer {

        private static final VertexFormat FORMAT = new VertexFormat().addElement(DefaultVertexFormats.TEX_2F).addElement(DefaultVertexFormats.TEX_2S);

        int blockLight, skyLight;

        { setVertexFormat(FORMAT); }

        boolean hasLighting() 
        {
            return dataLength[1] >= 2;
        }

        @Override
        protected void processQuad() 
        {
            // Reset light data
            blockLight = 0;
            skyLight = 0;
            // Compute average light for all 4 vertices
            for (int i = 0; i < 4; i++) 
            {
                blockLight += (int) ((quadData[1][i][0] * 0xFFFF) / 0x20);
                skyLight += (int) ((quadData[1][i][1] * 0xFFFF) / 0x20);
            }
            // Values must be multiplied by 16, divided by 4 for average => x4
            blockLight *= 4;
            skyLight *= 4;
        }

        // Dummy overrides

        @Override
        public void setQuadTint(int tint) {}

        @Override
        public void setQuadOrientation(EnumFacing orientation) {}

        @Override
        public void setApplyDiffuseLighting(boolean diffuse) {}

        @Override
        public void setTexture(TextureAtlasSprite texture) {}
    }

    private static final LightGatheringTransformer lightGatherer = new LightGatheringTransformer();

    public static void renderLitItem(ItemRenderer ri, IBakedModel model, int color, ItemStack stack)
    {
        List<BakedQuad> allquads = new ArrayList<>();
        Random random = new Random();
        long seed = 42L;

        for (EnumFacing enumfacing : EnumFacing.values())
        {
            random.setSeed(seed);
            allquads.addAll(model.getQuads(null, enumfacing, random));
        }

        random.setSeed(seed);
        allquads.addAll(model.getQuads(null, null, random));

        if (allquads.isEmpty()) return;

        // Current list of consecutive quads with the same lighting
        List<BakedQuad> segment = new ArrayList<>();

        // Lighting of the current segment
        int segmentBlockLight = 0;
        int segmentSkyLight = 0;
        // Diffuse lighting state
        boolean segmentShading = true;
        // State changed by the current segment
        boolean segmentLightingDirty = false;
        boolean segmentShadingDirty = false;
        // If the current segment contains lighting data
        boolean hasLighting = false;

        for (int i = 0; i < allquads.size(); i++) 
        {
            BakedQuad q = allquads.get(i);

            // Lighting of the current quad
            int bl = 0;
            int sl = 0;

            // Fail-fast on ITEM, as it cannot have light data
            if (q.getFormat() != DefaultVertexFormats.ITEM && q.getFormat().hasUv(1))
            {
                q.pipe(lightGatherer);
                if (lightGatherer.hasLighting())
                {
                    bl = lightGatherer.blockLight;
                    sl = lightGatherer.skyLight;
                }
            }

            boolean shade = q.shouldApplyDiffuseLighting();

            boolean lightingDirty = segmentBlockLight != bl || segmentSkyLight != sl;
            boolean shadeDirty = shade != segmentShading; 

            // If lighting or color data has changed, draw the segment and flush it
            if (lightingDirty || shadeDirty)
            {
                if (i > 0) // Make sure this isn't the first quad being processed
                {
                    drawSegment(ri, color, stack, segment, segmentBlockLight, segmentSkyLight, segmentShading, segmentLightingDirty && (hasLighting || segment.size() < i), segmentShadingDirty);
                }
                segmentBlockLight = bl;
                segmentSkyLight = sl;
                segmentShading = shade;
                segmentLightingDirty = lightingDirty;
                segmentShadingDirty = shadeDirty;
                hasLighting = segmentBlockLight > 0 || segmentSkyLight > 0 || !segmentShading;
            }

            segment.add(q);
        }

        drawSegment(ri, color, stack, segment, segmentBlockLight, segmentSkyLight, segmentShading, segmentLightingDirty && (hasLighting || segment.size() < allquads.size()), segmentShadingDirty);

        // Clean up render state if necessary
        if (hasLighting)
        {
            OpenGlHelper.glMultiTexCoord2f(OpenGlHelper.GL_TEXTURE0, OpenGlHelper.lastBrightnessX, OpenGlHelper.lastBrightnessY);
            GlStateManager.enableLighting();
        }
    }

    private static void drawSegment(ItemRenderer ri, int baseColor, ItemStack stack, List<BakedQuad> segment, int bl, int sl, boolean shade, boolean updateLighting, boolean updateShading)
    {
        BufferBuilder bufferbuilder = Tessellator.getInstance().getBuffer();
        bufferbuilder.begin(GL11.GL_QUADS, DefaultVertexFormats.ITEM);

        float lastBl = OpenGlHelper.lastBrightnessX;
        float lastSl = OpenGlHelper.lastBrightnessY;

        if (updateShading)
        {
            if (shade)
            {
                // (Re-)enable lighting for normal look with shading
                GlStateManager.enableLighting();
            }
            else
            {
                // Disable lighting to simulate a lack of diffuse lighting
                GlStateManager.disableLighting();
            }
        }
        
        if (updateLighting)
        {
            // Force lightmap coords to simulate synthetic lighting
            OpenGlHelper.glMultiTexCoord2f(OpenGlHelper.GL_TEXTURE0, Math.max(bl, lastBl), Math.max(sl, lastSl));
        }

        ri.renderQuads(bufferbuilder, segment, baseColor, stack);
        Tessellator.getInstance().draw();

        // Preserve this as it represents the "world" lighting
        OpenGlHelper.lastBrightnessX = lastBl;
        OpenGlHelper.lastBrightnessY = lastSl;

        segment.clear();
    }

    /**
     * internal, relies on fixed format of FaceBakery
     */
    public static void fillNormal(int[] faceData, EnumFacing facing)
    {
        Vector3f v1 = getVertexPos(faceData, 3);
        Vector3f t  = getVertexPos(faceData, 1);
        Vector3f v2 = getVertexPos(faceData, 2);
        v1.sub(t);
        t.set(getVertexPos(faceData, 0));
        v2.sub(t);
        v1.cross(v2, v1);
        v1.normalize();

        int x = ((byte) Math.round(v1.x * 127)) & 0xFF;
        int y = ((byte) Math.round(v1.y * 127)) & 0xFF;
        int z = ((byte) Math.round(v1.z * 127)) & 0xFF;

        int normal = x | (y << 0x08) | (z << 0x10);

        for(int i = 0; i < 4; i++)
        {
            faceData[i * 7 + 6] = normal;
        }
    }

    private static Vector3f getVertexPos(int[] data, int vertex)
    {
        int idx = vertex * 7;

        float x = Float.intBitsToFloat(data[idx]);
        float y = Float.intBitsToFloat(data[idx + 1]);
        float z = Float.intBitsToFloat(data[idx + 2]);

        return new Vector3f(x, y, z);
    }

    @SuppressWarnings("deprecation")
    public static Optional<TRSRTransformation> applyTransform(ItemTransformVec3f transform, Optional<? extends IModelPart> part)
    {
        if(part.isPresent()) return Optional.empty();
        return Optional.of(TRSRTransformation.blockCenterToCorner(TRSRTransformation.from(transform)));
    }

    public static Optional<TRSRTransformation> applyTransform(ModelRotation rotation, Optional<? extends IModelPart> part)
    {
        if(part.isPresent()) return Optional.empty();
        return Optional.of(TRSRTransformation.from(rotation));
    }

    public static Optional<TRSRTransformation> applyTransform(Matrix4f matrix, Optional<? extends IModelPart> part)
    {
        if(part.isPresent()) return Optional.empty();
        return Optional.of(new TRSRTransformation(matrix));
    }

    public static void loadEntityShader(Entity entity, GameRenderer entityRenderer)
    {
        if (entity != null)
        {
            ResourceLocation shader = ClientRegistry.getEntityShader(entity.getClass());
            if (shader != null)
            {
                entityRenderer.loadShader(shader);
            }
        }
    }

    public static IBakedModel getDamageModel(IBakedModel ibakedmodel, TextureAtlasSprite texture, IBlockState state, IWorldReader world, BlockPos pos)
    {
        state = state.getBlock().getExtendedState(state, world, pos);
        return (new SimpleBakedModel.Builder(state, ibakedmodel, texture, new Random(), 42)).build();
    }

    private static int slotMainHand = 0;

    public static boolean shouldCauseReequipAnimation(@Nonnull ItemStack from, @Nonnull ItemStack to, int slot)
    {
        boolean fromInvalid = from.isEmpty();
        boolean toInvalid   = to.isEmpty();

        if (fromInvalid && toInvalid) return false;
        if (fromInvalid || toInvalid) return true;

        boolean changed = false;
        if (slot != -1)
        {
            changed = slot != slotMainHand;
            slotMainHand = slot;
        }
        return from.getItem().shouldCauseReequipAnimation(from, to, changed);
    }

    public static BlockFaceUV applyUVLock(BlockFaceUV blockFaceUV, EnumFacing originalSide, ITransformation rotation)
    {
        TRSRTransformation global = new TRSRTransformation(rotation.getMatrixVec());
        Matrix4f uv = global.getUVLockTransform(originalSide).getMatrixVec();
        Vector4f vec = new Vector4f(0, 0, 0, 1);
        float u0 = blockFaceUV.getVertexU(blockFaceUV.getVertexRotatedRev(0));
        float v0 = blockFaceUV.getVertexV(blockFaceUV.getVertexRotatedRev(0));
        vec.x = u0 / 16;
        vec.y = v0 / 16;
        uv.transform(vec);
        float uMin = 16 * vec.x; // / vec.w;
        float vMin = 16 * vec.y; // / vec.w;
        float u1 = blockFaceUV.getVertexU(blockFaceUV.getVertexRotatedRev(2));
        float v1 = blockFaceUV.getVertexV(blockFaceUV.getVertexRotatedRev(2));
        vec.x = u1 / 16;
        vec.y = v1 / 16;
        vec.z = 0;
        vec.w = 1;
        uv.transform(vec);
        float uMax = 16 * vec.x; // / vec.w;
        float vMax = 16 * vec.y; // / vec.w;
        if (uMin > uMax && u0 < u1 || uMin < uMax && u0 > u1)
        {
            float t = uMin;
            uMin = uMax;
            uMax = t;
        }
        if (vMin > vMax && v0 < v1 || vMin < vMax && v0 > v1)
        {
            float t = vMin;
            vMin = vMax;
            vMax = t;
        }
        float a = (float)Math.toRadians(blockFaceUV.rotation);
        Vector3f rv = new Vector3f(MathHelper.cos(a), MathHelper.sin(a), 0);
        Matrix3f rot = new Matrix3f();
        uv.getRotationScale(rot);
        rot.transform(rv);
        int angle = MathHelper.normalizeAngle(-(int)Math.round(Math.toDegrees(Math.atan2(rv.y, rv.x)) / 90) * 90, 360);
        return new BlockFaceUV(new float[]{ uMin, vMin, uMax, vMax }, angle);
    }

    @SuppressWarnings("deprecation")
    public static Pair<? extends IBakedModel,Matrix4f> handlePerspective(IBakedModel model, ItemCameraTransforms.TransformType type)
    {
        TRSRTransformation tr = TRSRTransformation.from(model.getItemCameraTransforms().getTransform(type));
        Matrix4f mat = null;
        if(!tr.equals(TRSRTransformation.identity())) mat = tr.getMatrixVec();
        return Pair.of(model, mat);
    }

    public static void refreshResources(Minecraft mc, VanillaResourceType... types)
    {
        SelectiveReloadStateHandler.INSTANCE.beginReload(ReloadRequirements.include(types));
        mc.refreshResources();
        SelectiveReloadStateHandler.INSTANCE.endReload();
    }

    // Resets cached thread fields in ThreadNameCachingStrategy and ReusableLogEventFactory to be repopulated during their next access.
    // This serves a workaround for no built-in method of triggering this type of refresh as brought up by LOG4J2-2178.
    public static void invalidateLog4jThreadCache()
    {
        try
        {
            Field nameField = ThreadNameCachingStrategy.class.getDeclaredField("THREADLOCAL_NAME");
            Field logEventField = ReusableLogEventFactory.class.getDeclaredField("mutableLogEventThreadLocal");
            nameField.setAccessible(true);
            logEventField.setAccessible(true);
            ((ThreadLocal<?>) nameField.get(null)).set(null);
            ((ThreadLocal<?>) logEventField.get(null)).set(null);
        }
        catch (ReflectiveOperationException | NoClassDefFoundError e)
        {
            LOGGER.error("Unable to invalidate log4j thread cache, thread fields in logs may be inaccurate", e);
        }
    }

    /**
     * Called when a player uses 'pick block', calls new Entity and Block hooks.
     */
    public static boolean onPickBlock(RayTraceResult target, EntityPlayer player, World world)
    {
        ItemStack result;
        boolean isCreative = player.abilities.isCreativeMode;
        TileEntity te = null;

        if (target.type == RayTraceResult.Type.BLOCK)
        {
            IBlockState state = world.getBlockState(target.getBlockPos());

            if (state.isAir(world, target.getBlockPos()))
                return false;

            if (isCreative && GuiScreen.isCtrlKeyDown() && state.hasTileEntity())
                te = world.getTileEntity(target.getBlockPos());

            result = state.getBlock().getPickBlock(state, target, world, target.getBlockPos(), player);
        }
        else
        {
            if (target.type != RayTraceResult.Type.ENTITY || target.entity == null || !isCreative)
            {
                return false;
            }

            result = target.entity.getPickedResult(target);
        }

        if (result.isEmpty())
        {
            return false;
        }

        if (te != null)
        {
            Minecraft.getInstance().storeTEInStack(result, te);
        }

        if (isCreative)
        {
            player.inventory.setPickedItemStack(result);
            Minecraft.getInstance().playerController.sendSlotPacket(player.getHeldItem(EnumHand.MAIN_HAND), 36 + player.inventory.currentItem);
            return true;
        }
        int slot = player.inventory.getSlotFor(result);
        if (slot != -1)
        {
            if (InventoryPlayer.isHotbar(slot))
                player.inventory.currentItem = slot;
            else
                Minecraft.getInstance().playerController.pickItem(slot);
            return true;
        }
        return false;
    }
}
