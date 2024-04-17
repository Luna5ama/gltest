#version 460 core

layout(vertices = 4) out;

#include "../GlobalCamera.glsl"

uniform vec3 originOffset;

struct VertData {
    vec3 colorMul;
    vec2 texCoord;
    vec3 normal;
    vec2 lightCoord;
};

in VertData vertData[];
in flat int vertIsWater[];

out VertData tessData[];
out flat int tessIsWater[];

const int MIN_TESS_LEVEL = 0;
const int MAX_TESS_LEVEL = 0;

float calcTessDistance(float d) {
    return pow(0.95, d);
}

void main() {
    gl_out[gl_InvocationID].gl_Position = gl_in[gl_InvocationID].gl_Position;
    tessData[gl_InvocationID] = vertData[gl_InvocationID];
    tessIsWater[gl_InvocationID] = vertIsWater[gl_InvocationID];

    if (gl_InvocationID == 0) {
        if (bool(tessIsWater[gl_InvocationID])) {
            vec3 pos00 = gl_in[0].gl_Position.xyz + originOffset;
            vec3 pos11 = gl_in[3].gl_Position.xyz + originOffset;
            vec3 posC = (pos00 + pos11) * 0.5;

            float distance0 = length(vec3(pos00.x, posC.y, posC.z));
            float distance1 = length(vec3(posC.x, posC.y, pos00.z));
            float distance2 = length(vec3(pos11.x, posC.y, posC.z));
            float distance3 = length(vec3(posC.x, posC.y, pos11.z));

            float tessLevel0 = mix(MIN_TESS_LEVEL, MAX_TESS_LEVEL, calcTessDistance(distance0));
            float tessLevel1 = mix(MIN_TESS_LEVEL, MAX_TESS_LEVEL, calcTessDistance(distance1));
            float tessLevel2 = mix(MIN_TESS_LEVEL, MAX_TESS_LEVEL, calcTessDistance(distance2));
            float tessLevel3 = mix(MIN_TESS_LEVEL, MAX_TESS_LEVEL, calcTessDistance(distance3));

            // ----------------------------------------------------------------------
            // Step 5: set the corresponding outer edge tessellation levels
            gl_TessLevelOuter[0] = max(tessLevel0, 1);
            gl_TessLevelOuter[1] = max(tessLevel1, 1);
            gl_TessLevelOuter[2] = max(tessLevel2, 1);
            gl_TessLevelOuter[3] = max(tessLevel3, 1);

            // ----------------------------------------------------------------------
            // Step 6: set the inner tessellation levels to the max of the two parallel edges
            gl_TessLevelInner[0] = max(tessLevel1, tessLevel3);
            gl_TessLevelInner[1] = max(tessLevel0, tessLevel2);
        } else {
            gl_TessLevelOuter[0] = 1;
            gl_TessLevelOuter[1] = 1;
            gl_TessLevelOuter[2] = 1;
            gl_TessLevelOuter[3] = 1;

            gl_TessLevelInner[0] = 0;
            gl_TessLevelInner[1] = 0;
        }
    }
}