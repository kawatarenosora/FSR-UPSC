#version 430 core
// FSR-UPSC addition (M3 auto-reactive): port of the FSR4 merged MV_GEN's
// "T&C-STYLE AUTO-REACTIVE" term (fsr-reloaded FsrProcessor.java MV_GEN_CS).
// The MV field is camera/entity driven; particles, animated foliage, mobs,
// potion overlays and screen-space trim (outlines, view cone) move
// independently, so their pixels get ~0 MV and history keeps accumulating at
// a stale position. This pass compares the CURRENT color at p against the
// PREVIOUS frame's color at the reprojected position (prevUv from the same
// MV field RECONSTRUCT/DEPTH_CLIP consume). A color difference the motion
// cannot explain = the content genuinely changed = reactive. Output R8,
// consumed by DEPTH_CLIP through uReactiveMask (binding 8, AMD contract).
//
// Faithful ports from the reference: still/moving ramp edges (0.10/0.30 and
// 0.18/0.50), the x0.9 partial-reactivity cap, the offscreen-prevUv
// disocclusion rule (reactive = 1.0), the still-camera search radius (±1;
// ±2 while moving), and the constant-depth trust gate (term disabled where
// mvLen >= 1.5 render px because prevUv is inexact; lifted when real
// per-pixel depth — DepthProxy — is active).
layout(local_size_x = 8, local_size_y = 8) in;
layout(binding = 0) uniform sampler2D uInputColor;
layout(binding = 1) uniform sampler2D uPrevColor;   // prev frame raw color
layout(binding = 2) uniform sampler2D uDilatedData; // rg = mv in UV units
layout(binding = 3, r8) writeonly uniform image2D uOutputReactive;

uniform ivec2 uRenderSize;
uniform int uHavePrev;   // prevColor holds last frame's input color
uniform int uUseDepth;   // 1 when DepthProxy feeds exact depth
uniform int uCamMotion;  // 0 = camera genuinely still (jitter-excluded)

void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (p.x >= uRenderSize.x || p.y >= uRenderSize.y) {
        imageStore(uOutputReactive, p, vec4(0.0, 0.0, 0.0, 0.0));
        return;
    }
    if (uHavePrev <= 0) {
        imageStore(uOutputReactive, p, vec4(0.0, 0.0, 0.0, 0.0));
        return;
    }

    vec2 uv = (vec2(p) + 0.5) / vec2(uRenderSize);
    vec2 mvUv = texelFetch(uDilatedData, p, 0).rg;
    vec2 prevUv = uv + mvUv;
    float mvLenPx = length(mvUv) * float(uRenderSize.x);

    // T&C trust gate (constant depth): only where the reprojection is
    // near-exact. Real depth: lift the gate entirely (reference parity).
    if (uUseDepth <= 0 && mvLenPx >= 1.5) {
        imageStore(uOutputReactive, p, vec4(0.0, 0.0, 0.0, 0.0));
        return;
    }

    bool noPrev = prevUv.x < 0.0 || prevUv.x > 1.0
        || prevUv.y < 0.0 || prevUv.y > 1.0;

    float reactive;
    if (noPrev) {
        // Was not visible last frame: no history can exist.
        reactive = 1.0;
    } else {
        vec3 curRgb = max(vec3(0.0), texelFetch(uInputColor, p, 0).rgb);
        float curLuma = dot(curRgb, vec3(0.2126, 0.7152, 0.0722));
        ivec2 prevTexel = clamp(
            ivec2(prevUv * vec2(uRenderSize)),
            ivec2(0), uRenderSize - 1);
        // Search radius absorbs the ±0.5px jitter shift plus small
        // entity-MV unit error; ±1 while still catches any mover >= 2px.
        int searchR = (uCamMotion > 0) ? 2 : 1;
        float diffLo = (uCamMotion > 0) ? 0.18 : 0.10;
        float diffHi = (uCamMotion > 0) ? 0.50 : 0.30;
        float minDiff = 1e9;
        for (int dy = -searchR; dy <= searchR; ++dy) {
            for (int dx = -searchR; dx <= searchR; ++dx) {
                ivec2 q = clamp(prevTexel + ivec2(dx, dy),
                    ivec2(0), uRenderSize - 1);
                vec3 pc = max(vec3(0.0), texelFetch(uPrevColor, q, 0).rgb);
                float pl = dot(pc, vec3(0.2126, 0.7152, 0.0722));
                minDiff = min(minDiff, abs(curLuma - pl));
                if (minDiff <= diffLo) break;
            }
            if (minDiff <= diffLo) break;
        }
        // Partial reactivity (reference "review fix"): subtle animation
        // keeps partial history; strong changes (mobs, particles,
        // disoccluded content) go near-full discard.
        reactive = smoothstep(diffLo, diffHi, minDiff) * 0.9;
    }

    imageStore(uOutputReactive, p, vec4(reactive, 0.0, 0.0, 0.0));
}
