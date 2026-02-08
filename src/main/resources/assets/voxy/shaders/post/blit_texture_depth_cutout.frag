#version 450 core

layout(binding = 0) uniform sampler2D depthTex; // Voxy Depth
layout(binding = 4) uniform sampler2D vanillaDepthTex; // Vanilla Depth
layout(location = 1) uniform mat4 invProjMatVoxy;
layout(location = 2) uniform mat4 projMatVanilla;
layout(location = 3) uniform mat4 invProjMatVanilla;

#ifdef EMIT_COLOUR
layout(binding = 3) uniform sampler2D colourTex;
#ifdef USE_ENV_FOG
layout(location = 4) uniform vec3 endParams;
layout(location = 5) uniform vec3 fogColour;
#endif
#ifdef USE_ATMOSPHERIC_FOG
layout(location = 6) uniform vec4 atmosphericFogParams; // x: density, y: falloff, z: start, w: unused
layout(location = 7) uniform vec3 atmosphericFogColor;
#endif
#endif

out vec4 colour;
in vec2 UV;

vec3 rev3d(vec3 clip, mat4 invProj) {
    vec4 view = invProj * vec4(clip*2.0f-1.0f,1.0f);
    return view.xyz/view.w;
}
float projDepth(vec3 pos, mat4 proj) {
    vec4 view = proj * vec4(pos, 1);
    return view.z/view.w;
}

void main() {
    float voxyDepth = texture(depthTex, UV.xy).r;
    float vanillaDepth = texture(vanillaDepthTex, UV.xy).r;

    if (voxyDepth == 0.0f || voxyDepth == 1.0) {
        discard;
    }

    // Determine if Voxy is closer than Vanilla
    vec3 voxyPoint = rev3d(vec3(UV.xy, voxyDepth), invProjMatVoxy);
    float voxyDist = length(voxyPoint);
    
    // If vanilla depth is 1.0, it's the sky, so vanillaDist is effectively infinite
    float vanillaDist = 1e10; 
    if (vanillaDepth < 1.0) {
        vec3 vanillaPoint = rev3d(vec3(UV.xy, vanillaDepth), invProjMatVanilla);
        vanillaDist = length(vanillaPoint);
    }

    // If vanilla is closer (e.g. terrain, water, or even clouds), discard Voxy fragment.
    // We use a small negative bias to ensure Voxy doesn't overwrite vanilla water surfaces.
    if (voxyDist > vanillaDist - 0.05) {
        discard;
    }

    float dist = voxyDist;

    // Output Voxy depth mapped to vanilla projection space
    float outDepth = projDepth(voxyPoint, projMatVanilla);
    
    // To avoid Z-fighting with the sky (at depth 1.0) and clipping issues at the far plane:
    // We clamp the depth to be just inside the visible range [near, far-epsilon]
    outDepth = clamp(outDepth, -1.0, 0.999999); 
    
    outDepth = outDepth * 0.5 + 0.5;
    gl_FragDepth = gl_DepthRange.diff * outDepth + gl_DepthRange.near;

    #ifdef EMIT_COLOUR
    colour = texture(colourTex, UV.xy);
    if (colour.a == 0.0) {
        discard;
    }

    float fogAmount = 0.0;
    #ifdef USE_ENV_FOG
    {
        fogAmount = clamp(fma(min(dist, endParams.x),endParams.y,endParams.z),0,1);
    }
    #endif

    #ifdef USE_ATMOSPHERIC_FOG
    {
        float density = atmosphericFogParams.x;
        float falloff = atmosphericFogParams.y;
        float start = atmosphericFogParams.z;
        
        float atmosphericFogAmount = 1.0 - exp(-pow(max(0.0, dist - start) * density, falloff));
        fogAmount = max(fogAmount, clamp(atmosphericFogAmount, 0.0, 1.0));
    }
    #endif

    #ifdef USE_ENV_FOG
    colour.rgb = mix(colour.rgb, fogColour, fogAmount);
    #else
    #ifdef USE_ATMOSPHERIC_FOG
    colour.rgb = mix(colour.rgb, atmosphericFogColor, fogAmount);
    #endif
    #endif

    #else
    colour = vec4(0);
    #endif
}