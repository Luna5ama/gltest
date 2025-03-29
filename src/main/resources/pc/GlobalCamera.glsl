struct Camera {
    // 0 bytes
    mat4 projection;
    mat4 modelView;
    mat4 combined;
    mat4 invProjection;
    mat4 invModelView;
    mat4 invCombined;
    // 384 bytes
    mat4 projectionNoJitter;
    mat4 modelViewNoJitter;
    mat4 combinedNoJitter;
    mat4 invProjectionNoJitter;
    mat4 invModelViewNoJitter;
    mat4 invCombinedNoJitter;
    // 768 bytes
    mat4 prevProjection;
    mat4 prevModelView;
    mat4 prevCombined;
    mat4 prevInvProjection;
    mat4 prevInvModelView;
    mat4 prevInvCombined;
    // 1152 bytes
    mat4 prevProjectionNoJitter;
    mat4 prevModelViewNoJitter;
    mat4 prevCombinedNoJitter;
    mat4 prevInvProjectionNoJitter;
    mat4 prevInvModelViewNoJitter;
    mat4 prevInvCombinedNoJitter;
    // 1536 bytes
    vec4 screenSize;
    vec3 viewDir;
    vec3 cameraDelta;
    vec2 cameraRotation;
    vec2 planeZ;
    uint gFrameIndex;
};

layout(std140, binding = 0) uniform GlobalCamera {
    Camera ubo_camera;
};

float global_linearizeDepth(Camera camera, float depth) {
    float near = camera.planeZ.x;
    float far = camera.planeZ.y;
    return (near * far) / (depth * (near - far) + far);
}