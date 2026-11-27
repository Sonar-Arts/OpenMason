#!/usr/bin/env python3
"""Generates the ctest `import` fixtures (OpenMason #173). Pure stdlib + Pillow (JPEG / PNG only).

Run from anywhere: python3 make_fixtures.py   (writes next to this script; the output is committed).
Every value the test checks is written here explicitly - keep import_test.cpp in step when changing one.
"""

import base64
import io
import json
import os
import struct

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))

# component types
BYTE, UBYTE, SHORT, USHORT, UINT, FLOAT = 5120, 5121, 5122, 5123, 5125, 5126
FMT = {BYTE: "b", UBYTE: "B", SHORT: "h", USHORT: "H", UINT: "I", FLOAT: "f"}
NCOMP = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4, "MAT4": 16}


def write(name, data):
    path = os.path.join(HERE, name)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    mode = "wb" if isinstance(data, (bytes, bytearray)) else "w"
    with open(path, mode, newline="\n" if mode == "w" else None) as f:
        f.write(data)


class Gltf:
    """Tiny glTF builder: one binary buffer, one bufferView per accessor (4-byte aligned)."""

    def __init__(self, generator="OpenMason import fixtures"):
        self.doc = {"asset": {"version": "2.0", "generator": generator}}
        self.bin = bytearray()

    def _list(self, key):
        return self.doc.setdefault(key, [])

    def view(self, raw, target=None, stride=None):
        while len(self.bin) % 4:
            self.bin.append(0)
        v = {"buffer": 0, "byteOffset": len(self.bin), "byteLength": len(raw)}
        if target:
            v["target"] = target
        if stride:
            v["byteStride"] = stride
        self.bin.extend(raw)
        self._list("bufferViews").append(v)
        return len(self.doc["bufferViews"]) - 1

    @staticmethod
    def pack(ctype, values):
        return struct.pack("<%d%s" % (len(values), FMT[ctype]), *values)

    def accessor(self, ctype, atype, values, normalized=False, target=None, minmax=False, pad=False):
        n = NCOMP[atype]
        assert len(values) % n == 0
        raw = self.pack(ctype, values)
        # Element alignment inside a view: pad each element of 1/2-byte vec types to 4 bytes (glTF rule).
        size = struct.calcsize(FMT[ctype])
        stride = None
        if (atype != "SCALAR" or pad) and (size * n) % 4:
            esize = size * n
            stride = (esize + 3) // 4 * 4
            out = bytearray()
            for i in range(0, len(values), n):
                out.extend(self.pack(ctype, values[i:i + n]))
                out.extend(b"\0" * (stride - esize))
            raw = bytes(out)
        a = {"bufferView": self.view(raw, target, stride), "componentType": ctype, "count": len(values) // n,
             "type": atype}
        if normalized:
            a["normalized"] = True
        if minmax:
            a["min"] = [min(values[i::n]) for i in range(n)]
            a["max"] = [max(values[i::n]) for i in range(n)]
        self._list("accessors").append(a)
        return len(self.doc["accessors"]) - 1

    def add(self, key, obj):
        self._list(key).append(obj)
        return len(self.doc[key]) - 1

    def finish(self, uri=None, embed=False):
        while len(self.bin) % 4:
            self.bin.append(0)
        buf = {"byteLength": len(self.bin)}
        if embed:
            buf["uri"] = "data:application/octet-stream;base64," + base64.b64encode(bytes(self.bin)).decode()
        elif uri:
            buf["uri"] = uri
        self.doc["buffers"] = [buf]
        return self.doc

    def json(self, **kw):
        return json.dumps(self.finish(**kw), indent=1) + "\n"

    def glb(self):
        doc = self.finish()
        js = json.dumps(doc, separators=(",", ":")).encode()
        while len(js) % 4:
            js += b" "
        binc = bytes(self.bin)
        total = 12 + 8 + len(js) + 8 + len(binc)
        return (struct.pack("<III", 0x46546C67, 2, total) + struct.pack("<II", len(js), 0x4E4F534A) + js +
                struct.pack("<II", len(binc), 0x004E4942) + binc)


