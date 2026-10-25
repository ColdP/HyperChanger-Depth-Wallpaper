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


def render(alpha, depth, foreground=None):
    builder = skia.RuntimeShaderBuilder(effect)
    builder.setUniform('size', [16.0, 16.0])
    builder.setUniform('parallax', [3.0, 2.0])
    builder.setUniform('tilt', [0.0, 0.0])
    builder.setUniform('subjectDepth', .7)
    builder.setChild('img', foreground or skia.Shaders.Color(skia.Color4f(.8, .4, .2, alpha)))
    builder.setChild('bg', skia.Shaders.Color(skia.Color4f(.1, .3, .6, 1)))
    builder.setChild('dep', skia.Shaders.Color(skia.Color4f(depth, depth, depth, 1)))
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
print('Skia shader checks passed: compilation, six transparent/opaque alpha levels, and foreground continuity across depth edges')
