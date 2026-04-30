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
uniform vec4 u_zones[12];          // x0,y0,x1,y1 in UV [0,1] per zone (cap matches CorrectionState.MAX_ZONES)
uniform float u_zoneDims[12];
uniform vec3 u_zoneChannels[12];   // RGB has-wear flag per zone (0 or 1)
uniform int u_selectedZone;
uniform vec2 u_pxToUV;             // (1/maskW, 1/maskH) — also used for outline thickness
uniform vec2 u_maskSizePx;         // (maskW, maskH)
uniform float u_falloffRadiusPx;   // outward linear-ramp radius around each bbox
uniform vec3 u_globalDim;          // per-channel baseline dim (R, G, B). Each channel independent.
uniform float u_sevWearFraction[5]; // cumulative bucket-to-wear-fraction lookup [0, c1, c2, c3, 1.0]
uniform vec3 u_zoneMaxEffWear[12];  // per-zone-per-channel max effective wear (0..1)

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
        // Per-pixel effective wear via sevWearFraction[] lookup.
        // Round wearChannels * 4 to nearest int → bucket index 0..4.
        ivec3 bucket = ivec3(round(wearRaw * 4.0));
        bucket = clamp(bucket, ivec3(0), ivec3(4));
        vec3 effWear = vec3(
            u_sevWearFraction[bucket.r],
            u_sevWearFraction[bucket.g],
            u_sevWearFraction[bucket.b]
        );

        float zw = 0.0;
        float zd = 0.0;
        vec3 zoneCh = vec3(0.0);
        vec3 zoneMaxEff = vec3(0.0);
        for (int i = 0; i < 12; ++i) {
            if (i >= u_zoneCount) break;
            float w = zoneWeight(v_uv, u_zones[i], u_falloffRadiusPx);
            if (w > zw || (w == zw && u_zoneDims[i] > zd)) {
                zw = w; zd = u_zoneDims[i];
                zoneCh = u_zoneChannels[i];
                zoneMaxEff = u_zoneMaxEffWear[i];
            }
        }

        // Severity factor: 1.0 for unmarked (or factor=1 fallback), 0.0 at zone-max-wear,
        // linear in between. Channels with zoneMaxEff == 0 (zone has no wear in this channel)
        // fall back to factor=1 — unmarked-style baseline behavior, gated by zoneCh below.
        vec3 factor = clamp(
            (zoneMaxEff - effWear) / max(zoneMaxEff, vec3(1e-6)),
            vec3(0.0), vec3(1.0));
        factor = mix(vec3(1.0), factor, step(vec3(1e-6), zoneMaxEff));

        vec3 zoneDimV = mix(u_globalDim, vec3(zd), zw);
        vec3 nonZoneCh = vec3(1.0) - zoneCh;
        // Apply factor only to the zone term; baseline (non-zone) channels stay full-strength
        // (their globalDim is the manual rebalancing knob, unrelated to per-pixel severity).
        vec3 dimV = clamp(
            zoneCh * zoneDimV * factor + nonZoneCh * u_globalDim,
            vec3(0.0), vec3(0.99));
        outLin = lin * (vec3(1.0) - dimV);
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
