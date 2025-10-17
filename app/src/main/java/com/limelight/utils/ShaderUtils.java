package com.limelight.utils;

public class ShaderUtils {
    public static final String VERTEX_SHADER =
            "attribute vec4 a_Position;\n" +
                    "attribute vec2 a_TexCoord;\n" +
                    "varying vec2 v_TexCoord;\n" +
                    "void main() {\n" +
                    "  gl_Position = a_Position;\n" +
                    "  v_TexCoord = a_TexCoord;\n" +
                    "}";

    public static final String FRAGMENT_SHADER_3D =
            "#extension GL_OES_EGL_image_external : require\n" +
                    "precision mediump float;\n" +
                    "varying vec2 v_TexCoord;\n" +
                    "uniform samplerExternalOES s_ColorTexture;\n" +
                    "uniform sampler2D s_DepthTexture;\n" +
                    "uniform float u_parallax;\n" +
                    "uniform float u_convergence;\n" +
                    "uniform float u_shift;\n" +
                    "uniform bool u_debugMode;\n" +
                    "\n" +
                    "void main() {\n" +
                    "  vec2 depthTexCoord = vec2(v_TexCoord.x, 1.0 - v_TexCoord.y);\n" +
                    "  // Wende deinen bestehenden Offset auf die korrigierte Koordinate an.\n" +
                    "  depthTexCoord -= vec2(abs(u_parallax / 2.0), 0.04);\n" +
                    "  float depth = texture2D(s_DepthTexture, depthTexCoord).r;\n" +
                    "\n" +
                    "  const float zone_radius = 0.70; // Breite der neutralen Zone um Konvergenz\n" +
                    "\n" +
                    "  float depthDiff = depth - u_convergence;\n" +
                    "  // clamp to ensure total width = 1 unit, sliding around convergence\n" +
                    "  depthDiff = clamp(depthDiff, -u_convergence, 1.0 - u_convergence);\n" +
                    "\n" +
                    "  float dist_from_convergence = abs(depth - u_convergence);\n" +
                    "  float fade_multiplier = smoothstep(0.0, zone_radius, dist_from_convergence);\n" +
                    "\n" +
                    "  float parallax_magnitude = abs(u_parallax);\n" +
                    "  float ai_shift = parallax_magnitude * depthDiff;\n" +
                    "\n" +
                    "  // --- Dynamische Vignette, an Konvergenz angepasst ---\n" +
                    "  float edgeWidth = 0.01;\n" +
                    "  float depthLeft  = texture2D(s_DepthTexture, vec2(edgeWidth, 0.5)).r;\n" +
                    "  float depthRight = texture2D(s_DepthTexture, vec2(1.0 - edgeWidth, 0.5)).r;\n" +
                    "\n" +
                    "  // Korrektur: Bezug auf u_convergence statt 0.5\n" +
                    "  float ai_shift_left  = u_parallax * (depthLeft  - u_convergence);\n" +
                    "  float ai_shift_right = u_parallax * (depthRight - u_convergence);\n" +
                    "  float maxEdgeShift = max(abs(ai_shift_left), abs(ai_shift_right));\n" +
                    "\n" +
                    "  bool isLeftEye = (u_parallax < 0.0);\n" +
                    "  float isLeftEyeIndicator = isLeftEye ? 1.0 : -1.0;\n" +
                    "\n" +
                    "  // Vignette leicht an Konvergenz koppeln\n" +
                    "  float vignette_bias = (u_convergence - 0.5) * 0.3; // ±0.15 Anpassung\n" +
                    "  float vignette_start = mix(0.7 - vignette_bias, 1.0 - vignette_bias,\n" +
                    "                             clamp(maxEdgeShift / 0.5, 0.0, 1.0));\n" +
                    "  const float vignette_end = 1.0;\n" +
                    "  if ((depth - u_convergence) < 0.0) {\n" +
                    "    ai_shift *= isLeftEye ? u_shift : (1.0-u_shift);\n" +
                    "  } else {\n" +
                    "    ai_shift *= isLeftEye ? (1.0-u_shift) : u_shift;\n" +
                    "  }\n" +
                    "\n" +
                    "  float h_dist = pow(abs(v_TexCoord.x - 0.5) * 2.0, 1.5);\n" +
                    "  float vignette_factor = 1.0 - smoothstep(vignette_start, vignette_end, h_dist);\n" +
                    "  float final_shift = ai_shift * vignette_factor;\n" +
                    "\n" +
                    "  // ---------------- Backward Warping nur horizontal -----------------\n" +
                    "  vec2 srcUV = vec2(v_TexCoord.x - final_shift * isLeftEyeIndicator, v_TexCoord.y);\n" +
                    "  vec4 shiftedColor = texture2D(s_ColorTexture, clamp(srcUV, 0.0, 1.0));\n" +
                    "\n" +
                    "  vec4 originalColor = texture2D(s_ColorTexture, v_TexCoord);\n" +
                    "  float shiftMagnitude = abs(final_shift) / max(abs(parallax_magnitude), 0.001);\n" +
                    "  float artifactBlendFactor = (1.0 - smoothstep(0.1, 1.0, shiftMagnitude)) * 0.005;\n" +
                    "  vec4 finalColor = mix(shiftedColor, originalColor, artifactBlendFactor);\n" +
                    "\n" +
                    "  if (u_debugMode) {\n" +
                    "    float debugDepth = final_shift;\n" +
                    "    vec3 debugTint = vec3(0.0);\n" +
                    "    if (debugDepth > 0.0) { debugTint.r = debugDepth * 50.0; }\n" +
                    "    else { debugTint.b = -debugDepth * 50.0; }\n" +
                    "    finalColor.rgb += debugTint;\n" +
                    "  }\n" +
                    "\n" +
                    "  gl_FragColor = finalColor;\n" +
                    "}\n";

