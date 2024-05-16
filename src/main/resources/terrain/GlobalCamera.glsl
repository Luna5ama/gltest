struct Camera {
    mat4 projection;
    mat4 modelView;
    mat4 combined;
    mat4 invProjection;
    mat4 invModelView;
    mat4 invCombined;
    vec4 screenSize;
    vec3 viewDir;
    vec2 cameraRotation;
    vec2 planeZ;
    uint gFrameIndex;
};

layout(std140) uniform GlobalCameraJitter {
    Camera ubo_cameraJitter;
} ;

layout(std140) uniform GlobalCameraNoJitter {
    Camera ubo_cameraNoJitter;
};

layout(std140) uniform GlobalCameraDefault {
    Camera ubo_cameraDefault;
};

layout(std140) uniform PrevGlobalCameraJitter {
    Camera ubo_prevCameraJitter;
} ;

layout(std140) uniform PrevGlobalCameraNoJitter {
    Camera ubo_prevCameraNoJitter;
};

layout(std140) uniform PrevGlobalCameraDefault {
    Camera ubo_prevCameraDefault;
};

float global_linearizeDepth(Camera camera, float depth) {
    float near = camera.planeZ.x;
    float far = camera.planeZ.y;
    return (near * far) / (depth * (near - far) + far);
}