use std::ffi::c_void;
use std::ptr::NonNull;

use bytemuck::{Pod, Zeroable};
use raw_window_handle::{
    AndroidDisplayHandle, AndroidNdkWindowHandle, RawDisplayHandle, RawWindowHandle,
};
use wgpu::util::DeviceExt;

use crate::{record_log, CoreError};

#[repr(C)]
#[derive(Copy, Clone, Pod, Zeroable)]
struct AspectUniform {
    scale: [f32; 2],
    _pad: [f32; 2],
}

pub struct WgpuRenderer {
    instance: wgpu::Instance,
    surface: Option<wgpu::Surface<'static>>,
    device: Option<wgpu::Device>,
    queue: Option<wgpu::Queue>,
    config: Option<wgpu::SurfaceConfiguration>,
    pipeline: Option<wgpu::RenderPipeline>,
    bind_group_layout: Option<wgpu::BindGroupLayout>,
    bind_group: Option<wgpu::BindGroup>,
    sampler: Option<wgpu::Sampler>,
    aspect_buffer: Option<wgpu::Buffer>,
    frame_texture: Option<wgpu::Texture>,
    tex_width: u32,
    tex_height: u32,
    game_aspect: f32,
}

impl WgpuRenderer {
    pub fn new(game_aspect: f32) -> Self {
        let instance = wgpu::Instance::new(wgpu::InstanceDescriptor {
            backends: wgpu::Backends::VULKAN | wgpu::Backends::GL,
            ..Default::default()
        });
        Self {
            instance,
            surface: None,
            device: None,
            queue: None,
            config: None,
            pipeline: None,
            bind_group_layout: None,
            bind_group: None,
            sampler: None,
            aspect_buffer: None,
            frame_texture: None,
            tex_width: 0,
            tex_height: 0,
            game_aspect: if game_aspect > 0.1 { game_aspect } else { 4.0 / 3.0 },
        }
    }

