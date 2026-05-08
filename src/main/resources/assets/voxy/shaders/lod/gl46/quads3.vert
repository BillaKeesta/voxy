#version 460 core
#extension GL_ARB_gpu_shader_int64 : enable

#ifdef USE_NV_JANK
#extension GL_NV_gpu_shader5 : enable
#endif

#define QUAD_BUFFER_BINDING 1
#define MODEL_BUFFER_BINDING 3
#define MODEL_COLOUR_BUFFER_BINDING 4
#define POSITION_SCRATCH_BINDING 5
#define LIGHTING_SAMPLER_BINDING 1

#if defined(VOXY_MDIC_MORPH_DEBUG) || defined(VOXY_VERTEX_BOUNDS_DEBUG)
#define SECTION_METADATA_BUFFER_BINDING 2
#define INDIRECT_SECTION_LOOKUP_BINDING 6
#define VOXY_RENDER_DEBUG_BINDING VOXY_RENDER_DEBUG_BUFFER_BINDING
#import <voxy:lod/section.glsl>
#endif

#ifdef USE_SINGLE_TRI
#define USE_NV_BARRY
#endif

#import <voxy:lod/quad_format.glsl>
#import <voxy:lod/block_model.glsl>
#import <voxy:lod/gl46/bindings.glsl>
#import <voxy:lod/quad_util.glsl>

#if defined(VOXY_MDIC_MORPH_DEBUG) || defined(VOXY_VERTEX_BOUNDS_DEBUG)
layout(binding = VOXY_RENDER_DEBUG_BINDING, std430) restrict buffer VoxyRenderDebugBuffer {
    uint renderDebug[];
};

void recordRenderDebug(uint code, uint drawId, uint sectionCount_, uint sectionId, uvec2 expected, uvec2 got, uint extra,
                       uint e1, uint e2, uint e3, uint e4, uint e5, uint e6, uint e7) {
    uint slot = atomicAdd(renderDebug[0], 1u);
    if (slot == 0u) {
        renderDebug[1] = code;
        renderDebug[2] = drawId;
        renderDebug[3] = sectionCount_;
        renderDebug[4] = sectionId;
        renderDebug[5] = expected.x;
        renderDebug[6] = expected.y;
        renderDebug[7] = got.x;
        renderDebug[8] = got.y;
        renderDebug[9] = extra;
        renderDebug[10] = e1;
        renderDebug[11] = e2;
        renderDebug[12] = e3;
        renderDebug[13] = e4;
        renderDebug[14] = e5;
        renderDebug[15] = e6;
        renderDebug[16] = e7;
    }
}
#endif

#ifdef VOXY_MDIC_MORPH_DEBUG
void recordMorphDebug(uint code, uint drawId, uint sectionCount_, uint sectionId, uvec2 expected, uvec2 got, uint extra) {
    recordRenderDebug(code, drawId, sectionCount_, sectionId, expected, got, extra, 0u, 0u, 0u, 0u, 0u, 0u, 0u);
}

void validateMorphInputs(uint drawId, uvec2 got) {
    uint sectionCount_ = sectionCount;
    if (drawId >= sectionCount_) {
        recordMorphDebug(201u, drawId, sectionCount_, 0u, uvec2(0u), got, uint(gl_VertexID));
        return;
    }

    uint sectionId = indirectLookup[drawId];
    if (sectionId >= MDIC_MORPH_DEBUG_MAX_SECTIONS) {
        recordMorphDebug(202u, drawId, sectionCount_, sectionId, uvec2(0u), got, uint(gl_VertexID));
        return;
    }

    SectionMeta meta = sectionData[sectionId];
    uvec2 expected = extractRawPos(meta);
    if (expected.x != got.x || expected.y != got.y) {
        recordMorphDebug(203u, drawId, sectionCount_, sectionId, expected, got, uint(gl_VertexID));
        return;
    }

    uint quadStart = extractQuadStart(meta);
    uint quadCount = 0u;
    quadCount += meta.b.x & 0xFFFFu;
    quadCount += (meta.b.x >> 16) & 0xFFFFu;
    quadCount += meta.b.y & 0xFFFFu;
    quadCount += (meta.b.y >> 16) & 0xFFFFu;
    quadCount += meta.b.z & 0xFFFFu;
    quadCount += (meta.b.z >> 16) & 0xFFFFu;
    quadCount += meta.b.w & 0xFFFFu;
    quadCount += (meta.b.w >> 16) & 0xFFFFu;

    uint quadIndex = uint(gl_VertexID) >> 2;
    if (quadIndex < quadStart || quadIndex >= quadStart + quadCount) {
        recordMorphDebug(204u, drawId, sectionCount_, sectionId, uvec2(quadStart, quadCount), uvec2(quadIndex, uint(gl_VertexID)), getLoDLevel(got));
    }
}
#endif

