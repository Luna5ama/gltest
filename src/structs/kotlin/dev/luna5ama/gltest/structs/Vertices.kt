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

@Struct(sizeAlignment = 1L, size = 24L)
class PointPos(
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

@Struct(sizeAlignment = 1L, size = 12L)
class PointAttrib(
    val uvU: Short,
    val uvV: Short,
    val lightMapX: Byte,
    val lightMapY: Byte,
    @Padding(2)
    val colorR: Byte,
    val colorG: Byte,
    val colorB: Byte
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
    val count: Byte,
    val offsetX : Byte,
    val offsetY : Byte,
    val offsetZ : Byte,
)