def png_bytes(w, h, pixels, mode="RGBA"):
    im = Image.new(mode, (w, h))
    im.putdata(pixels)
    b = io.BytesIO()
    im.save(b, format="PNG")
    return b.getvalue()


def jpeg_bytes():
    im = Image.new("RGB", (8, 4))
    im.putdata([(255, 0, 0) if (x // 4) == 0 else (0, 0, 255) for y in range(4) for x in range(8)])
    b = io.BytesIO()
    im.save(b, format="JPEG", quality=95)
    return b.getvalue()


TEX_PNG = png_bytes(2, 2, [(255, 0, 0, 255), (0, 255, 0, 255), (0, 0, 255, 255), (255, 255, 255, 128)])

# ---------------------------------------------------------------------------------------------------------------
# 1. skin_strip.gltf + skin_strip.bin: 2-bone strip, 8 vertices, JOINTS_0/WEIGHTS_0 + JOINTS_1/WEIGHTS_1
#    (vertices 2 and 5 carry 6 non-zero influences), int16 POSITION (KHR_mesh_quantization, required).


def skin_strip():
    g = Gltf("Khronos glTF Blender I/O v4.2.70")
    pos = []
    for row in range(4):
        pos += [0, row, 0, 1, row, 0]
    p = g.accessor(SHORT, "VEC3", pos, minmax=True)
    tris = []
    for row in range(3):
        a = row * 2
        tris += [a, a + 1, a + 3, a, a + 3, a + 2]
    idx = g.accessor(USHORT, "SCALAR", tris)
    j0 = [0, 1, 0, 0] * 8
    w0 = [1.0, 0.0, 0.0, 0.0,  # v0
          1.0, 0.0, 0.0, 0.0,  # v1
          0.25, 0.25, 0.125, 0.125,  # v2 (+2 in set 1 = 6)
          0.5, 0.5, 0.0, 0.0,  # v3
          0.5, 0.5, 0.0, 0.0,  # v4
          0.0, 0.25, 0.125, 0.125,  # v5: first slot zero (dropped) -> 3 + 3 in set 1 = 6
          0.0, 1.0, 0.0, 0.0,  # v6
          0.0, 1.0, 0.0, 0.0]  # v7
    j1 = [0, 0, 0, 0] * 8
    w1 = [0] * 32
    j1[8:12] = [1, 0, 0, 0]
    w1[8:12] = [51, 102, 0, 0]  # normalized UNSIGNED_BYTE: 0.2, 0.4
    j1[20:24] = [1, 0, 1, 0]
    w1[20:24] = [51, 51, 0, 153]  # 0.2, 0.2, (0 dropped), 0.6
    # Joint indices in set 0 for v2 / v5: [0,1,1,0] to vary.
    j0[8:12] = [0, 1, 1, 0]
    j0[20:24] = [0, 1, 0, 1]
    a_j0 = g.accessor(UBYTE, "VEC4", j0)
    a_w0 = g.accessor(FLOAT, "VEC4", w0)
    a_j1 = g.accessor(USHORT, "VEC4", j1)
    a_w1 = g.accessor(UBYTE, "VEC4", w1, normalized=True)
    ibm = g.accessor(FLOAT, "MAT4", [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1,
                                     1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, -1.5, 0, 1])
    g.add("meshes", {"name": "StripMesh", "primitives": [{"attributes": {
        "POSITION": p, "JOINTS_0": a_j0, "WEIGHTS_0": a_w0, "JOINTS_1": a_j1, "WEIGHTS_1": a_w1},
        "indices": idx}]})
    g.add("nodes", {"name": "Strip", "mesh": 0, "skin": 0})
    g.add("nodes", {"name": "Armature", "children": [2], "rotation": [0, 0, 0, 1]})
    g.add("nodes", {"name": "Bone", "children": [3]})
    g.add("nodes", {"name": "Bone.001", "translation": [0, 1.5, 0]})
    g.add("skins", {"name": "Armature", "joints": [2, 3], "inverseBindMatrices": ibm, "skeleton": 1})
    g.doc["scenes"] = [{"nodes": [0, 1]}]
    g.doc["scene"] = 0
    g.doc["extensionsUsed"] = ["KHR_mesh_quantization"]
    g.doc["extensionsRequired"] = ["KHR_mesh_quantization"]
    write("skin_strip.gltf", g.json(uri="skin_strip.bin"))
    write("skin_strip.bin", bytes(g.bin))


# ---------------------------------------------------------------------------------------------------------------
# 2. morph_cube.gltf: embedded buffer, 8-vertex cube, UNSIGNED_BYTE indices, 2 targets; target 1 is a sparse
#    accessor without a base view (2 entries), mesh weights + extras.targetNames.

CUBE = [-1, -1, -1, 1, -1, -1, 1, 1, -1, -1, 1, -1, -1, -1, 1, 1, -1, 1, 1, 1, 1, -1, 1, 1]


def morph_cube():
    g = Gltf()
    p = g.accessor(FLOAT, "VEC3", CUBE, minmax=True)
    tris = [0, 2, 1, 0, 3, 2, 4, 5, 6, 4, 6, 7, 0, 1, 5, 0, 5, 4, 3, 7, 6, 3, 6, 2, 0, 4, 7, 0, 7, 3, 1, 2, 6, 1, 6, 5]
    idx = g.accessor(UBYTE, "SCALAR", tris)
    t0 = []
    for i in range(8):
        t0 += [0, 0.5, 0] if CUBE[i * 3 + 1] > 0 else [0, 0, 0]
    a_t0 = g.accessor(FLOAT, "VEC3", t0, minmax=True)
    # target 1: sparse, vertices 1 and 6 move.
    sp_idx = g.view(Gltf.pack(USHORT, [1, 6]))
    sp_val = g.view(Gltf.pack(FLOAT, [0.25, 0, 0, 0, 0, -0.75]))
    g.doc["accessors"].append({"componentType": FLOAT, "count": 8, "type": "VEC3", "min": [0, 0, -0.75],
                               "max": [0.25, 0, 0], "sparse": {"count": 2, "indices": {
                                   "bufferView": sp_idx, "componentType": USHORT}, "values": {"bufferView": sp_val}}})
    a_t1 = len(g.doc["accessors"]) - 1
    g.add("meshes", {"name": "Cube", "weights": [0.25, 0.75], "extras": {"targetNames": ["Smile", "Frown"],
                                                                          "note": "kept"},
                     "primitives": [{"attributes": {"POSITION": p}, "indices": idx, "mode": 4,
                                     "targets": [{"POSITION": a_t0}, {"POSITION": a_t1}]}]})
    g.add("nodes", {"name": "Cube", "mesh": 0})
    g.doc["scenes"] = [{"nodes": [0]}]
    write("morph_cube.gltf", g.json(embed=True))


# ---------------------------------------------------------------------------------------------------------------
# 3. textured_png.gltf (embedded PNG data URI, KHR_texture_transform, 2 UV sets, sampler) and its .glb twin
#    (image in a bufferView). 4. textured_jpeg.gltf with an external JPEG in a folder with a space.

QUAD_POS = [0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0]
QUAD_UV0 = [0, 1, 1, 1, 1, 0, 0, 0]
QUAD_UV1 = [0, 0, 0.5, 0, 0.5, 0.5, 0, 0.5]


def quad(g, image):
    p = g.accessor(FLOAT, "VEC3", QUAD_POS, minmax=True)
    n = g.accessor(FLOAT, "VEC3", [0, 0, 1] * 4)
    uv0 = g.accessor(FLOAT, "VEC2", QUAD_UV0)
    uv1 = g.accessor(FLOAT, "VEC2", QUAD_UV1)
    idx = g.accessor(UBYTE, "SCALAR", [0, 1, 2, 0, 2, 3])
    g.add("images", image)
    g.add("samplers", {"magFilter": 9728, "minFilter": 9728, "wrapS": 33071, "wrapT": 33648})
    g.add("textures", {"source": 0, "sampler": 0})
    g.add("materials", {"name": "Checker", "doubleSided": True, "alphaMode": "MASK", "alphaCutoff": 0.25,
                        "emissiveFactor": [0.5, 0.25, 0],
                        "pbrMetallicRoughness": {"baseColorFactor": [0.5, 0.75, 1.0, 0.5], "metallicFactor": 0.0,
                                                 "roughnessFactor": 0.5,
                                                 "baseColorTexture": {"index": 0, "texCoord": 0, "extensions": {
                                                     "KHR_texture_transform": {"offset": [0.5, 0.25],
                                                                               "rotation": 1.5,
                                                                               "scale": [2, 3], "texCoord": 1}}}},
                        "extras": {"source": "fixture"}})
    g.add("meshes", {"name": "Quad", "primitives": [{"attributes": {"POSITION": p, "NORMAL": n, "TEXCOORD_0": uv0,
                                                                    "TEXCOORD_1": uv1},
                                                     "indices": idx, "material": 0}]})
    g.add("nodes", {"name": "Quad", "mesh": 0})
    g.doc["scenes"] = [{"nodes": [0]}]
    g.doc["extensionsUsed"] = ["KHR_texture_transform", "KHR_materials_emissive_strength"]


def textured():
    g = Gltf()
    quad(g, {"name": "checker", "uri": "data:image/png;base64," + base64.b64encode(TEX_PNG).decode()})
    write("textured_png.gltf", g.json(embed=True))

    g = Gltf()
    quad(g, {"name": "checker", "uri": "textures/checker%201.jpg"})
    write("textured_jpeg.gltf", g.json(uri="textured_jpeg.bin"))
    write("textured_jpeg.bin", bytes(g.bin))
    write("textures/checker 1.jpg", jpeg_bytes())

    g = Gltf()
    img_view = g.view(TEX_PNG)
    assert img_view == 0
    quad(g, {"name": "checker", "mimeType": "image/png", "bufferView": img_view})
    data = g.glb()
    write("quad.glb", data)
    write("truncated.glb", data[:len(data) // 2])


# ---------------------------------------------------------------------------------------------------------------
# 5. hierarchy.gltf: TRS + matrix nodes, node extras, two scenes (default = 1).


def hierarchy():
    g = Gltf()
    g.add("nodes", {"name": "Root", "translation": [1, 2, 3], "rotation": [0, 0.7071067690849304, 0,
                                                                         0.7071067690849304],
                    "scale": [2, 2, 2], "children": [1, 2], "extras": {"tag": "root"}})
    g.add("nodes", {"name": "MatrixChild", "matrix": [1, 0, 0, 0, 0, 0, 1, 0, 0, -1, 0, 0, 5, 6, 7, 1]})
    g.add("nodes", {"name": "ScaleOnly", "scale": [1, 3, 1], "children": [3]})
    g.add("nodes", {"name": "Leaf"})
    g.add("nodes", {"name": "Other"})
    g.doc["scenes"] = [{"name": "unused", "nodes": [4]}, {"name": "main", "nodes": [0, 4]}]
    g.doc["scene"] = 1
    doc = g.finish()
    del doc["buffers"]
    write("hierarchy.gltf", json.dumps(doc, indent=1) + "\n")


# ---------------------------------------------------------------------------------------------------------------
# 6. animation.gltf: translation LINEAR, rotation STEP (normalized SHORT quaternions), weights CUBICSPLINE.


def animation():
    g = Gltf()
    p = g.accessor(FLOAT, "VEC3", [0, 0, 0, 1, 0, 0, 0, 1, 0], minmax=True)
    t0 = g.accessor(FLOAT, "VEC3", [0, 0, 1] * 3)
    t1 = g.accessor(FLOAT, "VEC3", [1, 0, 0] * 3)
    g.add("meshes", {"name": "Tri", "weights": [0, 0], "primitives": [{"attributes": {"POSITION": p},
                                                                       "targets": [{"POSITION": t0},
                                                                                   {"POSITION": t1}]}]})
    g.add("nodes", {"name": "Mover", "mesh": 0})
    times = g.accessor(FLOAT, "SCALAR", [0.0, 0.5, 1.0], minmax=True)
    tr = g.accessor(FLOAT, "VEC3", [0, 0, 0, 1, 2, 3, 2, 4, 6])
    rot = g.accessor(SHORT, "VEC4", [0, 0, 0, 32767, 0, 32767, 0, 0, 0, 0, 32767, 0], normalized=True)
    times2 = g.accessor(FLOAT, "SCALAR", [0.0, 2.0], minmax=True)
    # CUBICSPLINE weights, 2 keys x [in(2), value(2), out(2)]
    wts = g.accessor(FLOAT, "SCALAR", [0.1, 0.2, 0.0, 0.0, 0.3, 0.4,
                                       0.5, 0.6, 1.0, 0.5, 0.7, 0.8])
    g.add("animations", {"name": "Wiggle", "samplers": [
        {"input": times, "output": tr, "interpolation": "LINEAR"},
        {"input": times, "output": rot, "interpolation": "STEP"},
        {"input": times2, "output": wts, "interpolation": "CUBICSPLINE"}],
        "channels": [{"sampler": 0, "target": {"node": 0, "path": "translation"}},
                     {"sampler": 1, "target": {"node": 0, "path": "rotation"}},
                     {"sampler": 2, "target": {"node": 0, "path": "weights"}},
                     {"sampler": 0, "target": {"path": "translation"}}]})
    g.doc["scenes"] = [{"nodes": [0]}]
    g.doc["extensionsUsed"] = ["KHR_mesh_quantization"]
    write("animation.gltf", g.json(embed=True))


# ---------------------------------------------------------------------------------------------------------------
# 7. custom_attr.gltf: `_COVERED_JACKET` float + `_MASK` normalized UNSIGNED_BYTE, COLOR_0 VEC3, no indices.


def custom_attr():
    g = Gltf()
    p = g.accessor(FLOAT, "VEC3", [0, 0, 0, 1, 0, 0, 0, 1, 0], minmax=True)
    jacket = g.accessor(FLOAT, "SCALAR", [0.0, 0.5, 1.0])
    mask = g.accessor(UBYTE, "SCALAR", [0, 255, 51], normalized=True, pad=True)  # vertex attribute: 4-byte stride
    col = g.accessor(FLOAT, "VEC3", [1, 0, 0, 0, 1, 0, 0, 0, 1])
    g.add("meshes", {"name": "Jacket", "primitives": [{"attributes": {"POSITION": p, "_COVERED_JACKET": jacket,
                                                                      "_MASK": mask, "COLOR_0": col},
                                                       "extras": {"prim": 1}}]})
    g.add("nodes", {"name": "Jacket", "mesh": 0})
    write("custom_attr.gltf", g.json(embed=True))


# ---------------------------------------------------------------------------------------------------------------
# 8. strip.gltf: a TRIANGLE_STRIP (5 verts, one degenerate pair), a TRIANGLE_FAN, a POINTS primitive (skipped).


def strip():
    g = Gltf()
    p = g.accessor(FLOAT, "VEC3", [0, 0, 0, 0, 1, 0, 1, 0, 0, 1, 1, 0, 2, 0, 0, 2, 1, 0], minmax=True)
    s_idx = g.accessor(UBYTE, "SCALAR", [0, 1, 2, 3, 3, 4, 5])
    f_idx = g.accessor(UBYTE, "SCALAR", [0, 2, 3, 1])
    g.add("meshes", {"name": "Strips", "primitives": [
        {"attributes": {"POSITION": p}, "indices": s_idx, "mode": 5},
        {"attributes": {"POSITION": p}, "indices": f_idx, "mode": 6},
        {"attributes": {"POSITION": p}, "mode": 0}]})
    g.add("nodes", {"name": "Strips", "mesh": 0})
    write("strip.gltf", g.json(embed=True))


# ---------------------------------------------------------------------------------------------------------------
# 9. OBJ + MTL (+ wood.png): two objects, quads, a pentagon, smoothing groups, negative indices, two materials,
#    a group, map_Kd with options, a line continuation, vertex colours on the first object.

OBJ = """# fixture
mtllib model.mtl
o Box
v 0 0 0 1 0 0
v 1 0 0 0 1 0
v 1 1 0 0 0 1
v 0 1 0 1 1 1
vt 0 0
vt 1 0
vt 1 1
vt 0 1
vn 0 0 1
g front
usemtl Wood
s 1
f 1/1/1 2/2/1 3/3/1 4/4/1
s off
f -4/-4/-1 -2/-2/-1 \\
  -1/-1/-1
o Panel
v 2 0 0
v 3 0 0
v 3.5 1 0
v 2.5 2 0
v 1.5 1 0
g
usemtl Paint
s 2
f 5//1 6//1 7//1 8//1 9//1
usemtl Wood
f 5/1 6/2 7/3
usemtl Missing
f 5 7 8
"""

MTL = """# fixture materials
newmtl Wood
Ka 0 0 0
Kd 0.8 0.6 0.4
Ks 0.5 0.5 0.5
Ns 10
d 1
map_Kd -s 1 1 1 -o 0 0 0 tex/wood grain.png

newmtl Paint
Kd 0.1 0.2 0.3
Tr 0.25
Ke 0.5 0 0
illum 2
"""


def obj():
    write("model.obj", OBJ)
    write("model.mtl", MTL)
    write("tex/wood grain.png", png_bytes(1, 1, [(10, 20, 30)], mode="RGB"))


# ---------------------------------------------------------------------------------------------------------------
# 10. malformed inputs: each must raise format_error.


def malformed():
    write("bad_json.gltf", '{"asset": {"version": "2.0"}, "nodes": [ {"name": "x" ]\n')
    g = Gltf()
    p = g.accessor(FLOAT, "VEC3", QUAD_POS)
    g.add("meshes", {"primitives": [{"attributes": {"POSITION": p}}]})
    write("missing_buffer.gltf", g.json(uri="does_not_exist.bin"))
    g = Gltf()
    p = g.accessor(FLOAT, "VEC3", QUAD_POS)
    idx = g.accessor(UBYTE, "SCALAR", [0, 1, 9])
    g.add("meshes", {"primitives": [{"attributes": {"POSITION": p}, "indices": idx}]})
    write("bad_index.gltf", g.json(embed=True))
    g = Gltf()
    p = g.accessor(FLOAT, "VEC3", QUAD_POS)
    g.add("meshes", {"primitives": [{"attributes": {"POSITION": p}}]})
    g.doc["extensionsUsed"] = ["KHR_draco_mesh_compression"]
    g.doc["extensionsRequired"] = ["KHR_draco_mesh_compression"]
    write("unsupported_ext.gltf", g.json(embed=True))
    g = Gltf()
    p = g.accessor(FLOAT, "VEC3", QUAD_POS)
    g.doc["accessors"][p]["count"] = 400  # past the end of its view
    g.add("meshes", {"primitives": [{"attributes": {"POSITION": p}}]})
    write("accessor_overrun.gltf", g.json(embed=True))
    write("bad_index.obj", "v 0 0 0\nv 1 0 0\nv 0 1 0\nf 1 2 99\n")
    write("zero_index.obj", "v 0 0 0\nv 1 0 0\nv 0 1 0\nf 0 1 2\n")
    write("bad_number.obj", "v 0 0 zero\n")
    write("bad_relative.obj", "v 0 0 0\nf -1 -2 -3\n")


if __name__ == "__main__":
    skin_strip()
    morph_cube()
    textured()
    hierarchy()
    animation()
    custom_attr()
    strip()
    obj()
    malformed()
