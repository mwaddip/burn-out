#version 300 es

in vec2 a_pos;       // -1..1 quad
out vec2 v_uv;       // 0..1

void main() {
    // Flip Y because Bitmap origin is top-left while GL textures sample with t=0 at the first
    // row uploaded — without this the mask renders upside-down.
    v_uv = vec2(a_pos.x * 0.5 + 0.5, -a_pos.y * 0.5 + 0.5);
    gl_Position = vec4(a_pos, 0.0, 1.0);
}
