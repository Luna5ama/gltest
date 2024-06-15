package dev.luna5ama.gltest.structs

import dev.luna5ama.kmogus.struct.Padding
import dev.luna5ama.kmogus.struct.Struct

@Struct
class FontVertex(
    val position: Vec2f32,
    val vertUV: Vec2i16,
    val colorIndex: Byte,
    val overrideColor: Byte,
    val shadow: Byte
)

@Struct(sizeAlignment = 1L)
class TerrainVertex(
    val posX: Short,
    val posY: Short,
    val posZ: Short,
    val uvU: Short,
    val uvV: Short,
    val normalX: Byte,
    val normalY: Byte,
    val normalZ: Byte,
    @Padding(1)
    val lightMapX: Byte,
    val lightMapY: Byte,
    val colorR: Byte,
    val colorG: Byte,
    val colorB: Byte,
    val attrib: Byte
)

@Struct
class QuadPos(
    val posX1: Short,
    val posX2: Short,
    val posX3: Short,
    val posX4: Short,
    val posY1: Short,
    val posY2: Short,
    val posY3: Short,
    val posY4: Short,
    val posZ1: Short,
    val posZ2: Short,
    val posZ3: Short,
    val posZ4: Short
)

@Struct
class QuadTexCoord(
    val x1: Short,
    val x2: Short,
    val x3: Short,
    val x4: Short,

    val y1: Short,
    val y2: Short,
    val y3: Short,
    val y4: Short
)

@Struct
class QuadExtraAttrib(
    val lightCoordX1: Byte,
    val lightCoordX2: Byte,
    val lightCoordX3: Byte,
    val lightCoordX4: Byte,

    val lightCoordY1: Byte,
    val lightCoordY2: Byte,
    val lightCoordY3: Byte,
    val lightCoordY4: Byte,

    val r1: Byte,
    val r2: Byte,
    val r3: Byte,
    val r4: Byte,

    val g1: Byte,
    val g2: Byte,
    val g3: Byte,
    val g4: Byte,

    val b1: Byte,
    val b2: Byte,
    val b3: Byte,
    val b4: Byte,

    val materialID: Int
)

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
class DrawArraysIndirectCommand(
    val count: Int,
    val instanceCount: Int,
    val first: Int,
    val baseInstance: Int
)

@Struct(sizeAlignment = 1L, size = 16L)
class MeshShaderBatchInfo(
    val chunkIndex: Int,
    val quadStartIndex: Int,
    val quadCount: Int,
    val chunkOffsetEncoded: Int
)