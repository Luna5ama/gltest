package dev.luna5ama.gltest.structs

import dev.luna5ama.kmogus.struct.Struct

@Struct
class DrawElementsIndirectCommand(
    val count: Int,
    val instanceCount: Int,
    val firstIndex: Int,
    val baseVertex: Int,
    val baseInstance: Int
)

@Struct(sizeAlignment = 1L, size = 16L)
class DrawArraysIndirectCommand(
    val count: Int,
    val instanceCount: Int,
    val first: Int,
    val baseInstance: Int
)

@Struct(sizeAlignment = 1L, size = 8L)
class DrawMeshTasksIndirectCommandNV(
    val count: Int,
    val first: Int
)

@Struct(sizeAlignment = 1L, size = 12L)
class DispatchIndirectCommand(
    val num_groups_x: Int,
    val num_groups_y: Int,
    val num_groups_z: Int
)