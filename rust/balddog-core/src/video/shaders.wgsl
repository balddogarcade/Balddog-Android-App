struct VertexOutput {
    @builtin(position) position: vec4<f32>,
    @location(0) tex_coords: vec2<f32>,
}

struct AspectUniform {
    scale: vec2<f32>,
    rotation: u32,
    _pad: u32,
}

@group(0) @binding(2)
var<uniform> aspect: AspectUniform;

fn rotate_uv(uv: vec2<f32>, rot: u32) -> vec2<f32> {
    switch (rot % 4u) {
        case 1u: {
            return vec2<f32>(1.0 - uv.y, uv.x);
        }
        case 2u: {
            return vec2<f32>(1.0 - uv.x, 1.0 - uv.y);
        }
        case 3u: {
            return vec2<f32>(uv.y, 1.0 - uv.x);
        }
        default: {
            return uv;
        }
    }
}

@vertex
fn vs_main(@builtin(vertex_index) idx: u32) -> VertexOutput {
    var positions = array<vec2<f32>, 6>(
        vec2<f32>(-1.0,  1.0),
        vec2<f32>(-1.0, -1.0),
        vec2<f32>( 1.0, -1.0),
        vec2<f32>(-1.0,  1.0),
        vec2<f32>( 1.0, -1.0),
        vec2<f32>( 1.0,  1.0)
    );
    var uvs = array<vec2<f32>, 6>(
        vec2<f32>(0.0, 0.0),
        vec2<f32>(0.0, 1.0),
        vec2<f32>(1.0, 1.0),
        vec2<f32>(0.0, 0.0),
        vec2<f32>(1.0, 1.0),
        vec2<f32>(1.0, 0.0)
    );

    var out: VertexOutput;
    let pos = positions[idx];
    out.position = vec4<f32>(pos.x * aspect.scale.x, pos.y * aspect.scale.y, 0.0, 1.0);
    out.tex_coords = rotate_uv(uvs[idx], aspect.rotation);
    return out;
}

@group(0) @binding(0)
var frame_tex: texture_2d<f32>;

@group(0) @binding(1)
var frame_sampler: sampler;

@fragment
fn fs_main(in: VertexOutput) -> @location(0) vec4<f32> {
    return textureSample(frame_tex, frame_sampler, in.tex_coords);
}
