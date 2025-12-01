#define FORMAT_R32UI 0
#define FORMAT_RGBA8UN 1
#define FORMAT_RGBA16F 2

#define DATA_FLOAT 0
#define DATA_UINT 1
#define DATA_INT 2

#if FAST_CLEAR_FORMAT == FORMAT_R32UI
#define IMAGE_FORMAT rgba32ui
#define IMAGE_NAME r32uiImage
#define IMAGE_DATA_TYPE DATA_UINT

#elif FAST_CLEAR_FORMAT == FORMAT_RGBA8UN
#define IMAGE_FORMAT rgba8
#define IMAGE_NAME rgba8unImage
#define IMAGE_DATA_TYPE DATA_FLOAT

#elif FAST_CLEAR_FORMAT == FORMAT_RGBA16F
#define IMAGE_FORMAT rgba16f
#define IMAGE_NAME rgba16fImage
#define IMAGE_DATA_TYPE DATA_FLOAT
#endif

#if IMAGE_DATA_TYPE == DATA_FLOAT
#define CONVERT_UVEC(u) hash_uintToFloat(u)
#define ZERO vec4(0.0)
#define ONE vec4(1.0)
#define ONE_BITS vec4(uintBitsToFloat(0xffffffffU))
#define IMAGE_TYPE image2D

#elif IMAGE_DATA_TYPE == DATA_UINT
#define CONVERT_UVEC(u) u
#define ZERO uvec4(0u)
#define ONE uvec4(1u)
#define ONE_BITS uvec4(0xffffffffU)
#define IMAGE_TYPE uimage2D

#elif IMAGE_DATA_TYPE == DATA_INT
#define CONVERT_UVEC(u) ivec4(u)
#define ZERO ivec4(0)
#define ONE ivec4(1)
#define ONE_BITS ivec4(-1)
#define IMAGE_TYPE iimage2D

#endif