#ifdef VOXY_VERTEX_BOUNDS_DEBUG
void validateVertexBounds(uint drawId, uvec2 rawPos, const in QuadData quad) {
    uint sectionCount_ = sectionCount;
    if (drawId >= sectionCount_) {
        recordRenderDebug(311u, drawId, sectionCount_, 0u, uvec2(0u), rawPos, uint(gl_VertexID), 0u, 0u, 0u, 0u, 0u, 0u, 0u);
        return;
    }

    uint sectionId = indirectLookup[drawId];
    if (sectionId >= MDIC_MORPH_DEBUG_MAX_SECTIONS) {
        recordRenderDebug(312u, drawId, sectionCount_, sectionId, uvec2(0u), rawPos, uint(gl_VertexID), 0u, 0u, 0u, 0u, 0u, 0u, 0u);
        return;
    }

    uvec2 expectedRaw = extractRawPos(sectionData[sectionId]);
    if (expectedRaw.x != rawPos.x || expectedRaw.y != rawPos.y) {
        recordRenderDebug(313u, drawId, sectionCount_, sectionId, expectedRaw, rawPos, uint(gl_VertexID), 0u, 0u, 0u, 0u, 0u, 0u, 0u);
        return;
    }

    uint lodLevel = getLoDLevel(rawPos);
    ivec3 baseSection = (getLoDPosition(rawPos) << lodLevel) - baseSectionPos;
    vec3 sectionMin = vec3(baseSection << 5);
    vec3 sectionMax = sectionMin + vec3(32 << lodLevel);
    vec3 addin = abs(swizzelDataAxis(quad.axis, vec3(quad.quadSizeAddin * quad.lodScale, 0.0)));
    vec3 minPoint = min(quad.basePoint, quad.basePoint + addin);
    vec3 maxPoint = max(quad.basePoint, quad.basePoint + addin);
    float epsilon = max(2.0, float(1 << lodLevel) * 0.05);

    if (any(isnan(minPoint)) || any(isnan(maxPoint)) || any(isinf(minPoint)) || any(isinf(maxPoint))) {
        recordRenderDebug(314u, drawId, sectionCount_, sectionId, expectedRaw, rawPos, uint(gl_VertexID),
                          lodLevel, uint(baseSection.x), uint(baseSection.y), uint(baseSection.z),
                          floatBitsToUint(minPoint.x), floatBitsToUint(maxPoint.x), quad.axis);
        return;
    }

    if (any(lessThan(minPoint, sectionMin - vec3(epsilon))) || any(greaterThan(maxPoint, sectionMax + vec3(epsilon)))) {
        recordRenderDebug(315u, drawId, sectionCount_, sectionId, expectedRaw, rawPos, uint(gl_VertexID),
                          lodLevel, uint(baseSection.x), uint(baseSection.y), uint(baseSection.z),
                          floatBitsToUint(minPoint.x), floatBitsToUint(maxPoint.x), quad.axis);
    }
}
#endif

layout(location = 0) out flat uvec4 interData;
#ifndef USE_NV_BARRY
layout(location = 1) out vec2 uv;
#endif

#ifdef USE_NV_JANK
#ifdef GL_NV_gpu_shader5
out gl_PerVertex {
    f16vec4 gl_Position;
};
#endif
#endif

#ifdef DEBUG_RENDER
layout(location = 7) out flat uint quadDebug;
#endif

vec2 taaShift();

//TODO: add a mechanism so that some quads can ignore backface culling
// this would help alot with stuff like crops as they would look kinda weird i think,
// same with flowers etc
void main() {
    taaOffset = taaShift();

    QuadData quad;
    uvec2 pos = positionBuffer[gl_BaseInstance];
    #ifdef VOXY_MDIC_MORPH_DEBUG
    validateMorphInputs(uint(gl_BaseInstance), pos);
    #endif
    Quad rawQuad = quadData[uint(gl_VertexID)>>2];
    setupQuad(quad, rawQuad, pos, (gl_VertexID&3) == 1);
    #ifdef VOXY_VERTEX_BOUNDS_DEBUG
    validateVertexBounds(uint(gl_BaseInstance), pos, quad);
    #endif

    uint cornerId = gl_VertexID&3;

    gl_Position =
    #ifdef USE_NV_JANK
    #ifdef GL_NV_gpu_shader5
    f16vec4
    #endif
    #endif
    (getQuadCornerPos(quad, cornerId));


    #ifndef USE_NV_BARRY
    uv = getCornerUV(quad, cornerId);
    #endif

    //Note: other data is automatically discarded as it is undefiend and has not been generated
    interData = quad.attributeData;


    #ifdef DEBUG_RENDER
    #ifdef VOXY_DEBUG_COLOR_LOD
    quadDebug = getLoDLevel(pos);
    #else
    quadDebug = uint(gl_VertexID)>>2;
    #endif
    #endif
}

#ifndef TAA_PATCH
vec2 taaShift() {return vec2(0.0);}
#endif