    /// Adjunta un `ANativeWindow*` válido para crear la superficie `wgpu`.
    ///
    /// # Safety
    /// `native_window` debe ser un `*mut ANativeWindow` válido con referencia retenida
    /// hasta que se llame a `detach()`.
    pub unsafe fn attach(
        &mut self,
        native_window: *mut c_void,
        width: u32,
        height: u32,
    ) -> Result<(), CoreError> {
        self.detach();

        let nn_win = NonNull::new(native_window)
            .ok_or_else(|| CoreError::Init("ANativeWindow es null".into()))?;

        let raw_window = RawWindowHandle::AndroidNdk(AndroidNdkWindowHandle::new(nn_win));
        let raw_display = RawDisplayHandle::Android(AndroidDisplayHandle::new());

        let target = wgpu::SurfaceTargetUnsafe::RawHandle {
            raw_display_handle: raw_display,
            raw_window_handle: raw_window,
        };

        // SAFETY: `native_window` permanece vivo hasta `detach()` en `surfaceDestroyed`.
        let surface = unsafe { self.instance.create_surface_unsafe(target) }
            .map_err(|e| CoreError::Init(format!("Error creando wgpu::Surface: {e}")))?;

        let adapter = pollster::block_on(self.instance.request_adapter(&wgpu::RequestAdapterOptions {
            power_preference: wgpu::PowerPreference::HighPerformance,
            compatible_surface: Some(&surface),
            force_fallback_adapter: false,
        }))
        .ok_or_else(|| CoreError::Init("No se encontró adaptador GPU compatible".into()))?;

        let (device, queue) = pollster::block_on(adapter.request_device(
            &wgpu::DeviceDescriptor {
                label: Some("balddog-wgpu-device"),
                required_features: wgpu::Features::empty(),
                required_limits: wgpu::Limits::downlevel_webgl2_defaults()
                    .using_resolution(adapter.limits()),
                memory_hints: wgpu::MemoryHints::Performance,
            },
            None,
        ))
        .map_err(|e| CoreError::Init(format!("Error solicitando wgpu::Device: {e}")))?;

        let caps = surface.get_capabilities(&adapter);
        let format = caps
            .formats
            .iter()
            .copied()
            .find(|f| f.is_srgb())
            .or_else(|| caps.formats.first().copied())
            .ok_or_else(|| CoreError::Init("La superficie no reportó formatos".into()))?;

        let safe_w = width.max(1);
        let safe_h = height.max(1);
        let config = wgpu::SurfaceConfiguration {
            usage: wgpu::TextureUsages::RENDER_ATTACHMENT,
            format,
            width: safe_w,
            height: safe_h,
            present_mode: wgpu::PresentMode::Fifo,
            alpha_mode: wgpu::CompositeAlphaMode::Opaque,
            view_formats: vec![],
            desired_maximum_frame_latency: 2,
        };
        surface.configure(&device, &config);

        let shader = device.create_shader_module(wgpu::ShaderModuleDescriptor {
            label: Some("balddog-arcade-shader"),
            source: wgpu::ShaderSource::Wgsl(include_str!("shaders.wgsl").into()),
        });

        let bind_group_layout = device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
            label: Some("balddog-bgl"),
            entries: &[
                wgpu::BindGroupLayoutEntry {
                    binding: 0,
                    visibility: wgpu::ShaderStages::FRAGMENT,
                    ty: wgpu::BindingType::Texture {
                        multisampled: false,
                        view_dimension: wgpu::TextureViewDimension::D2,
                        sample_type: wgpu::TextureSampleType::Float { filterable: true },
                    },
                    count: None,
                },
                wgpu::BindGroupLayoutEntry {
                    binding: 1,
                    visibility: wgpu::ShaderStages::FRAGMENT,
                    ty: wgpu::BindingType::Sampler(wgpu::SamplerBindingType::Filtering),
                    count: None,
                },
                wgpu::BindGroupLayoutEntry {
                    binding: 2,
                    visibility: wgpu::ShaderStages::VERTEX,
                    ty: wgpu::BindingType::Buffer {
                        ty: wgpu::BufferBindingType::Uniform,
                        has_dynamic_offset: false,
                        min_binding_size: None,
                    },
                    count: None,
                },
            ],
        });

        let pipeline_layout = device.create_pipeline_layout(&wgpu::PipelineLayoutDescriptor {
            label: Some("balddog-pipeline-layout"),
            bind_group_layouts: &[&bind_group_layout],
            push_constant_ranges: &[],
        });

        let pipeline = device.create_render_pipeline(&wgpu::RenderPipelineDescriptor {
            label: Some("balddog-pipeline"),
            layout: Some(&pipeline_layout),
            vertex: wgpu::VertexState {
                module: &shader,
                entry_point: Some("vs_main"),
                buffers: &[],
                compilation_options: wgpu::PipelineCompilationOptions::default(),
            },
            fragment: Some(wgpu::FragmentState {
                module: &shader,
                entry_point: Some("fs_main"),
                targets: &[Some(wgpu::ColorTargetState {
                    format,
                    blend: Some(wgpu::BlendState::REPLACE),
                    write_mask: wgpu::ColorWrites::ALL,
                })],
                compilation_options: wgpu::PipelineCompilationOptions::default(),
            }),
            primitive: wgpu::PrimitiveState::default(),
            depth_stencil: None,
            multisample: wgpu::MultisampleState::default(),
            multiview: None,
            cache: None,
        });

        let sampler = device.create_sampler(&wgpu::SamplerDescriptor {
            label: Some("balddog-nearest-sampler"),
            mag_filter: wgpu::FilterMode::Nearest,
            min_filter: wgpu::FilterMode::Linear,
            ..Default::default()
        });

        let aspect_data = self.compute_aspect_uniform(safe_w, safe_h);
        let aspect_buffer = device.create_buffer_init(&wgpu::util::BufferInitDescriptor {
            label: Some("balddog-aspect-buf"),
            contents: bytemuck::bytes_of(&aspect_data),
            usage: wgpu::BufferUsages::UNIFORM | wgpu::BufferUsages::COPY_DST,
        });

        self.surface = Some(surface);
        self.device = Some(device);
        self.queue = Some(queue);
        self.config = Some(config);
        self.pipeline = Some(pipeline);
        self.bind_group_layout = Some(bind_group_layout);
        self.sampler = Some(sampler);
        self.aspect_buffer = Some(aspect_buffer);
        self.frame_texture = None;
        self.bind_group = None;
        self.tex_width = 0;
        self.tex_height = 0;

        record_log(
            "INFO",
            &format!("wgpu Surface adjuntada ({}x{}, backend={:?})", safe_w, safe_h, adapter.get_info().backend),
        );
        Ok(())
    }

    pub fn resize(&mut self, width: u32, height: u32) {
        let safe_w = width.max(1);
        let safe_h = height.max(1);
        if let (Some(surface), Some(device), Some(config)) =
            (&self.surface, &self.device, &mut self.config)
        {
            config.width = safe_w;
            config.height = safe_h;
            surface.configure(device, config);
            if let (Some(queue), Some(buf)) = (&self.queue, &self.aspect_buffer) {
                let aspect_data = self.compute_aspect_uniform(safe_w, safe_h);
                queue.write_buffer(buf, 0, bytemuck::bytes_of(&aspect_data));
            }
        }
    }

    pub fn detach(&mut self) {
        self.bind_group = None;
        self.frame_texture = None;
        self.aspect_buffer = None;
        self.sampler = None;
        self.bind_group_layout = None;
        self.pipeline = None;
        self.config = None;
        self.queue = None;
        self.device = None;
        self.surface = None;
    }

    fn compute_aspect_uniform(&self, surface_w: u32, surface_h: u32) -> AspectUniform {
        let screen_aspect = surface_w as f32 / surface_h.max(1) as f32;
        let target_aspect = self.game_aspect;
        let (sx, sy) = if screen_aspect > target_aspect {
            (target_aspect / screen_aspect, 1.0)
        } else {
            (1.0, screen_aspect / target_aspect)
        };
        AspectUniform {
            scale: [sx, sy],
            _pad: [0.0, 0.0],
        }
    }

    pub fn render_frame(&mut self, width: u32, height: u32, rgba: &[u8]) {
        let (Some(surface), Some(device), Some(queue), Some(pipeline), Some(bgl), Some(sampler), Some(aspect_buf)) = (
            &self.surface,
            &self.device,
            &self.queue,
            &self.pipeline,
            &self.bind_group_layout,
            &self.sampler,
            &self.aspect_buffer,
        ) else {
            return;
        };

        if self.frame_texture.is_none() || self.tex_width != width || self.tex_height != height {
            let tex = device.create_texture(&wgpu::TextureDescriptor {
                label: Some("balddog-frame-tex"),
                size: wgpu::Extent3d {
                    width,
                    height,
                    depth_or_array_layers: 1,
                },
                mip_level_count: 1,
                sample_count: 1,
                dimension: wgpu::TextureDimension::D2,
                format: wgpu::TextureFormat::Rgba8UnormSrgb,
                usage: wgpu::TextureUsages::TEXTURE_BINDING | wgpu::TextureUsages::COPY_DST,
                view_formats: &[],
            });
            let view = tex.create_view(&wgpu::TextureViewDescriptor::default());
            let bg = device.create_bind_group(&wgpu::BindGroupDescriptor {
                label: Some("balddog-frame-bg"),
                layout: bgl,
                entries: &[
                    wgpu::BindGroupEntry {
                        binding: 0,
                        resource: wgpu::BindingResource::TextureView(&view),
                    },
                    wgpu::BindGroupEntry {
                        binding: 1,
                        resource: wgpu::BindingResource::Sampler(sampler),
                    },
                    wgpu::BindGroupEntry {
                        binding: 2,
                        resource: aspect_buf.as_entire_binding(),
                    },
                ],
            });
            self.frame_texture = Some(tex);
            self.bind_group = Some(bg);
            self.tex_width = width;
            self.tex_height = height;
        }

        if let Some(tex) = &self.frame_texture {
            queue.write_texture(
                wgpu::ImageCopyTexture {
                    texture: tex,
                    mip_level: 0,
                    origin: wgpu::Origin3d::ZERO,
                    aspect: wgpu::TextureAspect::All,
                },
                rgba,
                wgpu::ImageDataLayout {
                    offset: 0,
                    bytes_per_row: Some(width * 4),
                    rows_per_image: Some(height),
                },
                wgpu::Extent3d {
                    width,
                    height,
                    depth_or_array_layers: 1,
                },
            );
        }

        let output = match surface.get_current_texture() {
            Ok(frame) => frame,
            Err(wgpu::SurfaceError::Lost | wgpu::SurfaceError::Outdated) => {
                if let Some(cfg) = &self.config {
                    surface.configure(device, cfg);
                }
                return;
            }
            Err(_) => return,
        };

        let view = output
            .texture
            .create_view(&wgpu::TextureViewDescriptor::default());
        let mut encoder = device.create_command_encoder(&wgpu::CommandEncoderDescriptor {
            label: Some("balddog-render-encoder"),
        });

        {
            let mut pass = encoder.begin_render_pass(&wgpu::RenderPassDescriptor {
                label: Some("balddog-render-pass"),
                color_attachments: &[Some(wgpu::RenderPassColorAttachment {
                    view: &view,
                    resolve_target: None,
                    ops: wgpu::Operations {
                        load: wgpu::LoadOp::Clear(wgpu::Color {
                            r: 0.039,
                            g: 0.039,
                            b: 0.047,
                            a: 1.0,
                        }),
                        store: wgpu::StoreOp::Store,
                    },
                })],
                depth_stencil_attachment: None,
                timestamp_writes: None,
                occlusion_query_set: None,
            });
            if let Some(bg) = &self.bind_group {
                pass.set_pipeline(pipeline);
                pass.set_bind_group(0, bg, &[]);
                pass.draw(0..6, 0..1);
            }
        }

        queue.submit(std::iter::once(encoder.finish()));
        output.present();
    }
}
