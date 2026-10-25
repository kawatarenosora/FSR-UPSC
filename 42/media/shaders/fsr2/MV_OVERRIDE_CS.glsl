#version 430 core
// FSR-UPSC addition (not from AMD FidelityFX): overwrite camera-generated
// motion vectors inside recorded entity rects (ObjectMotion). Runs after
// MV_GEN (or after the MV clear) and before RECONSTRUCT; the fetch barrier
// covers both. See MotionField.java for the CPU-side layout.
//
// Units: everything in render pixels, matching MV_GEN's
//   mv = (prevUv - uv) * vec2(uRenderSize)  (prev - cur convention).
// posm.xy = rect top-left, posm.zw = (mvx, mvy), sizeh.xy = (w, h).
layout(local_size_x = 8, local_size_y = 8) in;
layout(binding = 0, rg16f) uniform image2D uOutputMV;
layout(std140, binding = 3) uniform MotionBuf {
    vec4 posm[126];
    vec4 sizeh[126];
};

uniform int uCount;

void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (uCount <= 0) return;
    float px = float(p.x) + 0.5;
    float py = float(p.y) + 0.5;
    for (int i = 0; i < uCount; ++i) {
        vec4 r = posm[i];
        vec4 s = sizeh[i];
        if (px >= r.x && px < r.x + s.x && py >= r.y && py < r.y + s.y) {
            imageStore(uOutputMV, p, vec4(r.z, r.w, 0.0, 0.0));
            return;
        }
    }
}