    public static final String FRAGMENT_SHADER_SEPARABLE_DILATE =
            "precision mediump float;\n" +
                    "varying vec2 v_TexCoord;\n" +
                    "uniform sampler2D s_InputTexture;\n" +
                    "uniform vec2 u_texelSize;\n" +
                    "uniform int u_radius;\n" +
                    // NEU: Die Richtung (z.B. (1.0, 0.0) für horizontal)
                    "uniform vec2 u_direction;\n" +
                    "\n" +
                    "void main() {\n" +
                    "    if (u_radius <= 0) {\n" +
                    "        gl_FragColor = texture2D(s_InputTexture, v_TexCoord);\n" +
                    "        return;\n" +
                    "    }\n" +
                    "\n" +
                    "    float maxDepth = texture2D(s_InputTexture, v_TexCoord).r;\n" +
                    "\n" +
                    "    // Loop in one direction only\n" +
                    "    for (int i = -u_radius; i <= u_radius; i++) {\n" +
                    "        vec2 offset = u_direction * float(i) * u_texelSize;\n" +
                    "        maxDepth = max(maxDepth, texture2D(s_InputTexture, v_TexCoord + offset).r);\n" +
                    "    }\n" +
                    "    gl_FragColor = vec4(vec3(maxDepth), 1.0);\n" +
                    "}\n";
    /**
     * An optimized, single-pass Gaussian blur shader that works as a drop-in replacement.
     * It achieves better performance by taking fewer texture samples over the same blur radius.
     * NOTE: For best performance and quality, a two-pass implementation is still highly recommended.
     */
    public static final String OPTIMIZED_SINGLE_PASS_GAUSSIAN_BLUR_SHADER =
            "precision mediump float;\n" +
                    "varying vec2 v_TexCoord;\n" +
                    "uniform sampler2D s_InputTexture;\n" +
                    "uniform vec2 u_texelSize;\n" +
                    "uniform vec2 u_blurDirection;\n" +
                    "uniform float u_parallax;\n" +

                    "void main() {\n" +
                    "  float minRadius = 10.0;\n" +
                    "  float minSigma  = 5.0;\n" +
                    "\n" +
                    "  float blurRadius = max(minRadius, 60.0 * u_parallax);\n" +
                    "  float sigma      = max(minSigma, 50.0 * u_parallax);\n" +
                    "  float blurStep   = 1.0; // immer in 1-Pixel-Schritten\n" +
                    "  vec4 sum = vec4(0.0);\n" +
                    "  float weightSum = 0.0;\n" +

                    "  for (float i = -blurRadius; i <= blurRadius; i++) {\n" +
                    "    float sampleDistance = i * blurStep;\n" +

                    "    float weight = exp(-(sampleDistance * sampleDistance) / (2.0 * sigma * sigma));\n" +

                    "    sum += texture2D(s_InputTexture, v_TexCoord + sampleDistance * u_texelSize * u_blurDirection) * weight;\n" +
                    "    weightSum += weight;\n" +
                    "  }\n" +
                    "  gl_FragColor = sum / weightSum;\n" +
                    "}\n";

    public static final String SIMPLE_FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
                    "precision mediump float;\n" +
                    "varying vec2 v_TexCoord;\n" +
                    "uniform samplerExternalOES u_Texture;\n" +
                    "void main() {\n" +
                    "    gl_FragColor = texture2D(u_Texture, v_TexCoord);\n" +
                    "}\n";
}