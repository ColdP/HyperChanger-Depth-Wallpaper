# SPDX-License-Identifier: AGPL-3.0-or-later
"""Optional desktop Skia regression: run with numpy and skia-python installed."""
from pathlib import Path
import re

import numpy as np
import skia


root = Path(__file__).resolve().parents[2]
source = (root / 'app/src/main/java/io/github/btm_m/depthwallpaper/depthwallpaper/DepthWallpaperPreview.kt').read_text(encoding='utf-8')
shader = re.search(r'private const val LAYERED_SHADER = """(.*?)"""', source, re.S).group(1)
effect = skia.RuntimeEffect.MakeForShader(shader)


def render(alpha, depth, foreground=None, foreground_depth=.7, tilt=(0.0, 0.0)):
    builder = skia.RuntimeShaderBuilder(effect)
    builder.setUniform('size', [16.0, 16.0])
    builder.setUniform('parallax', [3.0, 2.0])
    builder.setUniform('tilt', list(tilt))
    builder.setUniform('subjectDepth', .7)
    builder.setChild('img', foreground or skia.Shaders.Color(skia.Color4f(.8, .4, .2, alpha)))
    builder.setChild('bg', skia.Shaders.Color(skia.Color4f(.1, .3, .6, 1)))
    builder.setChild('dep', skia.Shaders.Color(skia.Color4f(depth, depth, depth, 1)))
    builder.setChild('fgDepth', skia.Shaders.Color(skia.Color4f(foreground_depth, foreground_depth, foreground_depth, 1)))
    builder.setChild('msk', skia.Shaders.Color(skia.Color4f(alpha, alpha, alpha, 1)))
    surface = skia.Surface(16, 16)
    surface.getCanvas().drawPaint(skia.Paint(Shader=builder.makeShader()))
    return surface.toarray(colorType=skia.ColorType.kRGBA_8888_ColorType) / 255.0


for alpha in [0, .1, .25, .5, .75, 1]:
    result = render(alpha, .5)[8, 8]
    expected = np.array([.8, .4, .2]) * alpha + np.array([.1, .3, .6]) * (1 - alpha)
    np.testing.assert_allclose(result[:3], expected, atol=2 / 255.0)
    np.testing.assert_allclose(result[3], 1, atol=1 / 255.0)

# A foreground point cannot jump when depth changes abruptly across the silhouette.
coordinate_effect = skia.RuntimeEffect.MakeForShader('half4 main(float2 p) { return half4(p.x / 32.0, p.y / 32.0, 0.0, 1.0); }')
foreground = skia.RuntimeShaderBuilder(coordinate_effect).makeShader()
np.testing.assert_array_equal(render(1, .1, foreground), render(1, .9, foreground))
# The complete background must remain opaque when a large foreground moves to either side.
width, height = 96, 64
image_pixels = np.full((height, width, 4), [255, 0, 255, 255], dtype=np.uint8)
mask_pixels = np.zeros((height, width, 4), dtype=np.uint8)
mask_pixels[:, :, 3] = 255
mask_pixels[12:52, 28:68, :3] = 255
image_shader = skia.Image.fromarray(image_pixels).makeShader()
mask_shader = skia.Image.fromarray(mask_pixels).makeShader()
plate_effect = skia.RuntimeEffect.MakeForShader('half4 main(float2 p) { return half4(p.x / 192.0, p.y / 128.0, 0.35, 1.0); }')
plate_shader = skia.RuntimeShaderBuilder(plate_effect).makeShader()
for displacement in [-12.0, 0.0, 12.0]:
    builder = skia.RuntimeShaderBuilder(effect)
    builder.setUniform('size', [float(width), float(height)])
    builder.setUniform('parallax', [displacement, 0.0])
    builder.setUniform('tilt', [0.0, 0.0])
    builder.setUniform('subjectDepth', .8)
    builder.setChild('img', image_shader)
    builder.setChild('msk', mask_shader)
    builder.setChild('bg', plate_shader)
    builder.setChild('dep', skia.Shaders.Color(skia.Color4f(.3, .3, .3, 1)))
    builder.setChild('fgDepth', skia.Shaders.Color(skia.Color4f(.8, .8, .8, 1)))
    surface = skia.Surface(width, height)
    surface.getCanvas().drawPaint(skia.Paint(Shader=builder.makeShader()))
    rendered = surface.toarray(colorType=skia.ColorType.kRGBA_8888_ColorType)
    assert np.all(rendered[:, :, 3] == 255)
    background_pixels = rendered[:, :, 1] > 0
    assert np.all(rendered[:, :, 2][background_pixels] >= 88)

# The foreground interior follows depth while its perimeter remains anchored to the
# common subject depth, preserving a clean silhouette and giving the subject volume.
gradient_effect = skia.RuntimeEffect.MakeForShader('half4 main(float2 p) { return half4(p.x / 96.0, p.y / 64.0, 0.0, 1.0); }')
gradient = skia.RuntimeShaderBuilder(gradient_effect).makeShader()
def render_scene(fg_depth):
    builder = skia.RuntimeShaderBuilder(effect)
    builder.setUniform('size', [96.0, 64.0])
    builder.setUniform('parallax', [12.0, 0.0])
    builder.setUniform('tilt', [0.0, 0.0])
    builder.setUniform('subjectDepth', .5)
    builder.setChild('img', gradient)
    builder.setChild('bg', skia.Shaders.Color(skia.Color4f(.1, .3, .6, 1)))
    builder.setChild('dep', skia.Shaders.Color(skia.Color4f(.5, .5, .5, 1)))
    builder.setChild('fgDepth', skia.Shaders.Color(skia.Color4f(fg_depth, fg_depth, fg_depth, 1)))
    builder.setChild('msk', mask_shader)
    surface = skia.Surface(96, 64)
    surface.getCanvas().drawPaint(skia.Paint(Shader=builder.makeShader()))
    return surface.toarray(colorType=skia.ColorType.kRGBA_8888_ColorType)

far_subject = render_scene(.2)
near_subject = render_scene(.8)
# Depth changes move the interior, but the edge strip remains close to the anchored pose.
assert np.mean(np.abs(far_subject[25, 38:58, 0].astype(float) - near_subject[25, 38:58, 0].astype(float))) > 5
assert np.mean(np.abs(far_subject[25, 28:33, 0].astype(float) - near_subject[25, 28:33, 0].astype(float))) < 2
print('Skia shader checks passed: alpha compositing, silhouette stability, continuous subject relief, and opaque revealed backgrounds at three parallax positions')
