#version 430 core
// FSR-UPSC addition (M2c DepthProxy, not from AMD FidelityFX):
// convert the world FBO's hardware depth (blitted into our own depth-texture
// attachment by DepthProxy.java) into a monotonic R32F device-depth proxy
// consumed by RECONSTRUCT / DEPTH_CLIP / ACCUMULATE with
//   uDeviceToViewDepth = (0, 1, 0, 0)  ->  viewZ = 1.0 / d
//   uFarDepth = 1/50, uFarViewZ = 50   ->  sky only when d < 1/47.5
// The hardware depth d in [0,1] is optionally z-flipped (uFlip) so the
// background always lands at the FAR end of the mapping, then mapped
//   V = 1 + d * 49   (large V = far, matching the AMD sky rule)
//   out = 1.0 / V    (so the generic perspective formula viewZ = dtv[1]/(d-dtv[0])
//                     with dtv = (0,1,0,0) reproduces V exactly).
// See DepthProxy.java for probe/detection policy.
layout(local_size_x = 8, local_size_y = 8) in;
layout(binding = 0) uniform sampler2D uDepthIn;   // hardware depth (depth attachment tex)
layout(binding = 1, r32f) uniform writeonly image2D uDepthOut;

uniform int uFlip;  // 1 = source is reversed-z (background reads ~0)

void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    float d = texelFetch(uDepthIn, p, 0).r;
    if (uFlip > 0) d = 1.0 - d;
    float v = 1.0 + d * 49.0;   // V in [1, 50]
    imageStore(uDepthOut, p, vec4(1.0 / v, 0.0, 0.0, 0.0));
}
