package rinth.render.webgl2;

import static org.teavm.jso.webgl.WebGL2RenderingContext.LINEAR;
import static org.teavm.jso.webgl.WebGL2RenderingContext.LINEAR_MIPMAP_LINEAR;
import static org.teavm.jso.webgl.WebGL2RenderingContext.NEAREST;
import static org.teavm.jso.webgl.WebGL2RenderingContext.NEAREST_MIPMAP_LINEAR;
import static org.teavm.jso.webgl.WebGL2RenderingContext.NEAREST_MIPMAP_NEAREST;
import static org.teavm.jso.webgl.WebGL2RenderingContext.TEXTURE_MAG_FILTER;
import static org.teavm.jso.webgl.WebGL2RenderingContext.TEXTURE_MAX_LOD;
import static org.teavm.jso.webgl.WebGL2RenderingContext.TEXTURE_MIN_FILTER;
import static org.teavm.jso.webgl.WebGL2RenderingContext.TEXTURE_WRAP_S;
import static org.teavm.jso.webgl.WebGL2RenderingContext.TEXTURE_WRAP_T;

import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import java.util.OptionalDouble;
import org.teavm.jso.webgl.WebGL2RenderingContext;
import org.teavm.jso.webgl.WebGLSampler;

/**
 * A WebGL2 sampler object. Minification always blends between mip levels, as on the game's other
 * backends: a texture without mips has one level, and a view limits the levels, so the blend only
 * does anything where there are levels to blend.
 *
 * <p>WebGL2 can't blend a depth or integer texture's texels at all ({@link GlFormats#filterable}):
 * sampled with a blending filter, it reads as zero. That broke the clouds in Fabulous, whose pass
 * copies the world's depth through a sampler: every cloud passed the depth test and drew over the
 * terrain. So each sampler has a second WebGL sampler that never blends, bound for those textures.
 */
final class WebGl2Sampler implements GpuSampler {

    private final WebGl2Device device;
    private final WebGLSampler handle;
    private final WebGLSampler unfiltered;
    private final AddressMode addressModeU;
    private final AddressMode addressModeV;
    private final FilterMode minFilter;
    private final FilterMode magFilter;
    private final int maxAnisotropy;
    private final OptionalDouble maxLod;
    private boolean closed;

    WebGl2Sampler(
            WebGl2Device device,
            AddressMode addressModeU,
            AddressMode addressModeV,
            FilterMode minFilter,
            FilterMode magFilter,
            int maxAnisotropy,
            OptionalDouble maxLod) {
        this.device = device;
        this.addressModeU = addressModeU;
        this.addressModeV = addressModeV;
        this.minFilter = minFilter;
        this.magFilter = magFilter;
        this.maxAnisotropy = maxAnisotropy;
        this.maxLod = maxLod;
        WebGL2RenderingContext gl = device.gl();
        handle = gl.createSampler();
        gl.samplerParameteri(handle, TEXTURE_WRAP_S, GlFormats.wrap(addressModeU));
        gl.samplerParameteri(handle, TEXTURE_WRAP_T, GlFormats.wrap(addressModeV));
        gl.samplerParameteri(
                handle,
                TEXTURE_MIN_FILTER,
                minFilter == FilterMode.LINEAR ? LINEAR_MIPMAP_LINEAR : NEAREST_MIPMAP_LINEAR);
        gl.samplerParameteri(handle, TEXTURE_MAG_FILTER, magFilter == FilterMode.LINEAR ? LINEAR : NEAREST);
        if (maxLod.isPresent()) {
            gl.samplerParameterf(handle, TEXTURE_MAX_LOD, (float) maxLod.getAsDouble());
        }
        if (maxAnisotropy > 1 && device.hasAnisotropy()) {
            gl.samplerParameterf(handle, WebGl.TEXTURE_MAX_ANISOTROPY, maxAnisotropy);
        }
        unfiltered = gl.createSampler();
        gl.samplerParameteri(unfiltered, TEXTURE_WRAP_S, GlFormats.wrap(addressModeU));
        gl.samplerParameteri(unfiltered, TEXTURE_WRAP_T, GlFormats.wrap(addressModeV));
        gl.samplerParameteri(unfiltered, TEXTURE_MIN_FILTER, NEAREST_MIPMAP_NEAREST);
        gl.samplerParameteri(unfiltered, TEXTURE_MAG_FILTER, NEAREST);
        if (maxLod.isPresent()) {
            gl.samplerParameterf(unfiltered, TEXTURE_MAX_LOD, (float) maxLod.getAsDouble());
        }
    }

    /** The WebGL sampler for a texture: the one that never blends where its format can't be filtered. */
    WebGLSampler handle(boolean filterable) {
        return filterable ? handle : unfiltered;
    }

    @Override
    public AddressMode getAddressModeU() {
        return addressModeU;
    }

    @Override
    public AddressMode getAddressModeV() {
        return addressModeV;
    }

    @Override
    public FilterMode getMinFilter() {
        return minFilter;
    }

    @Override
    public FilterMode getMagFilter() {
        return magFilter;
    }

    @Override
    public int getMaxAnisotropy() {
        return maxAnisotropy;
    }

    @Override
    public OptionalDouble getMaxLod() {
        return maxLod;
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            device.gl().deleteSampler(handle);
            device.gl().deleteSampler(unfiltered);
        }
    }
}
