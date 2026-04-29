#version 300 es
precision highp float;

uniform sampler2D u_mask;
uniform vec3 u_bgColor;
uniform int u_correctionOn;
uniform float u_maxWearR;
uniform float u_maxWearG;
uniform float u_maxWearB;
uniform int u_simulatePanel;
uniform float u_wearStrength;

uniform int u_zoneCount;
uniform vec4 u_zones[8];           // x0,y0,x1,y1 in UV [0,1] per zone
uniform float u_zoneDims[8];
uniform vec3 u_zoneChannels[8];    // RGB has-wear flag per zone (0 or 1)
uniform int u_selectedZone;
uniform vec2 u_pxToUV;             // (1/maskW, 1/maskH) — also used for outline thickness
uniform vec2 u_maskSizePx;         // (maskW, maskH)
uniform float u_falloffRadiusPx;   // outward linear-ramp radius around each bbox
uniform float u_globalDim;         // baseline dim everywhere on wearing channels

in vec2 v_uv;
out vec4 fragColor;

vec3 srgb2lin(vec3 c) { return pow(c, vec3(2.2)); }
vec3 lin2srgb(vec3 c) { return pow(c, vec3(1.0/2.2)); }

bool inBox(vec2 uv, vec4 b) {
    return uv.x >= b.x && uv.x <= b.z && uv.y >= b.y && uv.y <= b.w;
}

bool onBorder(vec2 uv, vec4 b, float thickness) {
    if (!inBox(uv, b)) return false;
    return uv.x < b.x + thickness || uv.x > b.z - thickness ||
           uv.y < b.y + thickness || uv.y > b.w - thickness;
}

float zoneWeight(vec2 uv, vec4 b, float radiusPx) {
    vec2 d_uv = max(max(b.xy - uv, uv - b.zw), vec2(0.0));
    if (radiusPx <= 0.0) {
        return (d_uv.x == 0.0 && d_uv.y == 0.0) ? 1.0 : 0.0;
    }
    vec2 d_px = d_uv * u_maskSizePx;
    float dist = length(d_px);
    return clamp(1.0 - dist / radiusPx, 0.0, 1.0);
}

void main() {
    vec3 src = u_bgColor;
    vec3 wearRaw = texture(u_mask, v_uv).rgb;
    vec3 lin = srgb2lin(src);
    vec3 outLin = lin;

    if (u_correctionOn == 1) {
        bool marked = max(wearRaw.r, max(wearRaw.g, wearRaw.b)) > 0.001;
        if (!marked) {
            float zw = 0.0;
            float zd = 0.0;
            vec3 zoneCh = vec3(0.0);   // dominant zone's channel mask
            // Loop up to u_zoneCount; static array bound 8 keeps the shader compilable on all drivers.
            for (int i = 0; i < 8; ++i) {
                if (i >= u_zoneCount) break;
                float w = zoneWeight(v_uv, u_zones[i], u_falloffRadiusPx);
                if (w > zw) { zw = w; zd = u_zoneDims[i]; zoneCh = u_zoneChannels[i]; }
                else if (w == zw && u_zoneDims[i] > zd) { zd = u_zoneDims[i]; zoneCh = u_zoneChannels[i]; }
            }
            float dim = mix(u_globalDim, zd, zw);
            // Channel mask transitions smoothly with the same weight that drives dim:
            //   weight=1 inside bbox  → use zone's channel mask exactly
            //   weight=0 far from any → fall back to global "channels with any wear"
            //   in between (falloff)  → linear blend between the two, avoiding ring artifacts
            vec3 globalCh = step(vec3(0.001), vec3(u_maxWearR, u_maxWearG, u_maxWearB));
            vec3 channelMask = mix(globalCh, zoneCh, zw);
            vec3 dimV = clamp(channelMask * vec3(dim), vec3(0.0), vec3(0.99));
            outLin = lin * (vec3(1.0) - dimV);
        }
    }

    outLin = clamp(outLin, 0.0, 1.0);

    if (u_simulatePanel == 1) {
        outLin = outLin * (vec3(1.0) - wearRaw * u_wearStrength);
    }

    float th = 3.0 * max(u_pxToUV.x, u_pxToUV.y);
    if (u_zoneCount > 0 && u_selectedZone < u_zoneCount) {
        vec4 sel = u_zones[u_selectedZone];
        if (onBorder(v_uv, sel, th)) {
            outLin = vec3(1.0, 0.6, 0.0);
        }
    }

    fragColor = vec4(lin2srgb(outLin), 1.0);
}
