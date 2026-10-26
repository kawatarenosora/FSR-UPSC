#version 330

// fsr_rcas.frag: contrast-adaptive sharpen (FSR RCAS class).
//
// Written for FSR-UPSC; not AMD code. Unsharp mask on the 5-tap cross,
// gated by local luma contrast (flat areas are left alone so sensor/UI
// noise is not amplified), clamped per channel to the 3x3 neighbourhood
// range so the pass can never ring outside its footprint.
//
// The math mirrors the Java CPU reference model (numerically
// verified: sharp=0 is the identity, output stays inside the 3x3 range).
//
// Uniforms:
//   DIFFUSE  the EASU output at full res
//   InputSize full-res size in pixels (texel = 1/InputSize)
//   Sharp    0..1 (FsrSharpnessPct / 100)

uniform sampler2D DIFFUSE;
uniform vec2 InputSize;
uniform float Sharp;

in vec4 vColor;
in vec2 vUV1;

float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

void main()
{
    vec2 px = vec2(1.0) / InputSize;
    vec2 uv = vUV1;
    vec3 b = texture2D(DIFFUSE, uv, 0.0).rgb;
    vec3 n = texture2D(DIFFUSE, uv + vec2(0.0, -px.y), 0.0).rgb;
    vec3 s = texture2D(DIFFUSE, uv + vec2(0.0, px.y), 0.0).rgb;
    vec3 w = texture2D(DIFFUSE, uv + vec2(-px.x, 0.0), 0.0).rgb;
    vec3 e = texture2D(DIFFUSE, uv + vec2(px.x, 0.0), 0.0).rgb;
    vec3 d0 = texture2D(DIFFUSE, uv + vec2(-px.x, -px.y), 0.0).rgb;
    vec3 d1 = texture2D(DIFFUSE, uv + vec2(px.x, -px.y), 0.0).rgb;
    vec3 d2 = texture2D(DIFFUSE, uv + vec2(-px.x, px.y), 0.0).rgb;
    vec3 d3 = texture2D(DIFFUSE, uv + vec2(px.x, px.y), 0.0).rgb;

    // 3x3 range (limiter) and luma contrast (noise gate).
    vec3 mn3 = min(min(min(b, n), min(s, w)), min(min(e, d0), min(d1, min(d2, d3))));
    vec3 mx3 = max(max(max(b, n), max(s, w)), max(max(e, d0), max(d1, max(d2, d3))));
    float mnL = luma(mn3);
    float mxL = luma(mx3);
    float gate = smoothstep(1.0 / 255.0, 8.0 / 255.0, mxL - mnL);

    float k = clamp(Sharp, 0.0, 1.0) * gate;
    vec3 blur = (n + s + w + e) * 0.25;
    vec3 v = b * (1.0 + k) - blur * k;
    gl_FragColor = vec4(clamp(v, mn3, mx3), 1.0);
}
