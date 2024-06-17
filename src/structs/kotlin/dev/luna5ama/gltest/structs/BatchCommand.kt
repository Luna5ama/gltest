package dev.luna5ama.gltest.structs

import dev.luna5ama.kmogus.struct.Padding
import dev.luna5ama.kmogus.struct.Struct

@Struct(sizeAlignment = 1L, size = 16L)
class PointBatchInfo(
    val first: Int,
    val count: Int,
    val offsetX : Byte,
    val offsetY : Byte,
    val offsetZ : Byte,
    @Padding(1)
    val pointSampleCount: Int
)

@Struct(sizeAlignment = 1L, size = 8L)
class QuadBatchInfo(
    val first: Int,
    val offsetX : Byte,
    val offsetY : Byte,
    val offsetZ : Byte,
    val count: Byte,
)

@Struct(sizeAlignment = 1L, size = 16L)
class MeshShaderBatchInfo(
    val chunkIndex: Int,
    val quadStartIndex: Int,
    val quadCount: Int,
    val chunkOffsetEncoded: Int
)

@Struct(sizeAlignment = 1L, size = 8L)
class CullTestCommand(
    val chunkIndex: Int,
    val chunkOffsetEncoded: Int
)

@Struct(sizeAlignment = 1L, size = 12L)
class CullMSCommandHeader(
    val count: Int,
    val first: Int,
    val chunkCount: Int
)