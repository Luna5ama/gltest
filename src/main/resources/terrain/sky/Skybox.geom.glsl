#version 460 core

layout(triangles) in;
layout(triangle_strip, max_vertices = 3) out;

struct GeomData {
    vec3 gBoxCoord;
    int gFaceIndex;
};

in GeomData geomData[];

out vec3 fBoxCoord;

void main() {
    gl_Layer = geomData[0].gFaceIndex;

    gl_Position = gl_in[0].gl_Position;
    fBoxCoord = geomData[0].gBoxCoord;
    EmitVertex();

    gl_Position = gl_in[1].gl_Position;
    fBoxCoord = geomData[1].gBoxCoord;
    EmitVertex();

    gl_Position = gl_in[2].gl_Position;
    fBoxCoord = geomData[2].gBoxCoord;
    EmitVertex();

    EndPrimitive();
}