#version 330

// fsr_easu.frag: edge-directed spatial upsample (FSR EASU class).
//
// Written for FSR-UPSC; not AMD code. Algorithm: gather a 4x4 luma
// neighbourhood, estimate the local edge direction (Sobel), then filter
// with a 16-tap rotated lanczos2 kernel that is wide along the edge and
// narrow across it. Output is clamped per channel to the tap min/max, so
// the pass can never invent values outside its footprint (no halos, no
// NaN/Inf: the weight sum is renormalised and the kernel is finite).
//
// The math mirrors the Java CPU reference model (numerically
// verified: step transition as tight as bilinear, RCAS stage restores
// acutance, flat areas untouched). Jitter is in input pixels for the
// future temporal path; pass 0 for now.
//
// Uniforms:
//   DIFFUSE   the low-res world pass (linear filtering preferred;
//             degrades gracefully to nearest)
//   InputSize  low-res size in pixels
//   OutputSize full-res size in pixels
//   Jitter     sub-pixel offset in input pixels (0,0 = off)

uniform sampler2D DIFFUSE;
uniform vec2 InputSize;
uniform vec2 OutputSize;
uniform vec2 Jitter;

in vec4 vColor;
in vec2 vUV1;

float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

float lanczos2(float x) {
    float ax = abs(x);
    if (ax >= 2.0) return 0.0;
    if (ax < 1e-5) return 1.0;
    float px = 3.14159265 * x;
    return (sin(px) / px) * (sin(px * 0.5) / (px * 0.5));
}

vec3 tap(vec2 inPx) { return texture2D(DIFFUSE, inPx / InputSize, 0.0).rgb; }

void main()
{
    vec2 outPx = vUV1 * OutputSize;
    vec2 ratio = InputSize / OutputSize;
    vec2 pp = outPx * ratio + Jitter;

    // 4x4 luma neighbourhood around pp.
    vec2 base = floor(pp - 0.5) + 0.5;
    float l[16];
    for (int j = 0; j < 4; j++)
        for (int i = 0; i < 4; i++)
            l[j * 4 + i] = luma(tap(base + vec2(float(i), float(j))));

    // Sobel gradient on the centre 3x3; edge runs perpendicular to it.
    float gx = (l[2] + 2.0 * l[6] + l[10]) - (l[0] + 2.0 * l[4] + l[8]);
    float gy = (l[8] + 2.0 * l[9] + l[10]) - (l[0] + 2.0 * l[1] + l[2]);
    float gl = length(vec2(gx, gy));
    vec2 edge = vec2(1.0, 0.0);
    float strength = 0.0;
    if (gl > 1e-4) {
        edge = vec2(-gy, gx) / gl;
        strength = clamp(gl * 1.5, 0.0, 1.0);
    }
    vec2 grad = vec2(-edge.y, edge.x);

    // Kernel footprint follows the output pixel size in input space.
    float foot = min(min(ratio.x, ratio.y), 1.0);
    // Across-edge lobe narrows with edge strength; along-edge stays full.
    float wcs = mix(1.0, 0.55, strength);

    vec3 acc = vec3(0.0);
    vec3 mn = vec3(1e9);
    vec3 mx = vec3(-1e9);
    float wsum = 0.0;
    for (int ai = 0; ai < 4; ai++) {
        // a in {-1.5,-0.5,0.5,1.5}
        float a = float(ai) < 0.5 ? -1.5 : (float(ai) < 1.5 ? -0.5 : (float(ai) < 2.5 ? 0.5 : 1.5));
        for (int ci = 0; ci < 4; ci++) {
            float c = float(ci) < 0.5 ? -1.5 : (float(ci) < 1.5 ? -0.5 : (float(ci) < 2.5 ? 0.5 : 1.5));
            vec2 pos = pp + (a * edge + c * grad) * foot;
            float w = lanczos2(a) * lanczos2(c / wcs);
            vec3 t = tap(pos);
            acc += t * w;
            mn = min(mn, t);
            mx = max(mx, t);
            wsum += w;
        }
    }
    vec3 v = acc / max(wsum, 1e-6);
    gl_FragColor = vec4(clamp(v, mn, mx), 1.0);
}
